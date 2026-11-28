package app.deft.ime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Pure rules for the launcher's keyboard (the `ime` role). No Android types; unit tested. The
 * numbers below are Android's `InputType`, `EditorInfo` and `KeyEvent` constants, copied so this
 * file runs on the JVM.
 */
object ImeSpec {
    const val START_EVENT = "ime.start"
    const val FINISH_EVENT = "ime.finish"
    const val FINISH_PAYLOAD = "{}"

    /** Longest text for `ime.commitText` / `ime.setComposing`, and largest `ime.deleteBefore`. */
    const val TEXT_MAX = 1000
    const val DELETE_MAX = 1000

    /** Keyboard height the page may ask for with `ime.setHeight`, in dp; [DEFAULT_HEIGHT_DP] until it does. */
    const val HEIGHT_MIN_DP = 100
    const val HEIGHT_MAX_DP = 600
    const val DEFAULT_HEIGHT_DP = 260

    /** Never taller than this share of the screen, whatever the page asked. */
    const val HEIGHT_MAX_SCREEN_SHARE = 0.7

    // android.text.InputType
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val TYPE_CLASS_DATETIME = 0x00000004
    private const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    private const val TYPE_TEXT_VARIATION_URI = 0x00000010
    private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    // android.view.inputmethod.EditorInfo
    private const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_GO = 2
    const val IME_ACTION_SEARCH = 3
    const val IME_ACTION_SEND = 4
    const val IME_ACTION_NEXT = 5
    const val IME_ACTION_DONE = 6
    private const val IME_FLAG_NO_ENTER_ACTION = 0x40000000

    /**
     * True for a field whose text must never reach the web keyboard: text password, visible
     * password, web password and number password. Such a field gets the launcher's own keyboard.
     */
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and TYPE_MASK_VARIATION
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** True for a number password (the launcher's own keyboard then shows digits first). */
    fun isNumberPassword(inputType: Int): Boolean =
        inputType and TYPE_MASK_CLASS == TYPE_CLASS_NUMBER && inputType and TYPE_MASK_VARIATION == TYPE_NUMBER_VARIATION_PASSWORD

    /** `inputType` of `ime.start`: text, number, phone, email, url, password or multiline. */
    fun inputTypeName(inputType: Int): String {
        if (isPassword(inputType)) return "password"
        return when (inputType and TYPE_MASK_CLASS) {
            TYPE_CLASS_NUMBER, TYPE_CLASS_DATETIME -> "number"
            TYPE_CLASS_PHONE -> "phone"
            TYPE_CLASS_TEXT -> when (inputType and TYPE_MASK_VARIATION) {
                TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
                TYPE_TEXT_VARIATION_URI -> "url"
                else -> if (inputType and TYPE_TEXT_FLAG_MULTI_LINE != 0) "multiline" else "text"
            }
            else -> "text" // TYPE_NULL: an editor with no type (a terminal, say)
        }
    }

    /** `action` of `ime.start`: go, search, send, next, done or none (Enter then types a new line). */
    fun actionName(imeOptions: Int): String {
        if (imeOptions and IME_FLAG_NO_ENTER_ACTION != 0) return "none"
        return when (imeOptions and IME_MASK_ACTION) {
            IME_ACTION_GO -> "go"
            IME_ACTION_SEARCH -> "search"
            IME_ACTION_SEND -> "send"
            IME_ACTION_NEXT -> "next"
            IME_ACTION_DONE -> "done"
            else -> "none"
        }
    }

    /** The EditorInfo action id for [actionName], or null for none (Enter is then a key press). */
    fun actionId(name: String): Int? = when (name) {
        "go" -> IME_ACTION_GO
        "search" -> IME_ACTION_SEARCH
        "send" -> IME_ACTION_SEND
        "next" -> IME_ACTION_NEXT
        "done" -> IME_ACTION_DONE
        else -> null
    }

    /**
     * The `ime.start` payload. [packageName] is the app the field belongs to. Nothing about the
     * field's text: the page never gets the text around the cursor.
     */
    fun startPayload(inputType: Int, imeOptions: Int, packageName: String?): String = buildJsonObject {
        put("inputType", JsonPrimitive(inputTypeName(inputType)))
        put("action", JsonPrimitive(actionName(imeOptions)))
        put("package", JsonPrimitive(packageName.orEmpty().take(256)))
    }.toString()

    /** `ime.sendKey` names and their android.view.KeyEvent codes. */
    val KEYS: Map<String, Int> = mapOf(
        "enter" to 66, // KEYCODE_ENTER
        "backspace" to 67, // KEYCODE_DEL
        "tab" to 61, // KEYCODE_TAB
        "left" to 21, // KEYCODE_DPAD_LEFT
        "right" to 22, // KEYCODE_DPAD_RIGHT
        "up" to 19, // KEYCODE_DPAD_UP
        "down" to 20, // KEYCODE_DPAD_DOWN
    )

    /** The key code for an `ime.sendKey` key name; null for any other name. */
    fun keyCode(name: String?): Int? = KEYS[name]

    /** A whole number in [min]..[max] from a number or numeric string arg; null otherwise. */
    fun intArg(args: JsonObject, key: String, min: Int, max: Int): Int? {
        val raw = (args[key] as? JsonPrimitive)?.contentOrNull ?: return null
        val value = raw.toDoubleOrNull() ?: return null
        if (value.isNaN() || value != Math.floor(value)) return null
        if (value < min || value > max) return null
        return value.toInt()
    }

    /** `ime.deleteBefore {n}`: 1..[DELETE_MAX]; null when out of range or not a whole number. */
    fun deleteCount(args: JsonObject): Int? = intArg(args, "n", 1, DELETE_MAX)

    /** `ime.setHeight {dp}`: [HEIGHT_MIN_DP]..[HEIGHT_MAX_DP]; null when out of range or not a whole number. */
    fun heightDp(args: JsonObject): Int? = intArg(args, "dp", HEIGHT_MIN_DP, HEIGHT_MAX_DP)

    /** Keyboard height in pixels for [dp], never more than [HEIGHT_MAX_SCREEN_SHARE] of [screenPx]. */
    fun heightPx(dp: Int, density: Float, screenPx: Int): Int {
        val wanted = (dp * density).toInt()
        val cap = (screenPx * HEIGHT_MAX_SCREEN_SHARE).toInt()
        return if (cap > 0) minOf(wanted, cap) else wanted
    }

    /** Text for `commitText` / `setComposing`: a string of at most [TEXT_MAX] characters; null otherwise. */
    fun textArg(args: JsonObject): String? {
        val p = args["text"] as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.takeIf { it.length <= TEXT_MAX }
    }

    /**
     * `ime.*` methods are only for the keyboard page the launcher's keyboard shows
     * (a bridge flagged background with `ime` as its run capability). The app's own pages, its
     * background runs and its wallpaper page get `unsupported`.
     */
    fun methodAllowed(method: String, fromKeyboardPage: Boolean): Boolean =
        !method.startsWith("ime.") || fromKeyboardPage

    /** Calls made for each key press; their successful audit rows are thinned out ([shouldLogTyping]). */
    val TYPING_METHODS: Set<String> = setOf(
        "ime.commitText", "ime.deleteBefore", "ime.sendKey", "ime.setComposing", "ime.finishComposing",
    )

    /** Shortest gap between two audit rows for successful [TYPING_METHODS] of one app. */
    const val TYPING_LOG_GAP_MS = 60_000L

    /** Whether a successful typing call is written to the audit log, [lastMs] being the last one written (null: none). */
    fun shouldLogTyping(lastMs: Long?, nowMs: Long): Boolean =
        lastMs == null || nowMs < lastMs || nowMs - lastMs >= TYPING_LOG_GAP_MS

    /**
     * Whether [defaultSetting] (Settings.Secure.DEFAULT_INPUT_METHOD, "package/.Class" or
     * "package/package.Class") names the service [className] of [packageName].
     */
    fun isImeId(defaultSetting: String?, packageName: String, className: String): Boolean {
        if (defaultSetting.isNullOrEmpty()) return false
        val pkg = defaultSetting.substringBefore('/', "")
        val cls = defaultSetting.substringAfter('/', "")
        if (pkg != packageName || cls.isEmpty()) return false
        val full = if (cls.startsWith(".")) pkg + cls else cls
        return full == className
    }
}
