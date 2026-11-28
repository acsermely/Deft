package app.deft.ui.handlers

import app.deft.registry.DeftHandles

/**
 * The launcher's fixed inbound filters. Each is an `activity-alias` of
 * [HandlerPickerActivity] in AndroidManifest.xml, disabled until at least one installed app
 * handles it ([HandlerMatch.enabledAliases]). Exactly one of [mime] / [scheme] is set.
 */
enum class HandlerAlias(
    val key: String,
    val className: String,
    val mime: String? = null,
    val scheme: String? = null,
    /** Plain words for the dashboard: "Opens <label> without asking". */
    val label: String,
) {
    VIEW_TEXT("view-text", "app.deft.ui.handlers.ViewText", mime = "text/*", label = "text files"),
    VIEW_IMAGE("view-image", "app.deft.ui.handlers.ViewImage", mime = "image/*", label = "pictures"),
    VIEW_AUDIO("view-audio", "app.deft.ui.handlers.ViewAudio", mime = "audio/*", label = "audio files"),
    VIEW_VIDEO("view-video", "app.deft.ui.handlers.ViewVideo", mime = "video/*", label = "videos"),
    VIEW_PDF("view-pdf", "app.deft.ui.handlers.ViewPdf", mime = "application/pdf", label = "PDF files"),
    VIEW_CALENDAR("view-calendar", "app.deft.ui.handlers.ViewCalendar", mime = "text/calendar", label = "calendar files"),
    VIEW_VCARD("view-vcard", "app.deft.ui.handlers.ViewVcard", mime = "text/vcard", label = "contact cards"),
    VIEW_JSON("view-json", "app.deft.ui.handlers.ViewJson", mime = "application/json", label = "JSON files"),
    SENDTO_MAILTO("sendto-mailto", "app.deft.ui.handlers.SendToMailto", scheme = "mailto", label = "email links"),
    VIEW_WEB_DEFT("view-web-deft", "app.deft.ui.handlers.ViewWebDeft", scheme = "web+deft", label = "web+deft links"),
    ;

    companion object {
        fun forClassName(name: String?): HandlerAlias? = entries.firstOrNull { it.className == name }
        fun forKey(key: String?): HandlerAlias? = entries.firstOrNull { it.key == key }
    }
}

/** Pure matching rules for inbound "Open with" requests. No Android types. */
object HandlerMatch {
    const val MAX_URL = 2048
    private val mimeShape = Regex("^[a-z0-9][a-z0-9.+-]{0,62}/(\\*|[a-z0-9][a-z0-9.+-]{0,126})$")

    /** Lowercase, parameters dropped (`text/plain; charset=utf-8` → `text/plain`), `text/x-vcard` → `text/vcard`. Null if not a MIME type. */
    fun normalizeMime(raw: String?): String? {
        val base = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
        if (!mimeShape.matches(base)) return null
        return when (base) {
            "text/x-vcard" -> "text/vcard"
            else -> base
        }
    }

    /** True when [declared] covers [actual]. A wildcard subtype on either side matches its whole type. */
    fun mimeMatches(declared: String, actual: String): Boolean {
        val d = normalizeMime(declared) ?: return false
        val a = normalizeMime(actual) ?: return false
        val (dt, ds) = d.split('/', limit = 2)
        val (at, sub) = a.split('/', limit = 2)
        if (dt != at) return false
        return ds == "*" || sub == "*" || ds == sub
    }

    fun handlesMime(handles: DeftHandles, actual: String): Boolean = handles.mimeTypes.any { mimeMatches(it, actual) }

    fun handlesScheme(handles: DeftHandles, scheme: String): Boolean = scheme.lowercase() in handles.schemes

    /** True when [handles] would accept what [alias] receives (the app shows up in the picker for some such intent). */
    fun overlaps(handles: DeftHandles, alias: HandlerAlias): Boolean = when {
        alias.mime != null -> handlesMime(handles, alias.mime)
        alias.scheme != null -> handlesScheme(handles, alias.scheme)
        else -> false
    }

    /**
     * The aliases to enable for the effective handles of all installed apps. The text alias (any
     * `text/` type) is only enabled for a declared text type other than `text/calendar` and
     * `text/vcard`, which have their own aliases; while it is on, those two are off, because it
     * already receives them and two enabled aliases would list Deft twice in "Open with".
     */
    fun enabledAliases(all: List<DeftHandles>): Set<HandlerAlias> {
        val specific = setOf(HandlerAlias.VIEW_CALENDAR.mime, HandlerAlias.VIEW_VCARD.mime)
        val on = HandlerAlias.entries.filter { alias ->
            if (alias == HandlerAlias.VIEW_TEXT) {
                all.any { h -> h.mimeTypes.any { normalizeMime(it) !in specific && mimeMatches(it, alias.mime!!) } }
            } else {
                all.any { overlaps(it, alias) }
            }
        }.toMutableSet()
        if (HandlerAlias.VIEW_TEXT in on) {
            on -= HandlerAlias.VIEW_CALENDAR
            on -= HandlerAlias.VIEW_VCARD
        }
        return on
    }

    /** A scheme request's URL: at most [MAX_URL] chars, no control characters, scheme exactly [scheme]. */
    fun validLinkUrl(url: String?, scheme: String): Boolean {
        if (url.isNullOrEmpty() || url.length > MAX_URL) return false
        if (url.any { it.code < 0x20 || it.code == 0x7f }) return false
        return url.substringBefore(':', "").lowercase() == scheme
    }

    /**
     * The remembered "Always" app for an alias, if it still applies to this request: it must be
     * among [candidates] (installed, and handling this exact type or scheme). Otherwise ask again.
     */
    fun rememberedChoice(remembered: String?, candidates: List<String>): String? =
        remembered?.takeIf { it in candidates }
}
