package app.deft.telephony

// DEVICE-UNCHECKED (telephony extras (call screening, blocked numbers, SIM choice, USSD)): built and unit-tested, but the developer
// has not checked it on a real device yet. Check: a spam-blocker sample rejects a listed number; SIM choice and a safe USSD code work.
// Remove this note once checked.
/**
 * Carrier codes such as *100#: start with * or #, end with #, digits, * and # between.
 *
 * Review: supplementary-service codes change phone settings at the carrier (call forwarding,
 * barring, waiting, caller id, SIM PIN), so they are refused even behind the confirm. That is
 * every code starting with `**`, `##` or `*#`, and `*` or `#` followed by a service code.
 */
object UssdCodes {
    private val SHAPE = Regex("^[*#][0-9*#]{1,30}#$")

    /** 3GPP TS 22.030 service codes: forwarding, caller id, barring, waiting, password and PIN changes. */
    private val SERVICE = setOf(
        "21", "61", "62", "67", "002", "004",
        "30", "31", "33", "330", "331", "332", "333", "35", "351",
        "43", "03", "04", "042", "05", "052",
    )

    fun isValid(code: String): Boolean = SHAPE.matches(code) && !isServiceCode(code)

    private fun isServiceCode(code: String): Boolean {
        if (code.startsWith("**") || code.startsWith("##") || code.startsWith("*#")) return true
        val rest = code.substring(1)
        return SERVICE.any { rest.startsWith(it) && rest.length > it.length && rest[it.length] in "*#" }
    }
}
