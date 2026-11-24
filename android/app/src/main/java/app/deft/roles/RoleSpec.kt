package app.deft.roles

import app.deft.catalog.Capability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * PARITY G11: the launcher components that stand in for an Android role on behalf of one web app.
 * Each is declared disabled in AndroidManifest.xml and enabled only while some app holds [cap]
 * (an exclusive special grant), so Deft is not offered as a browser and so on for nothing.
 * Keep [className] in sync with the manifest.
 */
enum class RoleComponent(val className: String, val cap: Capability) {
    /** activity-alias of [app.deft.ui.roles.RoleEntryActivity]: VIEW http/https, BROWSABLE. */
    BROWSER_VIEW("app.deft.ui.roles.BrowserView", Capability.ROLE_BROWSER),

    /** The assistant's voice interaction service; what Android binds once the launcher holds ROLE_ASSISTANT. */
    ASSIST_SERVICE("app.deft.assist.DeftVoiceInteractionService", Capability.ROLE_ASSISTANT),

    /** Its session service: a long-press of Home (or the assist gesture) opens the holder app. */
    ASSIST_SESSION("app.deft.assist.DeftAssistSessionService", Capability.ROLE_ASSISTANT),

    /** The recognizer Android requires of an assistant; it hands speech on to another recognizer. */
    ASSIST_RECOGNIZER("app.deft.assist.DeftRecognitionService", Capability.ROLE_ASSISTANT),

    /** activity-alias of [app.deft.ui.roles.RoleEntryActivity]: ACTION_ASSIST, for phones that start an activity. */
    ASSIST_ENTRY("app.deft.ui.roles.AssistEntry", Capability.ROLE_ASSISTANT),

    /** The live wallpaper; while it is disabled Android cannot show it and falls back to its default wallpaper. */
    WALLPAPER_SERVICE("app.deft.wallpaper.DeftWallpaperService", Capability.WALLPAPER),

    /**
     * The keyboard (input method); while it is disabled Android does not list it under keyboards,
     * and disabling it while it is the keyboard makes Android switch to another one.
     */
    IME_SERVICE("app.deft.ime.DeftInputMethodService", Capability.IME),

    /**
     * PARITY F7b: the call screening service. While it is disabled Android does not bind it (the
     * default dialer screens nothing through the launcher) and takes the call screening role back.
     */
    CALL_SCREENING_SERVICE("app.deft.telephony.DeftCallScreeningService", Capability.CALLS_SCREEN),

    /**
     * PARITY B4: the payment-category card emulation service. While it is disabled Android does not
     * offer the launcher as a payment app, and disabling it while it is the default makes Android
     * pick another payment app (or none).
     */
    PAYMENT_HCE_SERVICE("app.deft.nfc.DeftPaymentApduService", Capability.NFC_HCE_PAYMENT),
    ;

    companion object {
        fun forClassName(name: String?): RoleComponent? = entries.firstOrNull { it.className == name }
    }
}

/** Pure rules for the role powers (PARITY G11). No Android types; unit tested. */
object RoleSpec {
    /** Longest web address passed on to the browser holder. */
    const val MAX_URL = 8 * 1024

    /** The components to enable, given which special groups have a holder. */
    fun enabledComponents(heldGroups: Set<String>): Set<RoleComponent> =
        RoleComponent.entries.filter { it.cap.exclusiveGroup in heldGroups }.toSet()

    /**
     * A link for the browser holder: `http` or `https` (any case), something after `//`, at most
     * [MAX_URL] characters, no control characters or spaces. Anything else is not passed on.
     */
    fun validBrowserUrl(url: String?): Boolean {
        if (url.isNullOrEmpty() || url.length > MAX_URL) return false
        if (url.any { it.code <= 0x20 || it.code == 0x7f }) return false
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val rest = url.substring(scheme.length + 1)
        return rest.startsWith("//") && rest.length > 2 && rest[2] != '/'
    }

    /** PARITY G11 `device.admin`: the shortest time between two `admin.lockNow` calls. */
    const val LOCK_MIN_INTERVAL_MS = 30_000L

    /**
     * Whether `admin.lockNow` may lock now, [lastMs] being the last lock (null: none yet). A clock
     * that went backwards allows it, so a time change never blocks locking for long.
     */
    fun lockAllowed(lastMs: Long?, nowMs: Long): Boolean =
        lastMs == null || nowMs < lastMs || nowMs - lastMs >= LOCK_MIN_INTERVAL_MS

    /**
     * `assist.invoked {source}`: no screen content, no voice; only that the assistant was asked for.
     * [ASSIST_SOURCE_GESTURE] comes from Android's voice interaction session, which only the system
     * can start. [ASSIST_SOURCE_INTENT] comes from the exported ACTION_ASSIST entry, which any app
     * can start too, so the app must not treat it as proof the user asked.
     */
    const val ASSIST_EVENT = "assist.invoked"
    const val ASSIST_SOURCE_GESTURE = "assist-gesture"
    const val ASSIST_SOURCE_INTENT = "intent"

    fun assistPayload(source: String): String {
        require(source == ASSIST_SOURCE_GESTURE || source == ASSIST_SOURCE_INTENT)
        return "{\"source\":\"$source\"}"
    }

    /** Recognizers the assistant's own recognizer prefers to hand speech to, best first. */
    val PREFERRED_RECOGNIZERS = listOf(
        "com.google.android.as",
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.samsung.android.bixby.agent",
    )

    /**
     * PARITY G11: Android makes an assistant's recognizer the phone's default speech recognizer, so
     * the launcher's one forwards to another installed recognizer. [candidates] are flattened
     * component names (`package/class`); the launcher's own are never picked. Preferred packages
     * first, then the first by name. Null when there is none.
     */
    fun pickRecognizer(candidates: List<String>, selfPackage: String): String? {
        val others = candidates.filter { it.substringBefore('/') != selfPackage && '/' in it }.sorted()
        for (pkg in PREFERRED_RECOGNIZERS) {
            others.firstOrNull { it.substringBefore('/') == pkg }?.let { return it }
        }
        return others.firstOrNull()
    }

    /** `intents.viewReceived` for a link from the browser role (same event as "Open with", PARITY E1). */
    fun browserPayload(url: String): String = buildJsonObject {
        put("type", JsonPrimitive("url"))
        put("url", JsonPrimitive(url))
        put("items", JsonArray(emptyList()))
    }.toString()
}
