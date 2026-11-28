package app.deft.ui.home

import kotlin.math.ceil

// DEVICE-UNCHECKED (DESIGN, home canvas clearance): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: with gesture navigation and with 3-button navigation,
// and on a very short screen (or split screen), the canvas widgets stop just above the dock; a canvas
// the user edited that still adds env(safe-area-inset-bottom) gets at most one system-bar height extra.
// Remove this note once checked.
/**
 * `--chrome-clearance` for the home canvas: the height of the native chrome drawn over the bottom of the
 * canvas WebView (widget row, dock, harness bar and the bottom system bar), in CSS px. It is set inline on the
 * root element, so it beats the `:root` fallback (240px) in the canvas stylesheet, including saved canvases.
 */
internal object ChromeClearance {
    const val VARIABLE = "--chrome-clearance"

    /** CSS px for a measured height in device px; 0 when nothing was measured yet. Rounded up so content never touches the dock. */
    fun cssPx(heightPx: Int, density: Float): Int =
        if (heightPx <= 0 || density <= 0f) 0 else ceil(heightPx / density - 0.01f).toInt()

    /** Sets the variable on the root element. Runs at document start and on every change. */
    fun js(cssPx: Int): String =
        "(function(){var r=document.documentElement;if(r)r.style.setProperty('$VARIABLE','${cssPx}px');})();"
}
