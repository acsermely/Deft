package app.deft.ime

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PARITY G11: the launcher's own small keyboard, used where the web keyboard must not or cannot
 * be: password fields (the holder app sees nothing typed there), while the page loads, and when no
 * app holds `ime` or its page failed. Letters, digits and common symbols, Enter, backspace, space
 * and a key to switch to another keyboard. What is typed goes straight to the field; nothing is
 * kept or logged.
 */
class NativeKeyboard(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onSwitch: () -> Unit,
) {
    enum class Reason { PASSWORD, LOADING, NO_APP }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(0x20, 0x21, 0x24))
        setPadding(dp(4), dp(4), dp(4), dp(6))
    }
    private val header = TextView(context).apply {
        setTextColor(Color.rgb(0xbd, 0xc1, 0xc6))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(8), dp(2), dp(8), dp(4))
    }
    private var symbols = false
    private var shift = false

    val view: View get() = column

    /** Shows the keyboard for [reason]; [numbers] starts on the digits page (number passwords). */
    fun reset(reason: Reason, numbers: Boolean) {
        header.text = when (reason) {
            Reason.PASSWORD -> "Password field: Deft's own keyboard. The keyboard app does not see what you type here."
            Reason.LOADING -> "Deft keyboard"
            Reason.NO_APP -> "Deft keyboard. The keyboard app is not available."
        }
        symbols = numbers
        shift = false
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(header)
        val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
        rows.forEachIndexed { index, chars ->
            val line = row()
            if (!symbols && index == rows.lastIndex) {
                line.addView(key(if (shift) "SHIFT" else "Shift", 1.5f) { shift = !shift; render() }, 0)
            }
            for (c in chars) {
                val label = if (shift && !symbols) c.uppercase() else c.toString()
                line.addView(key(label, 1f) {
                    onText(label)
                    if (shift) {
                        shift = false
                        render()
                    }
                })
            }
            if (index == rows.lastIndex) line.addView(key("Del", 1.5f) { onBackspace() })
            column.addView(line)
        }
        val bottom = row()
        bottom.addView(key(if (symbols) "ABC" else "?123", 1.5f) { symbols = !symbols; shift = false; render() })
        bottom.addView(key("Switch", 1.5f) { onSwitch() })
        bottom.addView(key(",", 1f) { onText(",") })
        bottom.addView(key("Space", 4f) { onText(" ") })
        bottom.addView(key(".", 1f) { onText(".") })
        bottom.addView(key("Enter", 2f) { onEnter() })
        column.addView(bottom)
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
    }

    private fun key(label: String, weight: Float, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 13f else 18f)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(0x3c, 0x40, 0x43))
        }
        isClickable = true
        isSoundEffectsEnabled = true
        setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val SYMBOL_ROWS = listOf("1234567890", "@#\$%&-+()/", "*\"':;!?_=")
    }
}
