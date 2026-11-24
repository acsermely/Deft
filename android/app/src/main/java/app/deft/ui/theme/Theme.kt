package app.deft.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.deft.DeftApp
import app.deft.R
import app.deft.config.DeftConfig

/**
 * Bundled font families (res/font, subset TTF). The same subsets ship as woff2 for the WebView under
 * /_deft/v1/fonts/. Geist Variable carries the wght axis, so each weight is an explicit variation.
 * Tokens.kt holds the CSS family names; these are the native references to the same files.
 */
@OptIn(ExperimentalTextApi::class)
object DeftFonts {
    private fun weighted(res: Int, vararg weights: Pair<FontWeight, Int>): FontFamily = FontFamily(
        weights.map { (fw, w) ->
            Font(res, fw, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    /** Geist: text and display (tight tracking is set per type step). */
    val text: FontFamily = weighted(
        R.font.geist,
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700,
    )

    /** Geist Mono, weights 400-500 only: micro-labels. */
    val mono: FontFamily = weighted(R.font.geist_mono, FontWeight.Normal to 400, FontWeight.Medium to 500)

    /** Doto: dot numerals (digits, `: . - % ° /` and A-Z only; roundness pinned). */
    val numeric: FontFamily = weighted(R.font.doto, FontWeight.Normal to 400)
}

val LocalDeftConfig = staticCompositionLocalOf { app.deft.config.ConfigStore.defaults() }

@Composable
fun AppDeftTheme(content: @Composable () -> Unit) {
    val config by DeftApp.instance.configStore.config.collectAsState(
        initial = DeftApp.instance.configStore.config.value,
    )
    DeftTheme(config = config, content = content)
}

/**
 * The launcher's own screens. Delegates to [DeftTheme] (section 5): the accent comes from the resolved config,
 * and `auto` appearance follows the system through `colors.mode`.
 */
@Composable
fun DeftTheme(
    config: DeftConfig = LocalDeftConfig.current,
    content: @Composable () -> Unit,
) {
    val appearance = if (config.theme.appearance == "auto") config.colors.mode else config.theme.appearance
    val accent = OklchMath.fromArgb(config.colors.accent.toArgb().toLong() and 0xFFFFFFFFL)
    CompositionLocalProvider(LocalDeftConfig provides config) {
        DeftTheme(accent = accent, appearance = appearance, typeScale = config.shell.typeScale) {
            ApplyDeftWindowChrome(config)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}
