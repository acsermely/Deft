package app.deft.bridge

import android.view.HapticFeedbackConstants

/**
 * `haptics.play` (deft-design motion.md): the page's `deft.haptic(kind)` as a View haptic. It needs
 * no capability grant, is foreground only, and the system's touch-feedback setting still applies.
 */
internal object HapticSpec {
    const val METHOD = "haptics.play"

    /** The View constant for [kind], or null for a kind the page may not ask for. minSdk is 30, so CONFIRM and REJECT exist. */
    fun constant(kind: String): Int? = when (kind) {
        "light" -> HapticFeedbackConstants.VIRTUAL_KEY
        "medium" -> HapticFeedbackConstants.CONTEXT_CLICK
        "selection" -> HapticFeedbackConstants.CLOCK_TICK
        "success" -> HapticFeedbackConstants.CONFIRM
        "warning" -> HapticFeedbackConstants.REJECT
        else -> null
    }
}
