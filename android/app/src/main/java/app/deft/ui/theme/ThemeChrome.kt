package app.deft.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.deft.config.ColorTokens
import app.deft.config.DeftConfig

fun Color.toCssHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

fun Activity.applyDeftChrome(config: DeftConfig) {
    val bg = config.colors.background.toArgb()
    window.statusBarColor = bg
    window.navigationBarColor = bg
    window.setBackgroundDrawable(ColorDrawable(bg))
    val light = config.colors.mode.equals("light", ignoreCase = true)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = light
        isAppearanceLightNavigationBars = light
    }
}

fun WebView.applyDeftTheme(tokens: ColorTokens) {
    setBackgroundColor(tokens.background.toArgb())
    evaluateJavascript(themeCssJs(tokens), null)
}

/**
 * Reloads /_deft/v1/theme.css (and /_fl/v1/theme.css in apps made before the rename) and sets the theme-color meta. It does not write the stylesheet:
 * `Palette.publish` (config load) is the single writer of `DeftRuntimeTheme.sheet`.
 */
fun themeCssJs(tokens: ColorTokens): String {
    // bg is always "#RRGGBB" from toCssHex, so a single-quoted literal is safe.
    val bg = tokens.background.toCssHex()
    val bgLiteral = "'$bg'"
    return """
        (function() {
          var bg = $bgLiteral;
          var r = Date.now();
          var href = '/_deft/v1/theme.css?r=' + r;
          document.querySelectorAll('link[href*="/_fl/v1/theme.css"]').forEach(function(l) { l.href = '/_fl/v1/theme.css?r=' + r; });
          var el = document.getElementById('deft-theme');
          if (!el) {
            el = document.createElement('link');
            el.id = 'deft-theme';
            el.rel = 'stylesheet';
            (document.head || document.documentElement).appendChild(el);
          }
          el.href = href;
          try { window.dispatchEvent(new Event('deft:theme')); window.dispatchEvent(new Event('fl:theme')); } catch (e) {}
          var meta = document.querySelector('meta[name="theme-color"]');
          if (!meta) {
            meta = document.createElement('meta');
            meta.setAttribute('name', 'theme-color');
            (document.head || document.documentElement).appendChild(meta);
          }
          meta.setAttribute('content', bg);
        })();
    """.trimIndent()
}

@Composable
fun ApplyDeftWindowChrome(config: DeftConfig) {
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.applyDeftChrome(config)
    }
}
