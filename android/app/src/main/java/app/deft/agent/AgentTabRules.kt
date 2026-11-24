package app.deft.agent

import app.deft.workspace.AgentWorkspace
import app.deft.workspace.MyAppsStore

/** Tab tool gating, file roots, and Home proposals. No Android. */
object AgentTabRules {
    const val READ_LINES = 20

    private val HOME_ONLY = setOf(
        "apps.list",
        "apps.create",
        "sessions.list",
        "sessions.read",
        "sessions.open",
        "sessions.propose",
        "canvas.list",
        "canvas.read",
        "canvas.write",
        "canvas.edit",
        "canvas.delete",
        "canvas.grep",
        "canvas.glob",
        "canvas.verify",
        "canvas.debug",
        "canvas.debug.clear",
        "canvas.reset",
    )

    private val HOME_WORDS = Regex(
        "\\bhome ?screen|\\b(?:on|to|onto) (?:my |the )?home\\b(?! ?(?:work|page|automation|gym|improv|brew|décor))|canvas|wallpaper|kezdőképernyő|főképernyő|kezdokepernyo|fokepernyo",
        RegexOption.IGNORE_CASE,
    )
    private val APP_WORDS = Regex("\\bapps?\\b|\\bapplications?\\b|alkalmaz", RegexOption.IGNORE_CASE)

    /** Generic structural word only; open-ended topics (weather, prices...) are never matched, the model says so via `purpose`. */
    private val WIDGET_WORD = Regex("\\bwidgets?\\b", RegexOption.IGNORE_CASE)

    /** apps.create `purpose` values: a plain app, an app whose widget page goes on the home canvas, or an app that provides tools. */
    val CREATE_PURPOSES = setOf("app", "widget", "tools")

    /**
     * apps.create when the latest user message talks about the home screen / canvas / wallpaper and never says
     * "app": likely meant the canvas tools. The model declares an app that backs a home widget or provides
     * agent tools with `purpose` ("widget" / "tools"), which passes. Returns the tool error, or null to proceed.
     */
    fun createGuard(latestUserText: String, confirmed: Boolean, purpose: String = ""): String? {
        val p = purpose.trim().lowercase()
        if (p.isNotEmpty() && p !in CREATE_PURPOSES) {
            return "purpose must be one of app, widget, tools"
        }
        if (confirmed || p == "widget" || p == "tools") return null
        if (!HOME_WORDS.containsMatchIn(latestUserText) || APP_WORDS.containsMatchIn(latestUserText)) return null
        if (WIDGET_WORD.containsMatchIn(latestUserText)) return null
        return "The user's message mentions the home screen/canvas/wallpaper, not an app. " +
            "Static home screen / canvas / wallpaper requests belong to the canvas tools (canvas.write, canvas.edit). " +
            "If the request needs live data, background work or its own logic, call apps.create again with purpose:\"widget\" (an app whose widget page goes on the home canvas) or purpose:\"tools\". " +
            "If the user really wants a separate app, ask them to confirm, then call apps.create again with confirmed:\"true\"."
    }

    /**
     * The my-app slugs Home may edit: `myapp:<slug>` ids placed on the canvas whose app declares a widget
     * ([declaresWidget]), plus [created] (apps this Home session made). Placing a tag alone opens nothing.
     */
    fun homeEditable(placedIds: List<String>, created: Set<String>, declaresWidget: (String) -> Boolean): Set<String> =
        placedIds.filter { it.startsWith(MyAppsStore.ID_PREFIX) }
            .map { it.removePrefix(MyAppsStore.ID_PREFIX) }
            .filter { it !in created && declaresWidget(it) }
            .toSet() + created

    /** A my-app addressed from Home as `local/<slug>/<rel>`; [rel] is "." for the app root. */
    data class LocalRef(val slug: String, val rel: String)

    /**
     * Home only: `local/<slug>/...` addresses a my-app whose widget is on the canvas ([placed] holds
     * the placed apps' slugs). Null for any other path. Throws for a malformed or unplaced slug, so
     * nothing else outside the canvas becomes reachable from Home.
     */
    fun localRef(path: String, placed: Set<String>): LocalRef? {
        if (!path.startsWith("local/")) return null
        val slug = MyAppsStore.requireSlug(path.removePrefix("local/").substringBefore('/'))
        if (slug !in placed) {
            throw IllegalArgumentException("local/$slug is only reachable from Home while its widget is on the canvas or after Home created it")
        }
        val rel = path.removePrefix("local/").substringAfter('/', "").trim('/').ifBlank { "." }
        return LocalRef(slug, rel)
    }

    /** Tools about this tab's own my-app; Home has none. */
    private val APP_TAB_ONLY = setOf("apps.preview")

    fun toolAllowed(workspaceId: String, name: String): Boolean {
        if (name in HOME_ONLY) return AgentWorkspace.isHomeSlug(workspaceId)
        if (name in APP_TAB_ONLY) return !AgentWorkspace.isHomeSlug(workspaceId)
        return true
    }

    fun proposalFor(slug: String, current: String?, prompt: String): String {
        if (AgentWorkspace.isHomeSlug(slug)) {
            throw IllegalArgumentException("cannot propose for Home")
        }
        MyAppsStore.requireSlug(slug)
        val text = prompt.trim()
        if (text.isEmpty()) throw IllegalArgumentException("prompt required")
        if (!current.isNullOrBlank()) {
            throw IllegalArgumentException("tab $slug already has a proposal")
        }
        return text
    }

    /** First user message for a tab Home hands off to. The target must be idle with nothing pending. */
    fun handoffFor(slug: String, busy: Boolean, pending: Boolean, prompt: String): String {
        if (AgentWorkspace.isHomeSlug(slug)) {
            throw IllegalArgumentException("cannot hand off to Home")
        }
        MyAppsStore.requireSlug(slug)
        val text = prompt.trim()
        if (text.isEmpty()) throw IllegalArgumentException("prompt required")
        if (busy || pending) {
            throw IllegalArgumentException("tab $slug is busy; use sessions.propose instead")
        }
        return text
    }

    fun tabState(busy: Boolean, pending: Boolean, hasProposal: Boolean): String = when {
        busy -> "working"
        pending -> "needs_apply"
        hasProposal -> "suggestion"
        else -> "idle"
    }

    fun snippet(lines: List<AgentLine>): String? =
        lines.lastOrNull { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() }
            ?.text
            ?.trim()
            ?.replace('\n', ' ')
            ?.take(80)

    fun transcript(lines: List<AgentLine>, limit: Int = READ_LINES): String {
        val slice = lines.filter { it.text.isNotBlank() }.takeLast(limit)
        if (slice.isEmpty()) return "(empty)"
        return slice.joinToString("\n") { line ->
            val body = line.text.trim().replace('\n', ' ').take(240)
            "${line.role}: $body"
        }
    }
}
