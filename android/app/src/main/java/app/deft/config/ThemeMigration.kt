package app.deft.config

import app.deft.ui.theme.DeftTokens

/**
 * One-time migration from the retired terminal themes (proposal 8.3).
 * Pure: the caller reads the legacy files and writes the result and the warnings.
 */
object ThemeMigration {
    data class Result(val settings: ThemeSettings, val warnings: List<String>)

    private class Legacy(val primaries: Set<String>, val accent: String, val mode: String)

    private val legacy = mapOf(
        "dracula" to Legacy(setOf("#bd93f9"), "#bd93f9", "dark"),
        "nord" to Legacy(setOf("#88c0d0", "#5e81ac"), "#88c0d0", "dark"),
        "synthwave" to Legacy(setOf("#f472b6"), "#f472b6", "dark"),
        "tokyo-night" to Legacy(setOf("#7aa2f7"), "#7aa2f7", "dark"),
        "cupcake" to Legacy(setOf("#0f6f73"), "#0f6f73", "light"),
        "light" to Legacy(setOf("#2e5aac", "#7aa2f7"), DeftTokens.DEFAULT_ACCENT, "light"),
    )

    private val primaryRe = Regex(""""primary"\s*:\s*"(#[0-9a-fA-F]{3,6})"""")

    /**
     * [themeName] is the old theme.name file, [paletteJson] the old palette.json, and
     * [colorsMode] / [colorsAccent] the old colors.toml values. Each may be null when the file was absent.
     */
    fun migrate(themeName: String?, paletteJson: String?, colorsMode: String?, colorsAccent: String?): Result {
        val name = themeName?.trim().orEmpty()
        val warnings = mutableListOf<String>()
        val entry = legacy[name]
        val override = paletteJson?.let { json -> primaryRe.find(json)?.groupValues?.get(1) }
        val appearance = ThemeSettings.normalizeAppearance(colorsMode ?: entry?.mode)
        val accent = when {
            name == ThemeSettings.WALLPAPER || name == "system" -> ThemeSettings.WALLPAPER
            override != null && (entry == null || override.lowercase() !in entry.primaries.map { it.lowercase() }) -> {
                warnings += "theme migration: palette.json primary $override is used as the accent; other palette hues are ignored"
                ThemeSettings.normalizeAccent(override) ?: DeftTokens.DEFAULT_ACCENT
            }
            entry != null -> entry.accent
            else -> ThemeSettings.normalizeAccent(colorsAccent) ?: DeftTokens.DEFAULT_ACCENT
        }
        return Result(ThemeSettings(accent, appearance), warnings)
    }
}
