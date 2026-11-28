package app.deft.bridge

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.deft.DeftApp
import app.deft.R
import app.deft.bridge.modules.A11yModule
import app.deft.bridge.modules.AccountsModule
import app.deft.bridge.modules.AlarmsModule
import app.deft.bridge.modules.LocationModule
import app.deft.bridge.modules.AuthModule
import app.deft.bridge.modules.BackgroundModule
import app.deft.bridge.modules.ForegroundModule
import app.deft.bridge.modules.PushModule
import app.deft.background.BackgroundPolicy
import app.deft.background.BackgroundRunner
import app.deft.background.BackgroundTriggers
import app.deft.background.BackgroundWake
import app.deft.background.SystemTriggers
import app.deft.bridge.modules.AppsModule
import app.deft.bridge.modules.DeviceModule
import app.deft.bridge.modules.UsageModule
import app.deft.bridge.modules.AudioModule
import app.deft.bridge.modules.BluetoothModule
import app.deft.bridge.modules.CalendarModule
import app.deft.bridge.modules.ContactsModule
import app.deft.bridge.modules.FilesModule
import app.deft.bridge.modules.IntentsModule
import app.deft.bridge.modules.KeystoreModule
import app.deft.bridge.modules.MediaModule
import app.deft.bridge.modules.NetModule
import app.deft.bridge.modules.NotificationsModule
import app.deft.bridge.modules.NfcModule
import app.deft.bridge.modules.NotifyModule
import app.deft.bridge.modules.SmsModule
import app.deft.bridge.modules.TelephonyModule
import app.deft.bridge.modules.UsbModule
import app.deft.bridge.modules.ClipboardModule
import app.deft.bridge.modules.DisplayModule
import app.deft.bridge.modules.DndModule
import app.deft.bridge.modules.TilesModule
import app.deft.bridge.modules.PipModule
import app.deft.bridge.modules.SensorsModule
import app.deft.bridge.modules.SpeechModule
import app.deft.bridge.modules.TorchModule
import app.deft.bridge.modules.WidgetsModule
import app.deft.bridge.modules.WifiModule
import app.deft.catalog.BridgeCatalog
import app.deft.catalog.Capability
import app.deft.catalog.GrantLevel
import app.deft.catalog.Risk
import app.deft.special.SystemAccess
import app.deft.catalog.isGranted
import app.deft.data.AppRepository
import app.deft.data.InstalledWebApp
import app.deft.data.canonicalOrigin
import app.deft.ime.ImeSpec
import app.deft.catalog.CapabilityKind
import app.deft.ui.home.HomeActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

interface BridgeEventSink {
    val webAppId: String

    /** A home-widget frame. It never counts as the app being open, never consumes queued events (see [WidgetEvents]). */
    val isWidget: Boolean get() = false

    /** Whether the sink listens for [event] now. Only a widget sink is asked; a page's queue-or-deliver logic is its own. */
    fun listensFor(event: String): Boolean = true

    fun dispatchEvent(event: String, payloadJson: String)
}

class BridgeService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e("BridgeService", "background work failed", t)
        },
    )
    private val sinks = CopyOnWriteArrayList<BridgeEventSink>()
    private val modules = mutableMapOf<String, BridgeModule>()

    lateinit var repository: AppRepository
        private set

    /** Host-scheduled background runs; [BackgroundRunner.trigger] is the entry point. */
    val background: BackgroundRunner by lazy { BackgroundRunner(this) }

    /** `network` and `charging` triggers, watched while the service runs. */
    private val systemTriggers by lazy { SystemTriggers(this) { name, payload -> wakeBackground(name, payload) } }
    private var observingContacts = false

    override fun onCreate() {
        super.onCreate()
        repository = DeftApp.instance.repository
        startAsForeground()
        instance = this
        scope.launch {
            ensureModules()
            maybeObserveContacts()
        }
        // An unplugged USB device closes its connections and tells the app.
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            usbDetached,
            android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        systemTriggers.start()
    }

    private val usbDetached = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                ?: return
            (modules["usb"] as? UsbModule)?.onDetached(device.deviceName)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        scope.launch {
            ensureModules()
            maybeObserveContacts()
            when (intent?.action) {
                ACTION_INCOMING_SMS -> handleIncomingSms(intent)
                ACTION_INCOMING_MMS -> handleIncomingMms(intent)
                ACTION_COMPOSE -> handleCompose(intent)
                ACTION_TELEPHONY_EVENT -> handleTelephonyEvent(intent)
                ACTION_ALARM -> handleAlarm(intent)
                ACTION_BACKGROUND_TRIGGER -> handleBackgroundTrigger(intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        systemTriggers.stop()
        background.shutdown()
        (modules["bluetooth"] as? BluetoothModule)?.shutdown()
        (modules["usb"] as? UsbModule)?.closeAll()
        (modules["nfc"] as? NfcModule)?.closeAll()
        (modules["speech"] as? SpeechModule)?.shutdown()
        (modules["net"] as? NetModule)?.shutdown()
        runCatching { unregisterReceiver(usbDetached) }
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    fun releaseBluetooth(webAppId: String) {
        val bt = modules["bluetooth"] as? BluetoothModule ?: return
        bt.releaseIfOwner(webAppId)
    }

    fun bluetoothRadioOwner(): Pair<String, String>? {
        val bt = modules["bluetooth"] as? BluetoothModule ?: return null
        return bt.radioOwner()
    }

    private fun ensureModules() {
        synchronized(modules) {
            if (modules.isNotEmpty()) return
            modules["contacts"] = ContactsModule(this)
            modules["calendar"] = CalendarModule(this)
            modules["telephony"] = TelephonyModule(this)
            modules["files"] = FilesModule(this)
            modules["sms"] = SmsModule(this)
            modules["intents"] = IntentsModule(this)
            modules["notify"] = NotifyModule(this)
            modules["alarms"] = AlarmsModule(this)
            modules["nfc"] = NfcModule(this)
            modules["bluetooth"] = BluetoothModule(this)
            modules["usb"] = UsbModule(this)
            modules["clipboard"] = ClipboardModule(this)
            modules["display"] = DisplayModule()
            modules["tiles"] = TilesModule(this)
            modules["torch"] = TorchModule(this)
            modules["pip"] = PipModule()
            modules["sensors"] = SensorsModule(this)
            modules["system"] = app.deft.bridge.modules.SystemStatsModule(this)
            modules["speech"] = SpeechModule(this)
            modules["audio"] = AudioModule(this)
            modules["dnd"] = DndModule(this)
            modules["keystore"] = KeystoreModule(this)
            modules["media"] = MediaModule(this)
            modules["widgets"] = WidgetsModule(this)
            modules["net"] = NetModule(this)
            modules["wifi"] = WifiModule(this)
            modules["apps"] = AppsModule(this)
            modules["usage"] = UsageModule(this)
            modules["device"] = DeviceModule(this)
            modules["auth"] = AuthModule(this)
            modules["passkeys"] = app.deft.bridge.modules.PasskeysModule(this)
            modules["background"] = BackgroundModule(this)
            modules["fgs"] = ForegroundModule(this)
            modules["push"] = PushModule(this)
            modules["location"] = LocationModule(this)
            modules["accounts"] = AccountsModule(this)
            modules["notifications"] = NotificationsModule(this)
            modules["a11y"] = A11yModule(this)
            modules["admin"] = app.deft.bridge.modules.AdminModule(this)
            modules["ime"] = app.deft.bridge.modules.ImeModule()
            modules["calls"] = app.deft.bridge.modules.CallsModule()
        }
    }

    private fun maybeObserveContacts() {
        if (observingContacts) return
        observingContacts = true
        ContactsModule.observe(this, scope) { emitContactsChanged() }
        // observe() no-ops without READ_CONTACTS; allow a later start to retry
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            observingContacts = false
        }
    }

    /**
     * True when the app has a page bridge connected right now (its WebView is open). A home-widget frame does not
     * count: events, pushes, background runs and tile clicks must reach the app itself, not a frame that drops them.
     */
    fun isLive(webAppId: String): Boolean = sinks.any { it.webAppId == webAppId && !it.isWidget }

    /** True when the app has any bridge, page or widget frame: its sockets and discovery may run. */
    fun hasSink(webAppId: String): Boolean = sinks.any { it.webAppId == webAppId }

    fun registerSink(sink: BridgeEventSink) {
        if (!sinks.contains(sink)) sinks.add(sink)
        // A widget frame never takes the app's queued events: they wait for the app's own page.
        if (!sink.isWidget) scope.launch { flushPending(sink) }
    }

    fun unregisterSink(sink: BridgeEventSink) {
        sinks.remove(sink)
        // Sockets and discovery stop as soon as the app has no open bridge.
        if (!hasSink(sink.webAppId)) releaseNetwork(sink.webAppId)
    }

    /** Ends the app's background run now and drops its waiting triggers. */
    fun cancelBackground(webAppId: String) {
        background.cancel(webAppId)
    }

    /**
     * Hands trigger [name] to the runner for every app that declares it and holds
     * `background` ([only]: just that app, e.g. the SMS holder). Extra to the UI event, never
     * instead of it.
     */
    fun wakeBackground(name: String, payloadJson: String, only: String? = null) {
        scope.launch {
            for (id in BackgroundWake.targets(repository, name, only)) background.trigger(id, name, payloadJson)
        }
    }

    /** Closes the app's sockets and stops its discovery (also on revoke and uninstall). */
    fun releaseNetwork(webAppId: String) {
        (modules["net"] as? NetModule)?.releaseApp(webAppId)
    }

    /** A home-widget frame calls with `background = true` and [RunContext.WIDGET]: the app's grants, `needs_foreground` rules, no run grant. */
    private fun isWidgetContext(context: RunContext) = context == RunContext.WIDGET

    /**
     * An agent-tool run ([RunContext.TOOL], the agent screen is in front). Like a widget frame it needs no run grant and
     * never asks for access, but confirm-each actions still show their dialog (see [BackgroundPolicy.needsForegroundForTool]).
     */
    private fun isToolContext(context: RunContext) = context == RunContext.TOOL

    private fun sourceOf(background: Boolean, runCapability: Capability, context: RunContext): String = when {
        !background -> "bridge"
        context == RunContext.WIDGET -> "widget"
        context == RunContext.TOOL -> "agent_tool"
        runCapability == Capability.BACKGROUND -> "background"
        else -> runCapability.id
    }

    suspend fun invoke(
        app: InstalledWebApp,
        method: String,
        args: JsonObject,
        webViewOrigin: String?,
        background: Boolean = false,
        /** The grant a [background] page needs on every call (`wallpaper` for the live wallpaper, `ime` for the keyboard page). */
        runCapability: Capability = Capability.BACKGROUND,
        /** Widget frame or agent tool run; never decided by a page and never a capability (see [RunContext]). */
        context: RunContext = RunContext.RUN,
    ): BridgeOutcome {
        if (webViewOrigin == null || canonicalOrigin(webViewOrigin) != canonicalOrigin(app.origin)) {
            repository.log(app.id, "bridge", method.substringBefore('.'), method, BridgeErrors.ORIGIN_MISMATCH)
            return BridgeOutcome.Err(BridgeErrors.ORIGIN_MISMATCH, "Bridge is locked to ${app.origin}")
        }
        if (method == NetFetchSpec.METHOD) return invokeFetch(app, args, background, runCapability, context)
        val prefix = method.substringBefore('.')
        val capability = BridgeCatalog.capabilityFor(method)
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Unknown method $method").also {
                repository.log(app.id, "bridge", prefix, method, BridgeErrors.UNSUPPORTED)
            }
        if (capability.kind != CapabilityKind.BRIDGE) {
            repository.log(app.id, "bridge", capability.id, method, BridgeErrors.UNSUPPORTED)
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Use the standard web API for ${capability.id}")
        }
        // Ime.* only from the keyboard page the launcher's keyboard shows, never from the
        // app's own pages, its background runs or its wallpaper page.
        if (!ImeSpec.methodAllowed(method, background && runCapability == Capability.IME)) {
            repository.log(app.id, "bridge", capability.id, method, BridgeErrors.UNSUPPORTED)
            return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "Only the app's keyboard page can do this")
        }
        val invalid = BridgeArgs.validate(method, args)
        if (invalid != null) {
            repository.log(app.id, "bridge", capability.id, method, BridgeErrors.INVALID_ARGS)
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, invalid)
        }
        // Review: a grant row only counts while the app's current manifest declares the capability
        // (read fresh: the app object a live WebView holds was captured when it opened).
        val declared = repository.declaredCapabilities(app.id)
        if (capability !in declared) {
            repository.log(app.id, "bridge", capability.id, method, BridgeErrors.NOT_GRANTED)
            logGrantRefusal(app.id, capability.id, BridgeErrors.NOT_GRANTED)
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${capability.id} is not declared")
        }
        var grant = repository.grantLevel(app.id, capability)
        // A capability the app declares and the user has not turned off opens the launcher's point-of-use
        // ask right here (special: the sheet and its screen lock; plain: the consent screen for this capability).
        // The call goes on only once the grant is held.
        if (grant == GrantLevel.DENIED) {
            when (val ask = SpecialAsk.ensure(
                this, repository, app.id, capability, background, needSystem = false,
                code = BridgeErrors.NOT_GRANTED, message = "${capability.id} is revoked",
            )) {
                is SpecialAsk.Outcome.Refused -> {
                    repository.log(app.id, "bridge", capability.id, method, ask.err.code)
                    logGrantRefusal(app.id, capability.id, ask.err.code)
                    return ask.err
                }
                else -> Unit
            }
            grant = repository.grantLevel(app.id, capability)
        }
        if (grant == GrantLevel.DENIED) {
            repository.log(app.id, "bridge", capability.id, method, BridgeErrors.NOT_GRANTED)
            logGrantRefusal(app.id, capability.id, BridgeErrors.NOT_GRANTED)
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${capability.id} is revoked")
        }
        // A background run needs the background grant on every call (kill switch), and
        // gets needs_foreground for anything that would show a screen or ask the user.
        // The live wallpaper's page follows the same rules, with `wallpaper` as its kill switch.
        val source = sourceOf(background, runCapability, context)
        if (background) {
            if (!isWidgetContext(context) && !isToolContext(context) && (runCapability !in declared || !repository.grantLevel(app.id, runCapability).isGranted())) {
                repository.log(app.id, source, capability.id, method, BridgeErrors.NOT_GRANTED)
                logGrantRefusal(app.id, runCapability.id, BridgeErrors.NOT_GRANTED)
                return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${runCapability.id} is revoked")
            }
            val refused = if (isToolContext(context)) {
                BackgroundPolicy.needsForegroundForTool(method, args)
            } else {
                BackgroundPolicy.needsForeground(method, args, capability)
            }
            if (refused) {
                repository.log(app.id, source, capability.id, method, BridgeErrors.NEEDS_FOREGROUND)
                return BridgeOutcome.Err(BridgeErrors.NEEDS_FOREGROUND, "Open the app to do this")
            }
        }
        // A send or call the user just tapped for goes straight out. Anything else (timers,
        // radio events, wakeups, the end of a scroll) is confirmed, as is everything when the
        // app asks for biometrics. The biometric flag is read fresh: the app object a live
        // WebView holds was captured when it opened.
        val always = method in BridgeArgs.ALWAYS_CONFIRM
        val confirm = capability.confirmEach || always
        val biometric = confirm &&
            (repository.getApp(app.id)?.confirmBiometric ?: app.confirmBiometric)
        // An agent tool run has no tap behind it: it always asks, whatever gesture the app's own page just had.
        if (confirm && (always || biometric || isToolContext(context) || !UserGesture.consume(app.id))) {
            val choice = GrantPrompt.ask(
                this,
                app,
                title = capability.title,
                message = BridgeArgs.confirmSummary(method, args),
                biometric = biometric,
            )
            if (choice != ConfirmChoice.ALLOW) {
                repository.log(app.id, "bridge", capability.id, method, BridgeErrors.CANCELLED)
                return BridgeOutcome.Err(BridgeErrors.CANCELLED, "User denied this action")
            }
        }
        ensureModules()
        val module = modules[prefix]
            ?: return BridgeOutcome.Err(BridgeErrors.UNSUPPORTED, "No module for $prefix")
        val request = BridgeRequest(app.id, app.origin, method, args, background, runCapability.takeIf { background && context == RunContext.RUN }, context.takeIf { background } ?: RunContext.RUN)
        var result = module.invoke(request)
        // Android's own access behind the capability is off (or Do Not Disturb). The sheet opens on that
        // step only; the call runs once more if the access is on now, and never opens a second sheet.
        if (result is BridgeOutcome.Err && result.code == BridgeErrors.OS_PERMISSION_MISSING && SystemAccess.forAsk(capability) != null) {
            // Only a sheet that ran earns a second call; a call already satisfied keeps its own result.
            when (val ask = SpecialAsk.ensure(
                this, repository, app.id, capability, background, needSystem = true,
                code = BridgeErrors.OS_PERMISSION_MISSING, message = result.message,
            )) {
                SpecialAsk.Outcome.SheetRan -> result = module.invoke(request)
                is SpecialAsk.Outcome.Refused -> result = ask.err
                SpecialAsk.Outcome.Satisfied -> Unit
            }
        }
        (result as? BridgeOutcome.Err)?.takeIf { it.code == BridgeErrors.NOT_GRANTED || it.code == BridgeErrors.OS_PERMISSION_MISSING }
            ?.let { logGrantRefusal(app.id, capability.id, it.code) }
        val outcome = when (result) {
            is BridgeOutcome.Ok -> "ok"
            is BridgeOutcome.Err -> result.code
        }
        // Typing is one call per key; successful key calls are logged at most once a
        // minute per app, so the audit log does not become a record of when each key was pressed.
        if (outcome == "ok" && method in ImeSpec.TYPING_METHODS) {
            val now = System.currentTimeMillis()
            if (!ImeSpec.shouldLogTyping(imeTypingLogged[app.id], now)) return result
            imeTypingLogged[app.id] = now
        }
        repository.log(app.id, source, capability.id, method, outcome, (result as? BridgeOutcome.Ok)?.metadata)
        return result
    }

    private val netFetch by lazy { app.deft.bridge.modules.NetFetchModule(this) }

    /**
     * `net.fetch` has no grant of its own: the origin policy (manifest and user servers, or
     * `network.open`) is the gate, checked inside [NetFetchModule]. A background run still needs
     * its run grant. Every call is audited with host, status and size only.
     */
    private suspend fun invokeFetch(
        app: InstalledWebApp,
        args: JsonObject,
        background: Boolean,
        runCapability: Capability,
        context: RunContext,
    ): BridgeOutcome {
        val method = NetFetchSpec.METHOD
        val source = sourceOf(background, runCapability, context)
        val declared = repository.declaredCapabilities(app.id)
        val open = Capability.NETWORK_OPEN in declared && repository.grantLevel(app.id, Capability.NETWORK_OPEN).isGranted()
        val capId = (if (open) Capability.NETWORK_OPEN else Capability.NETWORK_USER).id
        if (background && !isWidgetContext(context) && !isToolContext(context) && (runCapability !in declared || !repository.grantLevel(app.id, runCapability).isGranted())) {
            repository.log(app.id, source, capId, method, BridgeErrors.NOT_GRANTED)
            return BridgeOutcome.Err(BridgeErrors.NOT_GRANTED, "${runCapability.id} is revoked")
        }
        BridgeArgs.validate(method, args)?.let {
            repository.log(app.id, source, capId, method, BridgeErrors.INVALID_ARGS)
            return BridgeOutcome.Err(BridgeErrors.INVALID_ARGS, it)
        }
        val result = netFetch.invoke(BridgeRequest(app.id, app.origin, method, args, background, runCapability.takeIf { background && context == RunContext.RUN }, context.takeIf { background } ?: RunContext.RUN))
        val outcome = when (result) {
            is BridgeOutcome.Ok -> "ok"
            is BridgeOutcome.Err -> result.code
        }
        repository.log(app.id, source, capId, method, outcome, (result as? BridgeOutcome.Ok)?.metadata)
        return result
    }

    /** When a typing call of each app was last written to the audit log. */
    private val imeTypingLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()


    suspend fun emit(webAppId: String, event: String, payloadJson: String) {
        val pages = sinks.filter { it.webAppId == webAppId && !it.isWidget }
        val widgets = widgetTargets(webAppId, event)
        if (pages.isEmpty() && widgets.isEmpty()) {
            // Not delivered to anyone (a widget frame that does not listen does not count): the app's page gets it later.
            repository.enqueueEvent(webAppId, event, payloadJson)
        } else {
            withContext(Dispatchers.Main) {
                (pages + widgets).forEach { it.dispatchEvent(event, payloadJson) }
            }
        }
    }

    /**
     * Like [emit], but only to an open page of [webAppId]; never queued (for content
     * the launcher must not store, such as another app's screen). False when no page is open.
     */
    suspend fun emitLive(webAppId: String, event: String, payloadJson: String): Boolean {
        val live = sinks.filter { it.webAppId == webAppId && !it.isWidget } + widgetTargets(webAppId, event)
        if (live.isEmpty()) return false
        withContext(Dispatchers.Main) {
            live.forEach { it.dispatchEvent(event, payloadJson) }
        }
        return true
    }

    /** The widget frames of [webAppId] that get a live copy of [event]: a passive event, subscribed to, still granted. */
    private suspend fun widgetTargets(webAppId: String, event: String): List<BridgeEventSink> {
        val frames = sinks.filter { it.webAppId == webAppId && it.isWidget && it.listensFor(event) }
        if (frames.isEmpty() || !WidgetEvents.allowed(event)) return emptyList()
        val granted = repository.grantedCapabilities(webAppId).toSet()
        return if (BridgeCatalog.eventAllowed(event, granted)) frames else emptyList()
    }

    private suspend fun flushPending(sink: BridgeEventSink) {
        // Review: a row queued before a revoke, a holder change or a manifest change is dropped
        // unless the event's capability is still granted and declared.
        val allowed = repository.grantedCapabilities(sink.webAppId).toSet()
        val rows = repository.takePendingEvents(sink.webAppId)
            .filter { BridgeCatalog.eventAllowed(it.event, allowed) }
        withContext(Dispatchers.Main) {
            for (row in rows) sink.dispatchEvent(row.event, row.payloadJson)
        }
    }

    private suspend fun emitContactsChanged() {
        val apps = repository.allApps()
        for (app in apps) {
            if (repository.grantLevel(app.id, Capability.CONTACTS_READ).isGranted()) {
                emit(app.id, "contacts.changed", "{}")
            }
        }
    }

    private suspend fun handleIncomingSms(intent: Intent) {
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val holder = repository.smsHolderId()
        repository.log(
            webAppId = holder,
            source = "inbound",
            capability = Capability.SMS_READ.id,
            method = "sms.received",
            outcome = if (holder == null) "webAppId=none" else "ok",
        )
        val payload = buildJsonObject {
            put("from", JsonPrimitive(from))
            put("body", JsonPrimitive(body))
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }.toString()
        if (holder != null) {
            emit(holder, "sms.received", payload)
            SmsNotifications.notify(this, holder, from)
            wakeBackground(BackgroundTriggers.SMS, payload, only = holder)
        }
    }

    private suspend fun handleIncomingMms(intent: Intent) {
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        val subject = intent.getStringExtra(EXTRA_SUBJECT).orEmpty()
        val holder = repository.smsHolderId()
        repository.log(
            webAppId = holder,
            source = "inbound",
            capability = Capability.SMS_READ.id,
            method = "sms.mmsReceived",
            outcome = if (holder == null) "webAppId=none" else "ok",
        )
        val payload = buildJsonObject {
            put("from", JsonPrimitive(from))
            put("subject", JsonPrimitive(subject))
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }.toString()
        if (holder != null) {
            emit(holder, "sms.mmsReceived", payload)
            SmsNotifications.notifyMms(this, holder, from)
            wakeBackground(BackgroundTriggers.SMS, payload, only = holder)
        }
    }

    private suspend fun handleTelephonyEvent(intent: Intent) {
        val event = intent.getStringExtra(EXTRA_TELEPHONY_EVENT).orEmpty()
        val payload = intent.getStringExtra(EXTRA_TELEPHONY_PAYLOAD).orEmpty()
        val holder = repository.telephonyHolderId()
        repository.log(
            webAppId = holder,
            source = "inbound",
            capability = Capability.CALLS_CONTROL.id,
            method = event,
            outcome = if (holder == null) "webAppId=none" else "ok",
        )
        if (holder != null) {
            emit(holder, event, payload.ifBlank { "{}" })
            if (event == "telephony.incoming") {
                val from = runCatching {
                    BridgeJson.parseToJsonElement(payload).jsonObject["handle"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
                OriginNotifications.post(
                    this,
                    holder,
                    "Incoming call",
                    if (from.isBlank()) "Open the phone web app" else "From $from",
                )
                wakeBackground(BackgroundTriggers.CALLS, payload.ifBlank { "{}" }, only = holder)
            }
        }
    }

    private suspend fun handleCompose(intent: Intent) {
        val holder = repository.smsHolderId() ?: return
        val to = intent.getStringExtra(EXTRA_TO).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val payload = buildJsonObject {
            put("to", JsonPrimitive(to))
            put("body", JsonPrimitive(body))
        }.toString()
        emit(holder, "sms.composeRequested", payload)
    }

    private suspend fun handleAlarm(intent: Intent) {
        val webAppId = intent.getStringExtra(EXTRA_ALARM_WEB_APP_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (webAppId.isBlank() || alarmId.isBlank()) return
        val alarm = repository.getAlarm(alarmId) ?: return
        if (alarm.webAppId != webAppId) return
        val payload = buildJsonObject {
            put("id", JsonPrimitive(alarm.id))
            put("title", JsonPrimitive(alarm.title))
            put("body", JsonPrimitive(alarm.body))
            if (alarm.url != null) put("url", JsonPrimitive(alarm.url))
            put("triggerAt", JsonPrimitive(alarm.triggerAt))
        }.toString()
        emit(webAppId, "alarms.fired", payload)
        OriginNotifications.post(this, webAppId, alarm.title, alarm.body, alarm.url)
        wakeBackground(BackgroundTriggers.ALARMS, payload, only = webAppId)
        repository.log(
            webAppId = webAppId,
            source = "inbound",
            capability = Capability.ALARMS.id,
            method = "alarms.fired",
            outcome = "ok",
        )
        repository.deleteAlarm(alarmId)
        app.deft.alarms.AlarmScheduler.cancel(this, alarmId)
    }

    private fun handleBackgroundTrigger(intent: Intent) {
        val webAppId = intent.getStringExtra(EXTRA_BACKGROUND_WEB_APP_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_BACKGROUND_TRIGGER).orEmpty()
        if (webAppId.isBlank() || name.isBlank()) return
        background.trigger(webAppId, name, intent.getStringExtra(EXTRA_BACKGROUND_PAYLOAD) ?: "{}")
    }

    private fun startAsForeground() {
        val channel = NotificationChannelCompat.Builder(FGS_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.fgs_channel))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, FGS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        ServiceCompat.startForeground(
            this,
            FGS_ID,
            notification,
            types,
        )
    }

    companion object {
        @Volatile
        private var instance: BridgeService? = null

        fun instanceOrNull(): BridgeService? = instance

        const val FGS_CHANNEL = "hosting"
        const val FGS_ID = 1
        const val ACTION_INCOMING_SMS = "app.deft.INCOMING_SMS"
        const val ACTION_INCOMING_MMS = "app.deft.INCOMING_MMS"
        const val ACTION_COMPOSE = "app.deft.SMS_COMPOSE"
        const val ACTION_TELEPHONY_EVENT = "app.deft.TELEPHONY_EVENT"
        const val ACTION_ALARM = "app.deft.ALARM"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_BODY = "body"
        const val EXTRA_SUBJECT = "subject"
        const val EXTRA_TELEPHONY_EVENT = "telephonyEvent"
        const val EXTRA_TELEPHONY_PAYLOAD = "telephonyPayload"
        const val EXTRA_ALARM_WEB_APP_ID = "alarmWebAppId"
        const val EXTRA_ALARM_ID = "alarmId"
        const val ACTION_BACKGROUND_TRIGGER = "app.deft.BACKGROUND_TRIGGER"
        const val EXTRA_BACKGROUND_WEB_APP_ID = "backgroundWebAppId"
        const val EXTRA_BACKGROUND_TRIGGER = "backgroundTrigger"
        const val EXTRA_BACKGROUND_PAYLOAD = "backgroundPayload"

        /**
         * Wakes the service (if needed) and hands [name] to [BackgroundRunner.trigger].
         * For callers without a service reference: the dashboard's "Run now", and the receivers that fire triggers.
         *
         * A running service gets the trigger directly. Otherwise the service is
         * started, which Android 12+ allows from the background only in exempt moments (boot,
         * an exact alarm, a notification action, an incoming SMS or call, the home screen on
         * show); when Android refuses, the trigger is dropped with the audit outcome
         * [BackgroundRunner.OUTCOME_SERVICE_BLOCKED].
         *
         * Returns false when Android refused to start the service.
         */
        fun backgroundTrigger(context: Context, webAppId: String, name: String, payloadJson: String = "{}"): Boolean {
            instance?.let {
                it.background.trigger(webAppId, name, payloadJson)
                return true
            }
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_BACKGROUND_TRIGGER
                putExtra(EXTRA_BACKGROUND_WEB_APP_ID, webAppId)
                putExtra(EXTRA_BACKGROUND_TRIGGER, name)
                putExtra(EXTRA_BACKGROUND_PAYLOAD, payloadJson)
            }
            return try {
                context.startForegroundService(i)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
                Log.w("BridgeService", "background trigger $name not started: ${e.javaClass.simpleName}")
                BackgroundRunner.auditBlocked(webAppId, name)
                false
            }
        }

        fun start(context: Context) {
            ContextCompatStart(context)
        }

        fun incomingSms(context: Context, from: String, body: String) {
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_INCOMING_SMS
                putExtra(EXTRA_FROM, from)
                putExtra(EXTRA_BODY, body)
            }
            context.startForegroundService(i)
        }

        fun incomingMms(context: Context, from: String, subject: String) {
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_INCOMING_MMS
                putExtra(EXTRA_FROM, from)
                putExtra(EXTRA_SUBJECT, subject)
            }
            context.startForegroundService(i)
        }

        fun telephonyEvent(context: Context, event: String, payloadJson: String) {
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_TELEPHONY_EVENT
                putExtra(EXTRA_TELEPHONY_EVENT, event)
                putExtra(EXTRA_TELEPHONY_PAYLOAD, payloadJson)
            }
            context.startForegroundService(i)
        }

        fun compose(context: Context, to: String, body: String) {
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_COMPOSE
                putExtra(EXTRA_TO, to)
                putExtra(EXTRA_BODY, body)
            }
            context.startForegroundService(i)
        }

        fun alarmFired(context: Context, webAppId: String, alarmId: String) {
            val i = Intent(context, BridgeService::class.java).apply {
                action = ACTION_ALARM
                putExtra(EXTRA_ALARM_WEB_APP_ID, webAppId)
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            context.startForegroundService(i)
        }
    }
}

private fun ContextCompatStart(context: Context) {
    val i = Intent(context, BridgeService::class.java)
    context.startForegroundService(i)
}

fun jsonArg(obj: JsonObject, key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull

/** A call refused for a grant reason (no grant, or Android's permission missing). Ids and codes only, never arguments. */
private fun logGrantRefusal(appId: String, capability: String, code: String) {
    Log.i("DeftBridge", "refused app=$appId capability=$capability code=$code")
}
