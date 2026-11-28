package app.deft.ui.home

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import app.deft.DeftApp
import app.deft.background.BackgroundRunner
import app.deft.data.InstalledWebApp
import app.deft.registry.DeftWidget
import app.deft.webview.CspPolicy
import app.deft.webview.LocalOriginPathHandler
import app.deft.webview.WebViewFinding
import app.deft.webview.WebViewFindings
import app.deft.workspace.DepStore
import app.deft.workspace.OverlayStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/** One `<deft-widget>` in the canvas HTML: its app id and size (1x1 when the attribute is absent). */
data class WidgetPlacement(val app: String, val size: String, val inGrid: Boolean = true)

/** W1: `<deft-widget app size>` in the home canvas: where widgets sit, and what canvas.verify reports about them. */
object CanvasWidgets {
    /** Most `deft-widget` elements that get a frame; the rest render a placeholder (deft-runtime elements.js). */
    const val CAP = 6

    /** Path of the widget index on the canvas origin. */
    const val INDEX_PATH = "/_deft/v1/widgets.json"

    private val comment = Regex("<!--[\\s\\S]*?(?:-->|$)")
    // The tag name must end at whitespace, `/` or `>` (not `deft-widget-x`); quoted values may contain `>`.
    private val tag = Regex("<deft-widget(?=[\\s/>])((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<(/?)([a-zA-Z][\\w-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>")
    private val gridClass = Regex("class\\s*=\\s*(?:\"[^\"]*\\bdeft-widgets\\b[^\"]*\"|'[^']*\\bdeft-widgets\\b[^']*')", RegexOption.IGNORE_CASE)
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    // Quoted or bare value; the name must follow whitespace, so `data-app` never counts as `app`.
    private val attr = Regex("(?<=\\s)(app|size)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", RegexOption.IGNORE_CASE)

    /**
     * Slugs of `myapp:<slug>` placements whose project folder exists but that have no web_apps row yet: the canvas
     * only frames installed apps, so these get registered (metadata only, no grants). Order kept, no duplicates.
     */
    fun slugsToRegister(placed: List<WidgetPlacement>, registeredIds: Set<String>, hasProject: (String) -> Boolean): List<String> =
        placed.map { it.app }.distinct()
            .filter { it.startsWith(MY_APP_PREFIX) && it !in registeredIds }
            .map { it.removePrefix(MY_APP_PREFIX) }
            .filter { slug -> slug.isNotEmpty() && runCatching { hasProject(slug) }.getOrDefault(false) }

    private const val MY_APP_PREFIX = "myapp:"

    /** Canvas JS: tell every widget frame to reload its theme.css (the frame script answers `deft:theme`). */
    const val THEME_FRAMES_JS =
        "(function(){try{document.querySelectorAll('deft-widget iframe').forEach(function(f){f.contentWindow.postMessage('deft:theme','*');});}catch(e){}})();"

    /** Canvas JS: elements.js drops its widget index and redraws (setup may have turned into ready while away). */
    const val REDRAW_WIDGETS_JS = "try{window.dispatchEvent(new Event('deft:widgets'))}catch(e){}"

    /** Canvas JS: ask the frames of these app origins to reload (the frame script answers `deft:reload`). */
    fun reloadFramesJs(origins: Collection<String>): String {
        val list = origins.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
        return "(function(){try{var o=$list;document.querySelectorAll('deft-widget iframe').forEach(function(f){" +
            "if(o.some(function(x){return f.src.indexOf(x+'/')===0;})){f.contentWindow.postMessage('deft:reload','*');}});}catch(e){}})();"
    }

    /** A change signature of [files]: names, sizes and modification times. Order does not matter. */
    fun stamp(files: Iterable<File>): Long {
        var h = 0L
        for (f in files) h += f.path.hashCode() * 31L + f.lastModified() * 17L + f.length()
        return h
    }

    /** Keys whose stamp differs from the previous round; nothing on the first round (frames load fresh) or for a key that is new. */
    fun changedStamps(old: Map<String, Long>?, now: Map<String, Long>): Set<String> =
        if (old == null) emptySet() else now.filter { (k, v) -> old[k]?.let { it != v } == true }.keys

    /**
     * The `<deft-widget>` elements of [html] in document order. Commented-out markup is ignored.
     */
    fun placements(html: String): List<WidgetPlacement> {
        val clean = comment.replace(html, " ")
        // Open elements, innermost last; true for one whose class list has deft-widgets.
        val stack = ArrayList<Boolean>()
        val out = ArrayList<WidgetPlacement>()
        for (m in anyTag.findAll(clean)) {
            val closing = m.groupValues[1].isNotEmpty()
            val name = m.groupValues[2].lowercase()
            val body = m.groupValues[3]
            if (name == "deft-widget") {
                if (closing) continue
                val attrs = mutableMapOf<String, String>()
                for (a in attr.findAll(body)) {
                    // The first of a repeated attribute wins, as in HTML.
                    attrs.getOrPut(a.groupValues[1].lowercase()) { a.groupValues[2].ifEmpty { a.groupValues[3].ifEmpty { a.groupValues[4] } } }
                }
                out += WidgetPlacement(attrs["app"].orEmpty(), attrs["size"].orEmpty().ifEmpty { "1x1" }, stack.any { it })
            } else if (closing) {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else if (name !in VOID && !body.trimEnd().endsWith("/")) {
                stack += gridClass.containsMatchIn(body)
            }
        }
        return out
    }

    /** One WARN per problem: unknown app, app without a widget, undeclared size, more than [CAP] elements. */
    fun findings(
        placed: List<WidgetPlacement>,
        installed: Set<String>,
        declared: Map<String, DeftWidget>,
        /** App id to what it still needs the user to allow (see [app.deft.data.AppRepository.widgetSetupNeeds]). */
        setup: Map<String, List<String>> = emptyMap(),
        /** App id to the SPECIAL capabilities it declares; a widget cannot get them. */
        special: Map<String, List<String>> = emptyMap(),
    ): List<WebViewFinding> =
        buildList {
            fun warn(text: String) = add(WebViewFindings.finding("WARN", "widget", text))
            if (placed.size > CAP) warn("${placed.size} deft-widget elements; only the first $CAP get a frame, the rest show a placeholder")
            for (p in placed) {
                val widget = declared[p.app]
                special[p.app].orEmpty().takeIf { it.isNotEmpty() && widget != null }?.let {
                    warn("deft-widget app=\"${p.app}\" declares special access (${it.joinToString(", ")}); special access is not for widgets: it can only be allowed in the dashboard, so remove it from the app manifest")
                }
                when {
                    p.app !in installed -> warn("deft-widget app=\"${p.app}\" is not an installed app; it shows a placeholder")
                    widget == null -> warn("deft-widget app=\"${p.app}\" declares no widget; it shows a placeholder")
                    setup[p.app].orEmpty().isNotEmpty() ->
                        warn("deft-widget app=\"${p.app}\" needs setup: ask the user to tap the widget once to allow ${setup.getValue(p.app).joinToString(", ")}")
                    !p.inGrid -> warn("deft-widget app=\"${p.app}\" has no .deft-widgets ancestor: wrap it in <div class=\"deft-widgets\"> so the 4-column grid sizes it")
                    p.size !in widget.sizes -> warn("deft-widget app=\"${p.app}\" size=\"${p.size}\" is not declared (sizes: ${widget.sizes.joinToString()})")
                }
            }
        }

    /** "setup" while [needs] is not empty (the canvas shows a tap-to-set-up placeholder instead of the frame), else "ready". */
    fun stateOf(needs: List<String>?): String = if (needs.isNullOrEmpty()) "ready" else "setup"

    /** `{appId: {origin, page, sizes, name, state}}` for the installed apps that declare a widget; [setup] maps app id to its open needs. */
    fun indexJson(apps: List<Pair<InstalledWebApp, DeftWidget>>, setup: Map<String, List<String>> = emptyMap()): String = buildJsonObject {
        for ((app, widget) in apps) {
            put(app.id, buildJsonObject {
                put("origin", JsonPrimitive(app.origin))
                put("page", JsonPrimitive(widget.page))
                put("name", JsonPrimitive(app.name))
                put("state", JsonPrimitive(stateOf(setup[app.id])))
                putJsonArray("sizes") { widget.sizes.forEach { add(JsonPrimitive(it)) } }
            })
        }
    }.toString()
}

/**
 * W1: serves the Home canvas origin, the widget files of the apps it places (each on its own origin)
 * and the widget index. Any other origin keeps answering 403 (canvasClient). Requests arrive on the
 * WebView's background thread, so this may block on the repository. The state is rebuilt per canvas
 * revision; [staleSignature] tells the canvas when an install, uninstall or manifest edit changed
 * what it should frame, so it can bump the revision.
 */
class CanvasWidgetServer(private val store: HomeCanvasStore) {
    /** A placed app whose widget the canvas may frame. */
    private class Frame(val app: InstalledWebApp, val widget: DeftWidget, val dir: File, val policy: CspPolicy, val needs: List<String>)

    private class State(
        val revision: Int,
        val signature: String,
        val canvas: WebViewAssetLoader,
        val apps: List<WebViewAssetLoader>,
        val index: String,
        /** Placed apps whose widget waits for the user to set it up (id to app). */
        val setup: Map<String, InstalledWebApp>,
    )

    private var state: State? = null

    /** The canvas files, the index, or a file of a placed widget app; null for everything else. */
    fun intercept(uri: Uri): WebResourceResponse? = try {
        val current = current()
        if (uri.host == CANVAS_HOST && uri.encodedPath == CanvasWidgets.INDEX_PATH) {
            index(current)
        } else {
            current.canvas.shouldInterceptRequest(uri) ?: current.apps.firstNotNullOfOrNull { it.shouldInterceptRequest(uri) }
        }
    } catch (_: Exception) {
        null
    }

    /** True when the placed widget apps (or what they declare) differ from what the live state serves; false before the first request. */
    suspend fun staleSignature(): Boolean = state?.let { signature(frames()) != it.signature } ?: false

    /** Origin of each placed widget app to a stamp of its served files (project and overlay); changes when the agent edits them. */
    suspend fun fileStamps(): Map<String, Long> {
        val app = DeftApp.instance
        val frames = frames()
        return withContext(Dispatchers.IO) {
            frames.associate { f ->
                val roots = listOfNotNull(f.dir, OverlayStore.dir(app, f.app.id)).filter { it.isDirectory }
                f.app.origin to CanvasWidgets.stamp(roots.flatMap { it.walkTopDown().maxDepth(8).filter { x -> x.isFile }.take(3000).toList() })
            }
        }
    }

    fun setupIds(): Set<String> = state?.setup?.keys.orEmpty()

    /** The placed widget app with this id when it is waiting for setup in the live state, else null (the `deft://setup/` tap rule). */
    fun setupApp(id: String): InstalledWebApp? = state?.setup?.get(id)

    /** W2: the installed apps the canvas places and may frame; the bridge router answers only their origins. */
    suspend fun widgetApps(): List<InstalledWebApp> = frames().map { it.app }

    /** The CSP the canvas is served with right now (what canvas.verify must load under). */
    suspend fun canvasPolicy(): CspPolicy =
        CspPolicy(flags = CspPolicy.OFFLINE.flags, frameOrigins = frames().map { it.app.origin })

    /** canvas.verify: lint-style WARN lines for the canvas's `deft-widget` elements. */
    suspend fun findings(): List<WebViewFinding> {
        val rows = installed()
        val installedIds = rows.map { it.first.id }.toSet()
        val declared = rows.mapNotNull { (app, widget) -> widget?.let { app.id to it } }.toMap()
        val repo = DeftApp.instance.repository
        val setup = withContext(Dispatchers.IO) {
            rows.mapNotNull { (app, widget) ->
                if (widget == null) null else runCatching { repo.widgetSetupNeeds(app) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { app.id to it }
            }.toMap()
        }
        val special = withContext(Dispatchers.IO) {
            rows.mapNotNull { (app, widget) ->
                if (widget == null) null else runCatching { repo.widgetSpecialCaps(app) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { app.id to it }
            }.toMap()
        }
        return CanvasWidgets.findings(store.placedWidgets.value, installedIds, declared, setup, special)
    }

    private fun index(current: State) = WebResourceResponse(
        "application/json",
        "utf-8",
        200,
        "OK",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(current.index.toByteArray()),
    )

    @Synchronized
    private fun current(): State {
        val revision = store.revision.value
        state?.takeIf { it.revision == revision }?.let { return it }
        val frames = runCatching { runBlocking { frames() } }.getOrDefault(emptyList())
        val app = DeftApp.instance
        val host = LocalOriginPathHandler.hostOfOrigin(HomeCanvasStore.ORIGIN)
        val next = State(
            revision = revision,
            signature = signature(frames),
            canvas = LocalOriginPathHandler.loader(
                store.dir(),
                host,
                policy = CspPolicy(flags = CspPolicy.OFFLINE.flags, frameOrigins = frames.map { it.app.origin }),
            ),
            apps = frames.map { f ->
                LocalOriginPathHandler.loader(
                    f.dir,
                    LocalOriginPathHandler.hostOfOrigin(f.app.origin),
                    // The canvas frames the widget: only responses served inside the canvas WebView allow it.
                    policy = f.policy.copy(frameAncestors = listOf(HomeCanvasStore.ORIGIN)),
                    overlay = OverlayStore.dir(app, f.app.id),
                    depStore = DepStore.root(app),
                )
            },
            index = indexOf(frames),
            setup = frames.filter { it.needs.isNotEmpty() }.associate { it.app.id to it.app },
        )
        state = next
        return next
    }

    /** The installed apps that declare a widget and that the canvas places, with a serving directory. */
    private suspend fun frames(): List<Frame> {
        val placed = store.placedWidgets.value.map { it.app }.toSet()
        if (placed.isEmpty()) return emptyList()
        val repo = DeftApp.instance.repository
        registerPlacedApps()
        return installed().mapNotNull { (row, widget) ->
            if (widget == null || row.id !in placed) return@mapNotNull null
            BackgroundRunner.servingDir(row)?.let {
                val needs = withContext(Dispatchers.IO) { runCatching { repo.widgetSetupNeeds(row) }.getOrDefault(emptyList()) }
                Frame(row, widget, it, repo.cspPolicyFor(row), needs)
            }
        }
    }

    /**
     * Placed `myapp:<slug>` apps with a project folder but no web_apps row (a canvas written before the app was
     * registered) get their row here, so an existing canvas heals without a rewrite. Idempotent, metadata only.
     */
    private suspend fun registerPlacedApps() = withContext(Dispatchers.IO) {
        runCatching {
            val app = DeftApp.instance
            val registered = app.repository.allApps().map { it.id }.toSet()
            for (slug in CanvasWidgets.slugsToRegister(store.placedWidgets.value, registered) { app.myApps.hasProject(it) }) {
                runCatching { app.repository.ensureWorkspaceApp(slug) }
            }
        }
    }

    private fun indexOf(frames: List<Frame>) =
        CanvasWidgets.indexJson(frames.map { it.app to it.widget }, frames.associate { it.app.id to it.needs })

    /** The index includes each widget's setup state, so a grant (or a revoke) while away changes it. */
    private fun signature(frames: List<Frame>) = indexOf(frames)

    private suspend fun installed(): List<Pair<InstalledWebApp, DeftWidget?>> = withContext(Dispatchers.IO) {
        val repo = DeftApp.instance.repository
        repo.allApps().map { it to repo.widgetFor(it) }
    }

    private companion object {
        val CANVAS_HOST: String = LocalOriginPathHandler.hostOfOrigin(HomeCanvasStore.ORIGIN)
    }
}
