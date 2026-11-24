package app.deft.webview

object DeftRuntimeTheme {
    @Volatile
    var sheet: String = ":root { color-scheme: dark; }\n"

    fun css(): String = sheet
}
