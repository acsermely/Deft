package app.deft.config

import app.deft.ui.theme.DeftTokens
import app.deft.ui.theme.OklchMath

/**
 * The user's theme choice: one accent and one appearance (proposal 3.1 and 8.1).
 * Persisted in config/theme.toml as `accent`, `appearance` and `reduceTransparency`.
 */
data class ThemeSettings(
    val accent: String = DeftTokens.DEFAULT_ACCENT,
    val appearance: String = "auto",
    /** Forces the opaque material (8.4 "Reduce transparency"). Stored as `reduceTransparency`. */
    val reduceTransparency: Boolean = false,
    /** Home-screen icons as Android monochrome (themed) layers, tinted with the label colour. Off by default. Stored as `monochromeIcons`. */
    val monochromeIcons: Boolean = false,
) {
    /**
     * The accent (and, when given, the appearance) changed; every other switch (reduce transparency, monochrome
     * icons) keeps its value. Throws for an accent [normalizeAccent] does not know.
     */
    fun withChoice(accent: String, appearance: String? = null): ThemeSettings = copy(
        accent = normalizeAccent(accent)
            ?: throw IllegalArgumentException("unknown accent: $accent (use a preset, wallpaper, #hex or oklch(...))"),
        appearance = appearance ?: this.appearance,
    )

    companion object {
        const val WALLPAPER = "wallpaper"
        val APPEARANCES = listOf("auto", "light", "dark")

        /** Accent presets: the default Deft ember, the earlier signal red and the nearest accents of the retired terminal themes (8.3). */
        val PRESETS: Map<String, String> = linkedMapOf(
            "ember" to DeftTokens.DEFAULT_ACCENT,
            "red" to "oklch(0.58 0.21 27)",
            "violet" to "#bd93f9",
            "frost" to "#88c0d0",
            "blue" to "#7aa2f7",
            "pink" to "#f472b6",
            "teal" to "#0f6f73",
            "amber" to "#e0a33a",
        )

        /** The retired `black` appearance opens as dark. */
        fun normalizeAppearance(raw: String?): String =
            raw?.trim()?.lowercase()?.let { if (it == "black") "dark" else it }?.takeIf { it in APPEARANCES } ?: "auto"

        /** A preset name, `wallpaper`, or a parseable `#hex` / `oklch(...)` value. Null when none of these. */
        fun normalizeAccent(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            PRESETS[s.lowercase()]?.let { return it }
            if (s.equals(WALLPAPER, ignoreCase = true)) return WALLPAPER
            return if (OklchMath.parse(s) != null) s else null
        }

        fun fromMap(map: Map<String, String>): ThemeSettings = ThemeSettings(
            accent = normalizeAccent(map["accent"]) ?: DeftTokens.DEFAULT_ACCENT,
            appearance = normalizeAppearance(map["appearance"]),
            reduceTransparency = map["reduceTransparency"]?.trim()?.equals("true", ignoreCase = true) == true,
            monochromeIcons = map["monochromeIcons"]?.trim()?.equals("true", ignoreCase = true) == true,
        )
    }
}
