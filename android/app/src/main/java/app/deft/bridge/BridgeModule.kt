package app.deft.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal val BridgeJson = Json { ignoreUnknownKeys = true }

data class BridgeRequest(
    val webAppId: String,
    val origin: String,
    val method: String,
    val args: JsonObject,
    /** The call comes from a background run, not from the app on screen. */
    val background: Boolean = false,
    /**
     * For a [background] call, the grant that page runs under (`background` for a run,
     * `wallpaper`, `ime` for the keyboard page); null for the app on screen.
     */
    val runCapability: app.deft.catalog.Capability? = null,
    /** Set by the host for a background call only; [RunContext.RUN] for the app on screen and for ordinary background runs. */
    val context: RunContext = RunContext.RUN,
)

/**
 * Who a background-flagged call serves, decided by the native host (a bridge is built with it; no page can pass it).
 * It is deliberately not a [app.deft.catalog.Capability]: a widget frame or an agent tool run needs no run
 * grant, and an app that holds any capability gains nothing from this value.
 */
enum class RunContext {
    /** The app on screen, or a background run / wallpaper / keyboard page under the grant in [BridgeRequest.runCapability]. */
    RUN,

    /** One `<deft-widget>` frame of the home canvas. */
    WIDGET,

    /** An agent tool run (the agent screen is in front, confirm-each actions still ask). */
    TOOL,
}

sealed class BridgeOutcome {
    /** [metadata] is an optional short JSON text kept in the audit log (for example a row count). Never user content. */
    data class Ok(val value: JsonElement = JsonNull, val metadata: String? = null) : BridgeOutcome()
    data class Err(val code: String, val message: String) : BridgeOutcome()
}

fun interface BridgeModule {
    suspend fun invoke(request: BridgeRequest): BridgeOutcome
}

object BridgeErrors {
    const val NOT_GRANTED = "not_granted"
    const val OS_PERMISSION_MISSING = "os_permission_missing"
    const val ROLE_MISSING = "role_missing"
    const val INVALID_ARGS = "invalid_args"
    const val ORIGIN_MISMATCH = "origin_mismatch"
    const val UNSUPPORTED = "unsupported"
    const val CANCELLED = "cancelled"
    const val BRIDGE_NOT_READY = "bridge_not_ready"
    const val IN_USE = "in_use"

    /** `net.fetch` and `system.stats`: the app called too often; try again shortly. */
    const val RATE_LIMITED = "rate_limited"

    /** `net.fetch`: the request itself failed (no connection, TLS error, timeout, response over the cap). */
    const val NETWORK_ERROR = "network_error"

    /** A background run asked for something that shows the user a screen or a prompt. */
    const val NEEDS_FOREGROUND = "needs_foreground"

    /**
     * Message for [UNSUPPORTED] when a method needs the app on screen ([HostSurfaces]): open, or shown in the agent's
     * Preview. `apps.verify` has none, so the text says what counts: otherwise it reads as "no such sensor".
     */
    const val NOT_ON_SCREEN = "The app is not on screen (open it, or show it in the agent's Preview)"

    /** Message for [UNSUPPORTED] when a window-wide control is called from the agent's Preview ([HostSurface.isPreview]). */
    const val NOT_IN_PREVIEW = "Not available in the agent's Preview: open the app to use this"
}
