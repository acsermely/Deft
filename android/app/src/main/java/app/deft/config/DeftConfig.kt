package app.deft.config

import androidx.compose.ui.graphics.Color
import app.deft.agent.AgentModelChoices
import app.deft.workspace.DeftPaths

data class ColorTokens(
    val mode: String,
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val red: Color,
)

data class ShellConfig(
    val dockRadius: Float,
    val dockAlpha: Float,
    val searchCenter: Boolean,
    val wallpaper: Color,
    val typeScale: Float,
    val recentsCount: Int,
)

data class HomeConfig(
    val searchOpenDp: Float,
    val searchSnap: Float,
    val agentOpenDp: Float,
    val agentSnap: Float,
)

data class AgentConfig(
    val model: String,
    val maxTokens: Int,
    val contextWindow: Int,
    /** "Let the agent read the web": web.fetch and the web reads are off until the user says yes. */
    val webRead: Boolean = false,
    /** Fast: OpenRouter routes each request to the provider with the highest throughput. */
    val fast: Boolean = true,
    /** The reasoning effort sent with every request, one of [AgentModelChoices.EFFORTS]. */
    val effort: String = AgentModelChoices.DEFAULT_EFFORT,
) {
    /** agent.toml with every key, for writers that change one setting and keep the rest. */
    fun toToml(): String = Toml.encode(
        listOf(
            "model" to model,
            "fast" to fast,
            "effort" to effort,
            "max_tokens" to maxTokens,
            "context_window" to contextWindow,
            "web_read" to webRead,
        ),
    )
}

data class DeftConfig(
    val colors: ColorTokens,
    val shell: ShellConfig,
    val home: HomeConfig,
    val agent: AgentConfig,
    val theme: ThemeSettings = ThemeSettings(),
    val limits: DeftPaths.Limits = DeftPaths.defaultLimits(),
    /** True on low tier or with Reduce transparency: glass surfaces use the opaque fill (8.4). */
    val opaqueMaterial: Boolean = false,
)

fun parseColor(hex: String, fallback: Color): Color {
    var raw = hex.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (raw.length == 3) {
        raw = raw.map { "$it$it" }.joinToString("")
    }
    val value = when (raw.length) {
        6 -> raw.toLongOrNull(16)?.or(0xFF000000L)
        8 -> raw.toLongOrNull(16)
        else -> null
    } ?: return fallback
    return Color(value.toInt())
}

fun parseShell(map: Map<String, String>): ShellConfig = ShellConfig(
    dockRadius = Toml.float(map, "dock_radius", 28f),
    dockAlpha = Toml.float(map, "dock_alpha", 0.35f).coerceIn(0f, 1f),
    searchCenter = Toml.bool(map, "search_center", true),
    wallpaper = parseColor(Toml.str(map, "wallpaper", "#0a0a14"), Color(0xFF0A0A14)),
    typeScale = Toml.float(map, "type_scale", 1f).coerceIn(0.8f, 1.4f),
    recentsCount = Toml.int(map, "recents_count", 4).coerceIn(1, 8),
)

fun parseHome(map: Map<String, String>): HomeConfig {
    val searchOpenDp = Toml.float(map, "search_open_dp", 240f)
    val searchSnap = Toml.float(map, "search_snap", 0.42f).coerceIn(0.2f, 0.8f)
    return HomeConfig(
        searchOpenDp = searchOpenDp,
        searchSnap = searchSnap,
        agentOpenDp = Toml.float(map, "agent_open_dp", searchOpenDp),
        agentSnap = Toml.float(map, "agent_snap", searchSnap).coerceIn(0.2f, 0.8f),
    )
}

fun parseLimits(map: Map<String, String>): DeftPaths.Limits = DeftPaths.clampLimits(
    text = Toml.int(map, "max_text_bytes", DeftPaths.MAX_TEXT_BYTES.toInt()).toLong(),
    binary = Toml.int(map, "max_binary_bytes", DeftPaths.MAX_BINARY_BYTES.toInt()).toLong(),
    files = Toml.int(map, "max_files", DeftPaths.MAX_FILES),
    pkg = Toml.int(map, "max_package_bytes", DeftPaths.HARD_PACKAGE_BYTES.toInt()).toLong(),
)

/**
 * Reads agent.toml. The agent talks only to OpenRouter, so an `api_base` left by an older version is ignored.
 * A `:nitro` suffix from an older version becomes the plain id with [AgentConfig.fast] on.
 */
fun parseAgent(map: Map<String, String>): AgentConfig {
    val rawModel = Toml.str(map, "model", AgentModelChoices.DEFAULT_MODEL).trim()
    val nitro = rawModel.endsWith(":nitro")
    val effort = Toml.str(map, "effort", AgentModelChoices.DEFAULT_EFFORT)
    return AgentConfig(
        model = rawModel.removeSuffix(":nitro").ifBlank { AgentModelChoices.DEFAULT_MODEL },
        maxTokens = Toml.int(map, "max_tokens", 16384).coerceIn(256, 32_768),
        contextWindow = Toml.int(map, "context_window", 128_000).coerceIn(8_000, 2_000_000),
        webRead = Toml.bool(map, "web_read", false),
        fast = nitro || Toml.bool(map, "fast", true),
        effort = effort.takeIf { it in AgentModelChoices.EFFORTS }
            ?: AgentModelChoices.DEFAULT_EFFORT,
    )
}
