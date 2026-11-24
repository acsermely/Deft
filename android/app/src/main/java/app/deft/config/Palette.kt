package app.deft.config

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import app.deft.device.DeviceTier
import app.deft.ui.theme.DeftTokens
import app.deft.ui.theme.Neutrals
import app.deft.ui.theme.Oklch
import app.deft.ui.theme.OklchMath
import app.deft.webview.DeftRuntimeTheme
import kotlin.math.abs

/**
 * Accent x appearance (proposal 3.1, 3.2, 8.1). [resolve] turns the user's [ThemeSettings] into an
 * accent with a contrast-checked on-accent colour, and [css] writes theme.css: the accent, the
 * appearance neutrals and status colours, and the compatibility shim for bundled apps (section 6).
 */
object Palette {
    const val MIN_ON_ACCENT_CONTRAST = 4.5
    private const val ERROR_HUE_GAP = 30.0

    /** The resolved theme. [effective] is light or dark, with auto resolved from the system. */
    data class Resolved(
        val settings: ThemeSettings,
        val effective: String,
        val accent: Oklch,
        val onAccent: Oklch,
        val tier: DeviceTier = DeviceTier.MID,
    ) {
        /** Opaque material (8.4): low tier, or the user's Reduce transparency switch. */
        val opaqueMaterial: Boolean get() = tier == DeviceTier.LOW || settings.reduceTransparency
    }

    /**
     * [wallpaper] is the Material You accent (hex) when accent is `wallpaper`; null falls back to the default accent.
     * [systemDark] decides auto.
     */
    fun resolve(settings: ThemeSettings, wallpaper: String?, systemDark: Boolean, tier: DeviceTier = DeviceTier.MID): Resolved {
        val source = if (settings.accent == ThemeSettings.WALLPAPER) {
            wallpaper?.let { OklchMath.parse(it) } ?: DeftTokens.defaultAccent
        } else {
            OklchMath.parse(settings.accent) ?: DeftTokens.defaultAccent
        }
        val (accent, onAccent) = fitAccent(source.copy(alpha = 1.0))
        val effective = when (settings.appearance) {
            "light", "dark" -> settings.appearance
            else -> if (systemDark) "dark" else "light"
        }
        return Resolved(settings, effective, accent, onAccent, tier)
    }

    /**
     * Picks white or black for the text on the accent, then nudges the accent lightness until the
     * pair reaches 4.5:1. Returns the adjusted accent and its on-accent colour.
     */
    fun fitAccent(accent: Oklch): Pair<Oklch, Oklch> {
        var current = accent
        repeat(100) {
            val on = pickOn(current)
            if (OklchMath.contrast(on.toArgb(), current.toArgb()) >= MIN_ON_ACCENT_CONTRAST) return current to on
            val step = if (on.l > 0.5) -0.01 else 0.01  // white text needs a darker accent, black text a lighter one
            current = current.copy(l = (current.l + step).coerceIn(0.0, 1.0))
        }
        return current to pickOn(current)
    }

    private fun pickOn(accent: Oklch): Oklch {
        val a = accent.toArgb()
        return if (OklchMath.contrast(Oklch.WHITE.toArgb(), a) >= OklchMath.contrast(Oklch.BLACK.toArgb(), a)) Oklch.WHITE else Oklch.BLACK
    }

    /**
     * Status red/orange must not read as the accent. If the error hue is within 30 degrees of the
     * accent hue it moves to 30 degrees away on the same side.
     */
    fun shiftedError(error: Oklch, accentHue: Double): Oklch {
        var d = (error.h - accentHue) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        if (abs(d) >= ERROR_HUE_GAP) return error
        val shifted = accentHue + if (d >= 0) ERROR_HUE_GAP else -ERROR_HUE_GAP
        return error.copy(h = ((shifted % 360) + 360) % 360)
    }

    /** The theme.css the launcher publishes. Appearance pairs use light-dark() when appearance is auto. */
    fun css(r: Resolved): String {
        val appearance = r.settings.appearance
        // auto always declares both schemes so light-dark() follows the OS in the WebView between reloads.
        val scheme = when {
            appearance == "auto" -> "light dark"
            r.effective == "light" -> "light"
            else -> "dark"
        }
        fun neutral(pick: (Neutrals) -> Long): String = if (appearance == "auto") {
            "light-dark(${hex(pick(DeftTokens.appearanceLight))}, ${hex(pick(DeftTokens.appearanceDark))})"
        } else {
            hex(pick(DeftTokens.appearance(r.effective)))
        }
        fun status(pick: (Neutrals) -> Oklch): String {
            fun shifted(n: Neutrals) = shiftedError(pick(n), r.accent.h).css()
            return if (appearance == "auto") {
                "light-dark(${shifted(DeftTokens.appearanceLight)}, ${shifted(DeftTokens.appearanceDark)})"
            } else {
                shifted(DeftTokens.appearance(r.effective))
            }
        }
        val lines = listOf(
            "--deft-appearance: $appearance;",
            "--deft-accent: ${r.accent.css()};",
            "--deft-on-accent: ${r.onAccent.css()};",
            "--deft-bg: ${neutral { it.bg }};",
            "--deft-bg-grouped: ${neutral { it.bgGrouped }};",
            "--deft-bg-elevated: ${neutral { it.bgElevated }};",
            "--deft-label: ${neutral { it.label }};",
            "--deft-label-2: ${neutral { it.label2 }};",
            "--deft-label-3: ${neutral { it.label3 }};",
            "--deft-label-4: ${neutral { it.label4 }};",
            "--deft-fill: ${neutral { it.fill }};",
            "--deft-fill-2: ${neutral { it.fill2 }};",
            "--deft-fill-3: ${neutral { it.fill3 }};",
            "--deft-fill-4: ${neutral { it.fill4 }};",
            "--deft-separator: ${neutral { it.separator }};",
            "--deft-error: ${status { it.error }};",
            "--deft-warning: ${status { it.warning }};",
            "--deft-success: ${status { it.success }};",
            "--deft-info: ${neutral { it.info }};",
            "--deft-tier: ${r.tier.cssName};",
        ) + materialLines(r)
        val shim = listOf(
            "--deft-color-base-100: var(--deft-bg);",
            "--deft-color-base-200: var(--deft-bg-grouped);",
            "--deft-color-base-300: var(--deft-bg-elevated);",
            "--deft-color-base-content: var(--deft-label);",
            "--deft-color-primary: var(--deft-accent);",
            "--deft-color-primary-content: var(--deft-on-accent);",
            "--deft-color-secondary: var(--deft-label-2);",
            "--deft-color-secondary-content: var(--deft-bg);",
            "--deft-color-accent: var(--deft-label-2);",
            "--deft-color-accent-content: var(--deft-bg);",
            "--deft-color-neutral: var(--deft-label-2);",
            "--deft-color-neutral-content: var(--deft-bg);",
            "--deft-color-info: var(--deft-info);",
            "--deft-color-info-content: var(--deft-bg);",
            "--deft-color-success: var(--deft-success);",
            "--deft-color-success-content: var(--deft-bg);",
            "--deft-color-warning: var(--deft-warning);",
            "--deft-color-warning-content: var(--deft-bg);",
            "--deft-color-error: var(--deft-error);",
            "--deft-color-error-content: var(--deft-bg);",
            "--deft-fg: var(--deft-label);",
            "--deft-surface: var(--deft-bg-grouped);",
            "--deft-muted: var(--deft-label-2);",
            "--deft-red: var(--deft-error);",
        )
        val body = (lines + listOf(
            "--deft-border: 1px;",
            "--deft-density: 1;",
            "--deft-radius-box: var(--deft-radius-lg);",
            "--deft-radius-field: var(--deft-radius-md);",
            "--deft-radius-selector: 999px;",
        ) + shim).joinToString("\n") { "    $it" }
        return """
            @layer deft.theme {
              :root, [data-theme] {
                color-scheme: $scheme;
            $body
              }
              [data-theme="light"] { color-scheme: light; }
              [data-theme="dark"] { color-scheme: dark; }
            }
        """.trimIndent() + "\n"
    }

    /**
     * Glass material (3.3, 8.4). Mid and high tier blur; low tier or Reduce transparency is opaque at 92%
     * with no blur. Apps never branch on the tier.
     */
    private fun materialLines(r: Resolved): List<String> {
        val alpha = if (r.opaqueMaterial) DeftTokens.barAlphaReduced else DeftTokens.barAlpha
        val pct = Math.round(alpha * 100)
        val blur = if (r.opaqueMaterial) "none" else "blur(${DeftTokens.blurDp.toInt()}px) saturate(${DeftTokens.blurSaturate})"
        return listOf(
            "--deft-material-bar: color-mix(in oklch, var(--deft-bg-elevated) $pct%, transparent);",
            "--deft-material-blur: $blur;",
        )
    }

    /** Compose colours for the resolved theme. Muted text is the label-2 alpha composited over the background. */
    fun colors(r: Resolved): ColorTokens {
        val n = DeftTokens.appearance(r.effective)
        return ColorTokens(
            mode = if (r.effective == "light") "light" else "dark",
            background = Color(n.bg),
            surface = Color(n.bgGrouped),
            foreground = Color(n.label),
            muted = Color(OklchMath.composite(n.label2, n.bg)),
            accent = Color(r.accent.toArgb()),
            red = Color(shiftedError(n.error, r.accent.h).toArgb()),
        )
    }

    fun publish(r: Resolved) {
        DeftRuntimeTheme.sheet = css(r)
    }

    /** The Material You accent from the wallpaper (Android 12+), as hex; null when unavailable. */
    fun wallpaperAccent(context: Context, dark: Boolean): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val id = context.resources.getIdentifier(
            if (dark) "system_accent1_200" else "system_accent1_600",
            "color",
            "android",
        )
        if (id == 0) return null
        return String.format("#%06X", context.getColor(id) and 0xFFFFFF)
    }

    /** #RRGGBBAA (or #RRGGBB when opaque) from an ARGB long. */
    fun hex(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return if (a == 0xFFL) String.format("#%06X", rgb) else String.format("#%06X%02X", rgb, a)
    }
}
