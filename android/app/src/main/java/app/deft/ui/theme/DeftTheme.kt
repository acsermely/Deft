package app.deft.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deft.config.Palette

/**
 * The accent and its on-accent text (8.1). Only the primary button, the tab-bar dot, badges and live signals
 * use these. Everything else in Material takes neutrals from [MaterialTheme.colorScheme].
 */
data class DeftColors(val accent: Color, val onAccent: Color)

private val LocalFlColors = staticCompositionLocalOf { DeftColors(Color(DeftTokens.defaultAccent.toArgb()), Color.White) }

/** Motion specs from the style tokens (3.6). Use as `animateFloatAsState(animationSpec = DeftMotion.snappy())`. */
object DeftMotion {
    fun <T> snappy(): SpringSpec<T> = spring(DeftTokens.springSnappy.dampingRatio, DeftTokens.springSnappy.stiffness)
    fun <T> smooth(): SpringSpec<T> = spring(DeftTokens.springSmooth.dampingRatio, DeftTokens.springSmooth.stiffness)
    fun <T> bouncy(): SpringSpec<T> = spring(DeftTokens.springBouncy.dampingRatio, DeftTokens.springBouncy.stiffness)
}

/**
 * Hybrid style (8.1), one theme for every Compose screen: `DeftTheme(accent, appearance)`.
 * [appearance] is light or dark (auto is resolved by the caller). [typeScale] is the user's shell scale.
 *
 * ColorScheme: primary is the label colour, so stock switches, sliders and text buttons come out neutral.
 * Accent is read with `DeftTheme.colors.accent`.
 */
@Composable
fun DeftTheme(
    accent: Oklch,
    appearance: String,
    typeScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val fitted = remember(accent) { Palette.fitAccent(accent) }
    val accentOklch = fitted.first
    val n = DeftTokens.appearance(appearance)
    val light = appearance == "light"
    val error = Color(Palette.shiftedError(n.error, accentOklch.h).toArgb())
    val label = Color(n.label)
    val label2 = Color(n.label2)
    val bg = Color(n.bg)
    val scheme: ColorScheme = if (light) {
        lightColorScheme(
            primary = label, onPrimary = bg,
            primaryContainer = Color(n.fill2), onPrimaryContainer = label,
            secondary = label2, onSecondary = bg,
            secondaryContainer = Color(n.fill), onSecondaryContainer = label,
            tertiary = label2, onTertiary = bg,
            background = bg, onBackground = label,
            surface = Color(n.bgGrouped), onSurface = label,
            surfaceVariant = Color(n.fill), onSurfaceVariant = label2,
            outline = Color(n.label3), outlineVariant = Color(n.separator),
            error = error, onError = bg,
        )
    } else {
        darkColorScheme(
            primary = label, onPrimary = bg,
            primaryContainer = Color(n.fill2), onPrimaryContainer = label,
            secondary = label2, onSecondary = bg,
            secondaryContainer = Color(n.fill), onSecondaryContainer = label,
            tertiary = label2, onTertiary = bg,
            background = bg, onBackground = label,
            surface = Color(n.bgGrouped), onSurface = label,
            surfaceVariant = Color(n.fill), onSurfaceVariant = label2,
            outline = Color(n.label3), outlineVariant = Color(n.separator),
            error = error, onError = bg,
        )
    }
    val deftColors = DeftColors(accent = Color(accentOklch.toArgb()), onAccent = Color(fitted.second.toArgb()))
    CompositionLocalProvider(LocalFlColors provides deftColors) {
        MaterialTheme(colorScheme = scheme, typography = deftTypography(typeScale), shapes = deftShapes()) {
            content()
        }
    }
}

/** `DeftTheme.colors.accent` and `DeftTheme.colors.onAccent` for the current [DeftTheme] composable. */
object DeftTheme {
    val colors: DeftColors
        @Composable get() = LocalFlColors.current
}

/** Shapes from the radius tokens. Squircle and capsule shapes are a component concern, not Material's. */
fun deftShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(DeftTokens.radiusXs.dp),
    small = RoundedCornerShape(DeftTokens.radiusSm.dp),
    medium = RoundedCornerShape(DeftTokens.radiusMd.dp),
    large = RoundedCornerShape(DeftTokens.radiusLg.dp),
    extraLarge = RoundedCornerShape(DeftTokens.radiusXl.dp),
)

/**
 * Material text styles from the type scale. Display steps map onto the Material roles that fit them best.
 * labelMono has no Material role, so it is applied at call sites with `DeftTypeStyles.labelMono`.
 */
fun deftTypography(scale: Float = 1f): Typography {
    fun style(step: TypeStep, family: FontFamily = DeftFonts.text) = TextStyle(
        fontFamily = family,
        fontSize = (step.sizeSp * scale).sp,
        lineHeight = (step.leadingSp * scale).sp,
        fontWeight = FontWeight(step.weight),
        letterSpacing = step.trackingSp.sp,
    )
    return Typography(
        displayLarge = style(DeftTokens.largeTitle),
        displayMedium = style(DeftTokens.title1),
        displaySmall = style(DeftTokens.title2),
        headlineLarge = style(DeftTokens.title2),
        headlineMedium = style(DeftTokens.title3),
        headlineSmall = style(DeftTokens.headline),
        titleLarge = style(DeftTokens.title2),
        titleMedium = style(DeftTokens.headline),
        titleSmall = style(DeftTokens.subhead),
        bodyLarge = style(DeftTokens.body),
        bodyMedium = style(DeftTokens.subhead),
        bodySmall = style(DeftTokens.footnote),
        labelLarge = style(DeftTokens.subhead),
        labelMedium = style(DeftTokens.caption),
        labelSmall = style(DeftTokens.labelMono, DeftFonts.mono),
    )
}

/** Styles with no Material role: the labelMono micro-label and the Doto numeral. */
object DeftTypeStyles {
    val labelMono: TextStyle
        get() = TextStyle(
            fontFamily = DeftFonts.mono,
            fontSize = DeftTokens.labelMono.sizeSp.sp,
            lineHeight = DeftTokens.labelMono.leadingSp.sp,
            fontWeight = FontWeight(DeftTokens.labelMono.weight),
            letterSpacing = DeftTokens.labelMono.trackingSp.sp,
        )
}
