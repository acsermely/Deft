package app.deft.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * PARITY G10: `accounts.*` rules. Pure, unit tested; the Android side is `account.DeftAccounts`,
 * `account.DeftAuthenticator`, `account.DeftSyncAdapter` and `bridge.modules.AccountsModule`.
 *
 * Every app's accounts share one Android account type (`<applicationId>.account`). An account
 * belongs to the app whose origin id is in its user data under [KEY_ORIGIN]; the name the app
 * chose is under [KEY_NAME]. The Android account name, shown in system Settings, is
 * "<name> · <App name>" ([displayName]) so the owner is clear there.
 */
object AccountsSpec {
    const val NAME_MAX = 64
    const val APP_NAME_MAX = 40
    const val MAX_PER_APP = 8
    const val SEPARATOR = " · "

    /** Android's own floor for periodic syncs is 15 minutes. */
    const val MIN_PERIODIC_SECONDS = 900L
    const val MAX_PERIODIC_SECONDS = 30L * 24 * 3600

    const val KEY_ORIGIN = "origin"
    const val KEY_NAME = "name"

    /** What one Android account of the launcher's type says about itself. */
    data class Stored(val androidName: String, val origin: String?, val name: String?)

    sealed class Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>()
        data class Bad(val message: String) : Parsed<Nothing>()
    }

    /** `{name}`: trimmed, 1 to [NAME_MAX] characters, no control or line-break characters. */
    fun parseName(args: JsonObject): Parsed<String> {
        val p = args["name"] as? JsonPrimitive
        if (p == null || !p.isString) return Parsed.Bad("name must be a string")
        val name = p.contentOrNull.orEmpty().trim()
        return checkName(name)?.let { Parsed.Bad(it) } ?: Parsed.Ok(name)
    }

    /** Null when [name] (already trimmed) is a valid account name, else the reason. */
    fun checkName(name: String): String? {
        if (name.isEmpty()) return "name must not be empty"
        if (name.length > NAME_MAX) return "name must be at most $NAME_MAX characters"
        if (name.any { it.isISOControl() || it == ' ' || it == ' ' }) return "name must not contain control characters"
        return null
    }

    /** The Android account name: "<name> · <App name>", the app name cut to [APP_NAME_MAX]. */
    fun displayName(name: String, appName: String): String {
        val app = appName.trim().replace(Regex("\\p{Cntrl}"), " ").ifEmpty { "Web app" }
        val short = if (app.length > APP_NAME_MAX) app.take(APP_NAME_MAX - 1).trimEnd() + "…" else app
        return name + SEPARATOR + short
    }

    /** The accounts among [all] that belong to the app with origin id [origin]; ones without user data belong to no app. */
    fun ownedBy(all: List<Stored>, origin: String): List<Stored> =
        all.filter { it.origin != null && it.name != null && it.origin == origin }

    /** The app's account called [name], or null. */
    fun find(all: List<Stored>, origin: String, name: String): Stored? =
        ownedBy(all, origin).firstOrNull { it.name == name }

    sealed class AddCheck {
        data object Ok : AddCheck()
        /** The app already has an account with that name; adding it again is not an error. */
        data object Exists : AddCheck()
        data object TooMany : AddCheck()
        /** The Android name is taken by an account of another app (same name, same app name). */
        data object Taken : AddCheck()
    }

    fun checkAdd(all: List<Stored>, origin: String, name: String, androidName: String): AddCheck {
        val own = ownedBy(all, origin)
        if (own.any { it.name == name }) return AddCheck.Exists
        if (all.any { it.androidName == androidName }) return AddCheck.Taken
        if (own.size >= MAX_PER_APP) return AddCheck.TooMany
        return AddCheck.Ok
    }

    /** `accounts.setPeriodicSync {name, seconds}`: 0 turns it off, else [MIN_PERIODIC_SECONDS] to [MAX_PERIODIC_SECONDS]. */
    fun parsePeriodicSeconds(args: JsonObject): Parsed<Long> {
        val p = args["seconds"] as? JsonPrimitive
        val v = p?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it == Math.floor(it) }
            ?: return Parsed.Bad("seconds must be a whole number")
        val s = v.toLong()
        if (s == 0L) return Parsed.Ok(0L)
        if (s < MIN_PERIODIC_SECONDS || s > MAX_PERIODIC_SECONDS) {
            return Parsed.Bad("seconds must be 0 (off) or from $MIN_PERIODIC_SECONDS to $MAX_PERIODIC_SECONDS")
        }
        return Parsed.Ok(s)
    }

    /** `sync` trigger payload. */
    fun syncPayload(name: String): String = buildJsonObject { put("account", JsonPrimitive(name)) }.toString()
}
