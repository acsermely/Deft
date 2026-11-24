package app.deft.ui.special

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.bridge.ConfirmChoice
import app.deft.bridge.GrantPrompt
import app.deft.bridge.SpecialAsks
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.catalog.isGranted
import app.deft.data.InstalledWebApp
import app.deft.data.SpecialGrantResult
import app.deft.special.SpecialAccessFlow
import app.deft.special.SpecialReview
import app.deft.special.SystemAccess
import app.deft.ui.OverlayGuard
import app.deft.ui.theme.AppDeftTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Step {
    val app: InstalledWebApp?

    data object Loading : Step {
        override val app: InstalledWebApp? = null
    }

    data class Ask(
        override val app: InstalledWebApp,
        val cap: Capability,
        val reason: String,
        val heldBy: String?,
    ) : Step

    data class System(
        override val app: InstalledWebApp,
        val cap: Capability,
        val access: SystemAccess,
        val stage: SpecialAccessFlow.Stage,
    ) : Step
}

private sealed interface AllowOutcome {
    data object Done : AllowOutcome
    data class Message(val text: String) : AllowOutcome
    data class Held(val name: String) : AllowOutcome
    data class NeedsSystem(val access: SystemAccess, val stage: SpecialAccessFlow.Stage) : AllowOutcome
}

/**
 * PARITY G0: the only place a special-access capability is granted. Opened from the app's card
 * on the dashboard, or by a bridge call when the app asks ([SpecialAsk]; the call then waits for its close). Names the app and origin, shows the manifest reason and a plain warning,
 * refuses while another app holds an exclusive group, and needs the screen lock to allow.
 * If Android has its own switch for the power, a second step opens that system screen once
 * and reads the state back when the user returns.
 *
 * PARITY G5: for `location.background` that second step is Android's "Allow all the time"
 * location. Android 11+ asks it only on its own and only after precise or approximate location,
 * so the step asks location first (if needed), then background location, which opens the
 * launcher's location page in Settings. If Android no longer shows the request (asked and
 * refused before), "Open app settings" leads to the same page by hand.
 */
class SpecialGrantActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    private val foregroundLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) askBackgroundLocation()
    }

    // The state is read back on resume (see LaunchedEffect(tick)); nothing to do with the result here.
    private val backgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // PARITY G11: Android's role request (and device admin activation) must be started for a result;
    // the state is read back on resume.
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private fun hasForegroundLocation(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun askBackgroundLocation() {
        runCatching { backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    }

    /** Starts Android's own step for [access]; false when this phone has no such screen. */
    private fun openSystemStep(access: SystemAccess): Boolean {
        if (access == SystemAccess.BACKGROUND_LOCATION) {
            if (hasForegroundLocation()) {
                askBackgroundLocation()
            } else {
                runCatching {
                    foregroundLocation.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            }
            return true
        }
        // PARITY G11: the keyboard is turned on in keyboard settings, then chosen in Android's picker.
        if (access == SystemAccess.IME) {
            if (app.deft.ime.DeftInputMethodService.isEnabled(this)) {
                return showKeyboardPicker()
            }
            return SpecialAccessFlow.start(this, access)
        }
        if (access.asksInPlace) {
            val request = access.requestIntent(this) ?: return false
            return runCatching { roleRequest.launch(request) }.isSuccess
        }
        return SpecialAccessFlow.start(this, access)
    }

    /** PARITY G11: Android's keyboard picker (a system dialog over this sheet); false when it cannot be shown. */
    private fun showKeyboardPicker(): Boolean = runCatching {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)!!.showInputMethodPicker()
    }.isSuccess

    /** PARITY G11: the keyboard picker was shown once by itself after the user came back from keyboard settings. */
    private var keyboardPickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayGuard.protect(this)
        val webAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
        val cap = intent.getStringExtra(EXTRA_CAPABILITY)?.let { Capability.fromId(it) }
        val systemOnly = intent.getBooleanExtra(EXTRA_SYSTEM_ONLY, false)
        // A bridge call can open the sheet for only its Android step; a non-special capability has no grant to show.
        if (webAppId == null || cap == null || (cap.risk != Risk.SPECIAL && !systemOnly)) {
            finish()
            return
        }
        val fromApp = intent.getBooleanExtra(EXTRA_FROM_APP, false)
        val repo = DeftApp.instance.repository
        setContent {
            AppDeftTheme {
                var step by remember { mutableStateOf<Step>(Step.Loading) }
                var note by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val tick by resumeTick

                LaunchedEffect(Unit) {
                    val loaded = withContext(Dispatchers.IO) {
                        val app = repo.getApp(webAppId) ?: return@withContext null
                        if (cap !in app.requiredCapabilities) return@withContext null
                        if (systemOnly) {
                            // Only Android's step, and only while the launcher grant is held (a call re-checks it).
                            if (!repo.grantLevel(webAppId, cap).isGranted()) return@withContext null
                            val access = SystemAccess.forAsk(cap) ?: return@withContext null
                            if (access.isGranted(this@SpecialGrantActivity)) return@withContext null
                            return@withContext Step.System(app, cap, access, SpecialAccessFlow.beginSystem(this@SpecialGrantActivity, access))
                        }
                        val holder = cap.exclusiveGroup?.let { repo.specialHolder(it) }
                            ?.takeIf { it != webAppId }
                            ?.let { repo.getApp(it)?.name ?: "Another app" }
                        Step.Ask(app, cap, repo.reasonFor(app, cap), holder)
                    }
                    if (loaded == null) {
                        finish()
                    } else {
                        setTitle(loaded.app?.name)
                        step = loaded
                    }
                }

                LaunchedEffect(tick) {
                    val current = step as? Step.System ?: return@LaunchedEffect
                    if (current.stage != SpecialAccessFlow.Stage.OPENED) return@LaunchedEffect
                    // Back from Android's screen: a switch that is on finishes the sheet, and the waiting call re-reads it.
                    if (SpecialAccessFlow.resumed(current.stage, current.access.isGranted(this@SpecialGrantActivity)) ==
                        SpecialAccessFlow.Stage.DONE
                    ) {
                        finish()
                    } else if (current.access == SystemAccess.IME &&
                        app.deft.ime.DeftInputMethodService.isEnabled(this@SpecialGrantActivity)
                    ) {
                        // PARITY G11: on, but not chosen yet. Android's picker needs this window in
                        // focus, so it opens a moment after the user is back (once by itself).
                        note = "Deft's keyboard is on. Now choose it as your keyboard: tap Choose keyboard, " +
                            "then pick Deft in the list."
                        if (!keyboardPickerShown) {
                            keyboardPickerShown = true
                            kotlinx.coroutines.delay(400)
                            showKeyboardPicker()
                        }
                    } else {
                        note = "${current.access.label} is still off for Deft. " +
                            "${current.app.name} has the permission, but it cannot work until you turn it on." +
                            when (current.access) {
                                SystemAccess.BACKGROUND_LOCATION -> " In app settings, open Permissions, then Location."
                                // PARITY G9: Android 13+ greys out the switch for apps not installed from a store.
                                SystemAccess.ACCESSIBILITY ->
                                    " If the switch is greyed out, open app settings, tap the menu at the top right, " +
                                        "choose \"Allow restricted settings\", then try again."
                                // PARITY G11: most phones only let the user pick the assistant in Settings.
                                SystemAccess.ROLE_ASSISTANT ->
                                    " Open default apps, then Digital assistant app, and choose Deft. " +
                                        "If it is not listed there, this phone does not let Deft be the assistant."
                                // PARITY G11: the keyboard has its own switch in keyboard settings.
                                SystemAccess.IME ->
                                    " In keyboard settings, open the list of keyboards (On-screen keyboard or Manage keyboards) " +
                                        "and turn on Deft."
                                // PARITY G11: Android's preview screen sets the wallpaper only with its own button.
                                SystemAccess.WALLPAPER ->
                                    " On Android's preview screen, tap the button to set the wallpaper, then choose the home screen " +
                                        "(or home and lock screen). Tap Try again to open it once more."
                                // PARITY B4: Android's payment app list is under NFC settings.
                                SystemAccess.PAYMENT_DEFAULT ->
                                    " Open payment settings and choose Deft under Contactless payments " +
                                        "(on some phones: Connections, NFC, Contactless payments)."
                                else -> if (current.access.role != null) {
                                    " You can also choose Deft in Android's default apps settings."
                                } else {
                                    ""
                                }
                            }
                    }
                }

                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (val s = step) {
                            Step.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                            is Step.Ask -> AskStep(
                                s = s,
                                fromApp = fromApp,
                                note = note,
                                busy = busy,
                                onClose = { finish() },
                                onDontAsk = {
                                    // The user's final no, audited like a dashboard revoke; the app is not asked again.
                                    busy = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            repo.setGrant(s.app.id, s.cap, GrantLevel.DENIED, byUser = true)
                                            SpecialReview.dashboardAudit(s.cap, GrantLevel.DENIED)?.let {
                                                repo.log(s.app.id, "special", s.cap.id, null, it)
                                            }
                                        }
                                        finish()
                                    }
                                },
                                onAllow = {
                                    busy = true
                                    note = null
                                    scope.launch {
                                        val next = allow(s)
                                        busy = false
                                        when (next) {
                                            AllowOutcome.Done -> finish()
                                            is AllowOutcome.Message -> note = next.text
                                            is AllowOutcome.Held -> step = s.copy(heldBy = next.name)
                                            is AllowOutcome.NeedsSystem ->
                                                step = Step.System(s.app, s.cap, next.access, next.stage)
                                        }
                                    }
                                },
                            )
                            is Step.System -> SystemStep(
                                s = s,
                                fromApp = fromApp,
                                note = note,
                                onOpen = {
                                    step = s.copy(stage = SpecialAccessFlow.open(s.stage))
                                    if (!openSystemStep(s.access)) {
                                        note = if (s.access.asksInPlace) {
                                            // PARITY G11: some phones do not offer the role to other apps.
                                            "This phone does not let Deft be its ${s.access.label.lowercase()}. " +
                                                "${s.app.name} has the permission, but it cannot work on this phone."
                                        } else {
                                            "This phone has no ${s.access.label} screen."
                                        }
                                    }
                                },
                                onDefaultApps = {
                                    // PARITY G11: the keyboard: settings again while it is off, then Android's picker.
                                    if (s.access == SystemAccess.IME) {
                                        note = null
                                        if (!openSystemStep(s.access)) note = "This phone has no keyboard settings screen."
                                        return@SystemStep
                                    }
                                    // PARITY G11: device admin and the live wallpaper have no list to pick from; ask Android again.
                                    if (s.access == SystemAccess.DEVICE_ADMIN || s.access == SystemAccess.WALLPAPER) {
                                        note = null
                                        if (!openSystemStep(s.access)) note = "This phone has no ${s.access.label.lowercase()} screen."
                                        return@SystemStep
                                    }
                                    if (!SpecialAccessFlow.start(this@SpecialGrantActivity, s.access)) {
                                        note = "This phone has no ${s.access.label} settings screen."
                                    }
                                },
                                onAppSettings = {
                                    // The launcher's app info page (also where restricted settings are allowed).
                                    val info = SystemAccess.BACKGROUND_LOCATION.settingsIntent(this@SpecialGrantActivity)
                                    runCatching { startActivity(info) }
                                        .onFailure { note = "This phone has no app settings screen." }
                                },
                                onClose = { finish() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onDestroy() {
        // Only the end of the sheet answers a waiting bridge call; a rotation recreates it and keeps the wait.
        if (isFinishing) intent?.getStringExtra(EXTRA_REQUEST_TOKEN)?.let { SpecialAsks.closed(it) }
        super.onDestroy()
    }

    // PARITY G11: Android's keyboard picker is a dialog, not an activity: the choice is read back
    // when this window gets the focus again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) resumeTick.intValue++
    }

    private suspend fun allow(s: Step.Ask): AllowOutcome {
        val repo = DeftApp.instance.repository
        // Checked before the screen lock so the user is not asked for nothing; grantSpecial checks again.
        val holder = withContext(Dispatchers.IO) {
            s.cap.exclusiveGroup?.let { repo.specialHolder(it) }?.takeIf { it != s.app.id }
                ?.let { repo.getApp(it)?.name ?: "Another app" }
        }
        if (holder != null) return AllowOutcome.Held(holder)
        val choice = GrantPrompt.authenticate(
            this,
            appName = s.app.name,
            title = "Allow ${s.app.name}: ${s.cap.title}",
            message = "Confirm it is you.",
        )
        if (choice != ConfirmChoice.ALLOW) {
            withContext(Dispatchers.IO) { repo.log(s.app.id, "special", s.cap.id, null, "cancelled") }
            return AllowOutcome.Message("Not allowed. Nothing was changed.")
        }
        val result = withContext(Dispatchers.IO) { repo.grantSpecial(s.app.id, s.cap) }
        return when (result) {
            SpecialGrantResult.Granted -> {
                withContext(Dispatchers.IO) { repo.log(s.app.id, "special", s.cap.id, null, "granted") }
                // PARITY G8: a connected listener starts forwarding and shows "in use" now.
                if (s.cap == Capability.NOTIFICATIONS_LISTEN) {
                    app.deft.notifications.DeftNotificationListener.syncUse(this)
                }
                // PARITY G9: a connected accessibility service starts forwarding and shows "in use" now.
                if (s.cap == Capability.ACCESSIBILITY) {
                    app.deft.a11y.DeftAccessibilityService.syncUse(this)
                }
                // PARITY G11: the role's launcher component must be on before Android's role request.
                withContext(Dispatchers.IO) { app.deft.roles.RoleComponents.sync(this@SpecialGrantActivity, repo) }
                val access = SystemAccess.forCapability(s.cap)
                if (access != null && !access.isGranted(this)) {
                    AllowOutcome.NeedsSystem(access, withContext(Dispatchers.IO) { SpecialAccessFlow.beginSystem(this@SpecialGrantActivity, access) })
                } else {
                    AllowOutcome.Done
                }
            }
            is SpecialGrantResult.HeldBy -> {
                withContext(Dispatchers.IO) { repo.log(s.app.id, "special", s.cap.id, null, "refused_held") }
                AllowOutcome.Held(result.name)
            }
            SpecialGrantResult.Refused -> AllowOutcome.Message("This app cannot get this permission.")
        }
    }

    companion object {
        const val EXTRA_WEB_APP_ID = "webAppId"
        const val EXTRA_CAPABILITY = "capability"

        /** Set by a bridge call that waits for the sheet; [SpecialAsks.closed] is called with it when the sheet ends. */
        const val EXTRA_REQUEST_TOKEN = "requestToken"

        /** The app asked for this capability just now: the title says so. */
        const val EXTRA_FROM_APP = "fromApp"

        /** Skips the grant and opens only Android's step (the launcher grant is already held). */
        const val EXTRA_SYSTEM_ONLY = "systemOnly"

        fun intent(
            context: Context,
            webAppId: String,
            capability: Capability,
            requestToken: String? = null,
            fromApp: Boolean = false,
            systemOnly: Boolean = false,
        ): Intent =
            Intent(context, SpecialGrantActivity::class.java)
                .putExtra(EXTRA_WEB_APP_ID, webAppId)
                .putExtra(EXTRA_CAPABILITY, capability.id)
                .putExtra(EXTRA_FROM_APP, fromApp)
                .putExtra(EXTRA_SYSTEM_ONLY, systemOnly)
                .apply { requestToken?.let { putExtra(EXTRA_REQUEST_TOKEN, it) } }
    }
}

@Composable
private fun AskStep(
    s: Step.Ask,
    fromApp: Boolean,
    note: String?,
    busy: Boolean,
    onClose: () -> Unit,
    onDontAsk: () -> Unit,
    onAllow: () -> Unit,
) {
    Text(
        if (fromApp) "${s.app.name} wants to use ${s.cap.title}" else "Special permission for ${s.app.name}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(s.app.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(s.cap.title, style = MaterialTheme.typography.titleSmall)
    Text(s.cap.description, style = MaterialTheme.typography.bodyMedium)
    Text("Why ${s.app.name} says it needs this", style = MaterialTheme.typography.labelLarge)
    Text(s.reason, style = MaterialTheme.typography.bodyMedium)
    Text(s.cap.warning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    when {
        !s.cap.available -> {
            Text("Not available yet. Deft cannot do this yet.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onClose) { Text("Close") }
            }
        }
        s.heldBy != null -> {
            Text(
                "${s.heldBy} has this. Remove it there first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onClose) { Text("Close") }
            }
        }
        else -> {
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Text(
                "Allow asks for your screen lock. You can remove it any time from the app's card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, enabled = !busy) { Text("Not now") }
                // Only for an app's own ask: the dashboard's card is the place to change a decision otherwise.
                if (fromApp) {
                    TextButton(onClick = onDontAsk, enabled = !busy) { Text(stringResource(R.string.consent_dont_ask_again)) }
                }
                Button(onClick = onAllow, enabled = !busy) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun SystemStep(
    s: Step.System,
    fromApp: Boolean,
    note: String?,
    onOpen: () -> Unit,
    onDefaultApps: () -> Unit,
    onAppSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val location = s.access == SystemAccess.BACKGROUND_LOCATION
    val role = s.access.asksInPlace
    val opened = s.stage == SpecialAccessFlow.Stage.OPENED
    Text(
        if (fromApp) "${s.app.name} wants to use ${s.cap.title}" else "One more step",
        style = MaterialTheme.typography.titleMedium,
    )
    if (fromApp) Text("One more step", style = MaterialTheme.typography.titleSmall)
    if (s.stage == SpecialAccessFlow.Stage.RESTRICTED_HINT) {
        // PARITY W7: shown once. Android 13+ locks the switch until "Allow restricted settings" is chosen in app info.
        Text(RESTRICTED_STEPS, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("Later") }
            TextButton(onClick = onOpen) { Text("Accessibility settings") }
            Button(onClick = onAppSettings) { Text("Open app settings") }
        }
        return
    }
    Text(
        if (role) {
            roleStepText(s)
        } else if (s.access == SystemAccess.IME) {
            imeStepText(s)
        } else if (location) {
            "Android asks for this on its own. If it asks about location first, allow it. " +
                "Then choose \"Allow all the time\" for Deft. " +
                "Only ${s.app.name} gets this through Deft; other apps in the launcher do not."
        } else {
            "Android has its own switch for this. Turn on Deft under ${s.access.label}. " +
                "Only ${s.app.name} gets this through Deft; other apps in the launcher do not."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose) { Text(if (opened) "Done" else "Later") }
        if (!opened) {
            Button(onClick = onOpen) { Text(if (location || role) "Continue" else "Open Android settings") }
        } else if (role && note != null) {
            Button(onClick = onDefaultApps) {
                Text(
                    when (s.access) {
                        SystemAccess.DEVICE_ADMIN, SystemAccess.WALLPAPER -> "Try again"
                        SystemAccess.PAYMENT_DEFAULT -> "Open payment settings"
                        else -> "Open default apps"
                    },
                )
            }
        } else if (s.access == SystemAccess.IME && note != null) {
            val on = app.deft.ime.DeftInputMethodService.isEnabled(androidx.compose.ui.platform.LocalContext.current)
            Button(onClick = onDefaultApps) { Text(if (on) "Choose keyboard" else "Open keyboard settings") }
        } else if ((location || s.access == SystemAccess.ACCESSIBILITY) && note != null) {
            // Android stops showing the request after it was refused; Settings still has the switch.
            // PARITY G9: app settings is also where Android 13+ allows restricted settings.
            Button(onClick = onAppSettings) { Text("Open app settings") }
        }
    }
}

/** PARITY W7: the one-time steps for "Allow restricted settings" (Android 13+, apps not from a store). */
private const val RESTRICTED_STEPS = "Android 13 and newer keeps the accessibility switch locked for apps not installed from the " +
    "Play Store, until you allow restricted settings. Three steps:\n" +
    "1. Tap Open app settings.\n" +
    "2. Tap the menu (the three dots, top right) and choose Allow restricted settings.\n" +
    "3. Come back here and tap Accessibility settings. This is shown only once."

/** PARITY G11: the two keyboard steps, what they mean for this app, and how to undo them. */
private fun imeStepText(s: Step.System): String =
    "Two steps in Android: turn on Deft's keyboard in keyboard settings, then choose it as your keyboard. " +
        "Android warns that a keyboard can collect everything you type. With this keyboard that is true for ${s.app.name}, " +
        "except in password fields, where Deft shows its own simple keyboard instead. " +
        "Only ${s.app.name} gets this through Deft; other apps in the launcher do not. " +
        "To undo it, remove the permission from the app's card here: Android then goes back to another keyboard. " +
        "You can also switch keyboards any time with the keyboard button, or in Android Settings under Keyboard."

/** PARITY G11: what Android's role step does for this app, and how to undo it in Android settings. */
private fun roleStepText(s: Step.System): String {
    val what = when (s.access) {
        SystemAccess.ROLE_BROWSER -> "Android asks you to make Deft your default browser. Choose Deft. " +
            "Web links you tap in other apps then go to ${s.app.name}."
        // PARITY F7b: only asked when Deft is not the phone app (that one screens calls already).
        SystemAccess.CALL_SCREENING -> "Android asks you to make Deft your caller ID and spam app. " +
            "Choose Deft. Each incoming call then goes to ${s.app.name} first, which can let it ring, " +
            "ring silently or reject it; with no answer within 4 seconds it rings as usual."
        SystemAccess.ROLE_ASSISTANT -> "Make Deft your digital assistant app. Android may ask you, or you pick it " +
            "under Default apps. Holding the Home button (or the assistant gesture) then opens ${s.app.name}."
        SystemAccess.DEVICE_ADMIN -> return "Android asks you to activate Deft as a device admin app. " +
            "It can only lock the screen: it cannot erase the phone or change your screen lock. " +
            "Only ${s.app.name} can use it through Deft. While it is on, Android will not uninstall Deft. " +
            "To undo it, remove the permission from the app's card here, which turns it off, " +
            "or turn it off in Android Settings under Security, Device admin apps."
        SystemAccess.WALLPAPER -> return "Android shows a preview of Deft's live wallpaper. Set it as your wallpaper " +
            "there. It then shows ${s.app.name}'s wallpaper page, which runs whenever your home screen or lock screen is visible. " +
            "Only ${s.app.name} gets this through Deft; other apps in the launcher do not. " +
            "To undo it, remove the permission from the app's card here: Android then shows its default wallpaper. " +
            "Android does not keep your previous picture, so choose it again in Android Settings under Wallpaper."
        // PARITY B4: Android's own question; undone under Contactless payments, not Default apps.
        SystemAccess.PAYMENT_DEFAULT -> return "Android asks you to make Deft your default payment app. " +
            "Choose Deft. When you then hold the unlocked phone to a card reader while ${s.app.name} is open, " +
            "${s.app.name} answers as your contactless card and may pay with it. " +
            "Only ${s.app.name} gets this through Deft; other apps in the launcher do not. " +
            "To undo it, remove the permission from the app's card here: Android then picks another payment app or none. " +
            "You can also pick another app in Android Settings under Contactless payments."
        else -> "Android asks you to make Deft your ${s.access.label.lowercase()}. Choose Deft."
    }
    return "$what Only ${s.app.name} gets this through Deft; other apps in the launcher do not. " +
        "To undo it, remove the permission from the app's card here, or pick another app in Android Settings under Default apps."
}
