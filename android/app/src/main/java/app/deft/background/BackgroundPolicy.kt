package app.deft.background

import app.deft.bridge.BridgeArgs
import app.deft.bridge.BulkRows
import app.deft.catalog.Capability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * PARITY D1: what a background run may not do, and which triggers a manifest declares. Pure,
 * unit tested. Checked centrally in `BridgeService.invoke` for every call from a run.
 */
object BackgroundPolicy {
    /** Trigger name of the dashboard's "Run now"; allowed whatever the manifest declares. */
    const val MANUAL = "manual"

    /**
     * Methods that open a screen, a picker or a system prompt (besides confirm-each and
     * [BridgeArgs.ALWAYS_CONFIRM]). Keystore use can ask for a fingerprint, so it counts too.
     */
    val UI_METHODS: Set<String> = setOf(
        "files.pickTree", "files.pickMedia", "files.saveAs", "files.share", "files.openWith",
        "intents.open", "intents.share", "intents.start", "intents.startForResult",
        "nfc.read", "usb.requestPermission", "bluetooth.bleEnable", "bluetooth.bleCreateBond",
        "tiles.request", "dnd.requestAccess", "wifi.openPanel", "apps.launch", "auth.start",
        "keystore.sign", "keystore.encrypt", "keystore.decrypt",
        // PARITY D3: posts an ongoing notification, and Android 14 starts location and microphone
        // services only while the launcher is in front. fgs.update and fgs.stop stay allowed.
        "fgs.start",
        // PARITY G8: sends another app's tap action, which usually opens a screen.
        "notifications.open",
        // PARITY G9: no a11y.* method is listed. They act on whatever app is in front, which is
        // never the holder's hidden run page, so a run may read the screen, tap and press
        // back/home/recents; Android's and the launcher's grant screens are refused anyway.
        // PARITY G11: admin.lockNow is not listed either: locking on a trigger is its point. It is
        // limited to one lock every 30 seconds instead.
    )

    /** List calls that ask the user first above [BulkRows.CONFIRM_ABOVE] rows, with their default limit. */
    private val BULK_DEFAULTS: Map<String, Int> = mapOf(
        "sms.listThreads" to 100,
        "sms.listMessages" to BulkRows.DEFAULT_LIMIT,
        "telephony.listCalls" to BulkRows.DEFAULT_LIMIT,
    )

    /** True when [method] would show the user something; a run gets `needs_foreground` instead. */
    fun needsForeground(method: String, args: JsonObject, capability: Capability): Boolean {
        if (capability.confirmEach || method in BridgeArgs.ALWAYS_CONFIRM || method in UI_METHODS) return true
        val default = BULK_DEFAULTS[method] ?: return false
        val raw = (args["limit"] as? JsonPrimitive)?.intOrNull
        return BulkRows.needsConfirm(BulkRows.limit(raw, default))
    }

    /**
     * W5: an agent tool run happens while the agent screen is in front, so a confirm-each action (and the always-confirm
     * methods) may show its small dialog. Anything else that opens a screen, a picker or a system prompt, and bulk
     * reads that would ask, is still `needs_foreground`: the agent must not drive full screens.
     */
    fun needsForegroundForTool(method: String, args: JsonObject): Boolean {
        if (method in UI_METHODS) return true
        val default = BULK_DEFAULTS[method] ?: return false
        val raw = (args["limit"] as? JsonPrimitive)?.intOrNull
        return BulkRows.needsConfirm(BulkRows.limit(raw, default))
    }

    /** Whether [triggers] (manifest entries) cover the trigger [name]; `periodic` matches `periodic:N`. */
    fun declares(triggers: List<String>, name: String): Boolean = when (name) {
        RunnerQueue.PERIODIC -> triggers.any { it.startsWith("${RunnerQueue.PERIODIC}:") }
        else -> ':' !in name && name in triggers
    }

    /** The minutes of the `periodic:N` entry, or null (for step D2's scheduler). */
    fun periodicMinutes(triggers: List<String>): Int? =
        triggers.firstOrNull { it.startsWith("${RunnerQueue.PERIODIC}:") }
            ?.substringAfter(':')?.toIntOrNull()
}
