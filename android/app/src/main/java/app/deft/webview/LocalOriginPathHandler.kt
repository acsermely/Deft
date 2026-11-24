package app.deft.webview

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import app.deft.registry.DeftDependency
import app.deft.registry.DeftManifestParser
import app.deft.workspace.DepStore
import app.deft.workspace.MergedAppTree
import app.deft.workspace.OverlayStore
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class LocalOriginPathHandler(
    private val dir: File,
    /** What the CSP allows; built by [CspBuilder] (PARITY G1). */
    private val policy: CspPolicy = CspPolicy(),
    private val overlay: File? = null,
    /** Dependency store root (spec 10.10); null disables /_deft/deps/ and the import map. */
    private val depStore: File? = null,
) : WebViewAssetLoader.PathHandler {
    private val tree = MergedAppTree(dir, overlay)
    private var deletedAt = Long.MIN_VALUE
    private var deletedPaths: Set<String> = emptySet()
    private var depsStamp = ""
    private var depsCache: List<DeftDependency> = emptyList()
    // Built eagerly: a policy the builder refuses fails here, not on every response.
    private val csp: String = CspBuilder.build(policy)

    private fun cspWith(extraScript: String): String = CspBuilder.build(policy, extraScript)
    private val cssHeaders: Map<String, String> by lazy { headerMap("no-store") }
    private val otherHeaders: Map<String, String> by lazy { headerMap("max-age=0") }

    override fun handle(path: String): WebResourceResponse? {
        return try {
            var rel = path.trimStart('/')
            if (rel.isEmpty() || rel.endsWith("/")) {
                rel = rel.trimEnd('/') + if (rel.isEmpty()) "index.html" else "/index.html"
            }
            if (rel.contains("..")) return notFound()
            // Apps made before the Deft rename load /_fl/v1/ and use fl- names: serve the same runtime,
            // with its names translated back (legacyNames).
            val legacy = rel.startsWith("_fl/v1/")
            if (legacy) rel = "_deft/v1/" + rel.removePrefix("_fl/v1/")
            if (rel == "_deft/v1/theme.css" || rel.startsWith("_deft/v1/theme.css?")) {
                val css = themeCss().let { if (legacy) legacyNames(it) else it }
                return WebResourceResponse(
                    "text/css",
                    "utf-8",
                    200,
                    "OK",
                    headers("text/css"),
                    ByteArrayInputStream(css),
                )
            }
            // PARITY D1: the host page of a background run; generated from the manifest `background`.
            if (rel == BACKGROUND_PAGE || rel.startsWith("$BACKGROUND_PAGE?")) return backgroundPage()
            // W5: the host page of an agent tool run; only the tool runner loads it.
            if (rel == TOOL_PAGE || rel.startsWith("$TOOL_PAGE?")) return toolPage()
            if (rel.startsWith("_deft/v1/")) {
                val name = rel.removePrefix("_deft/v1/").substringBefore('?')
                val flat = name.isNotEmpty() && !name.contains("..") && !name.contains('/')
                if (flat || FONT_FILE.matches(name)) {
                    val bytes = runtimeAsset(name)?.let { if (legacy) legacyNames(it, name) else it }
                    if (bytes != null) {
                        val mime = guessMime(name)
                        return WebResourceResponse(
                            mime,
                            "utf-8",
                            200,
                            "OK",
                            headers(mime),
                            ByteArrayInputStream(bytes),
                        )
                    }
                }
                return notFound()
            }
            if (rel.startsWith("_deft/deps/")) return serveDep(rel.removePrefix("_deft/deps/").substringBefore('?'))
            // Apps made before the rename import dependencies from /_fl/deps/: the same store, unchanged.
            if (rel.startsWith("_fl/deps/")) return serveDep(rel.removePrefix("_fl/deps/").substringBefore('?'))
            // Reserved: only /_fl/v1/ and /_fl/deps/ are served (above); other /_fl/ paths never reach app files.
            if (rel.startsWith("_deft/") || rel.startsWith("_fl/")) return notFound()
            val file = tree.resolve(rel, deletedNow()) ?: return notFound()
            if (file.length() > MAX_BYTES) return notFound()
            val mime = guessMime(file.name)
            val encoding = if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")) {
                "utf-8"
            } else {
                null
            }
            if (mime == "text/html") {
                val text = file.readText()
                val html = withFontPreload(withWidgetElements(text))
                val map = depStore?.let { DepStore.importMap(it, dependencies()) }
                if (map != null) return htmlWithImportMap(html, map)
                if (html !== text) {
                    return WebResourceResponse(mime, encoding, 200, "OK", headers(mime), ByteArrayInputStream(html.toByteArray()))
                }
            }
            WebResourceResponse(mime, encoding, 200, "OK", headers(mime), file.inputStream())
        } catch (_: Exception) {
            notFound()
        }
    }

    private fun withWidgetElements(html: String): String = injectWidgetElements(html)

    /**
     * Preloads the one font every design-system page needs (Geist text) so first paint does not
     * wait for the stylesheet. Only pages that link the runtime base.css get it. Returns [html]
     * itself when nothing changes, so callers can tell.
     */
    private fun withFontPreload(html: String): String {
        if (!BASE_CSS.containsMatchIn(html) || FONT_PRELOAD_MARK.containsMatchIn(html)) return html
        val tag = "<link rel=\"preload\" as=\"font\" type=\"font/woff2\" crossorigin href=\"/_deft/v1/fonts/geist.woff2\">"
        val head = HEAD_TAG.find(html) ?: return html
        return html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
    }

    /**
     * PARITY D1: `<script type="module" src="<module>">` and nothing else, so the page runs under
     * the app's normal CSP without an inline script. 404 when the manifest declares no module.
     */
    private fun backgroundPage(): WebResourceResponse {
        val manifest = tree.manifestFile()
        if (!manifest.isFile) return notFound()
        val module = runCatching { DeftManifestParser.parse(manifest.readText()).background?.module }.getOrNull()
            ?: return notFound()
        val html = backgroundHtml(module)
        val map = depStore?.let { DepStore.importMap(it, dependencies()) }
        if (map != null) return htmlWithImportMap(html, map)
        return WebResourceResponse(
            "text/html",
            "utf-8",
            200,
            "OK",
            headers("text/html"),
            ByteArrayInputStream(html.toByteArray()),
        )
    }

    private fun toolPage(): WebResourceResponse {
        val html = toolHtml()
        val map = depStore?.let { DepStore.importMap(it, dependencies()) }
        if (map != null) return htmlWithImportMap(html, map)
        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers("text/html"), ByteArrayInputStream(html.toByteArray()))
    }

    /** `<host>/<path>` of a declared CDN URL, resolved only through this app's own manifest. */
    private fun serveDep(rest: String): WebResourceResponse {
        val store = depStore ?: return notFound()
        if (rest.split('/').any { it.isEmpty() || it.startsWith(".") }) return notFound()
        val file = DepStore.lookup(store, dependencies(), rest) ?: return notFound()
        val name = rest.substringAfterLast('/')
        val mime = when {
            name.endsWith(".json") -> "application/json"
            name.endsWith(".wasm") -> "application/wasm"
            else -> "text/javascript"
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers(mime), file.inputStream())
    }

    /** Inserts the host import map first in <head> and allows exactly that inline script by hash. */
    private fun htmlWithImportMap(html: String, map: String): WebResourceResponse {
        val tag = "<script type=\"importmap\">$map</script>"
        val head = HEAD_TAG.find(html) ?: HTML_TAG.find(html)
        val out = if (head != null) {
            html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
        } else {
            tag + html
        }
        val hash = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(map.toByteArray(Charsets.UTF_8)),
        )
        val headers = mapOf(
            "Content-Security-Policy" to cspWith(" 'sha256-$hash'"),
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "max-age=0",
        )
        return WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(out.toByteArray()))
    }

    /** This app's declared dependencies; the overlay manifest wins, re-read when it changes. */
    @Synchronized
    private fun dependencies(): List<DeftDependency> {
        val manifest = tree.manifestFile()
        val stamp = "${manifest.path}:${manifest.lastModified()}:${manifest.length()}"
        if (stamp != depsStamp) {
            depsCache = DepStore.dependenciesIn(manifest)
            depsStamp = stamp
        }
        return depsCache
    }

    @Synchronized
    private fun deletedNow(): Set<String> {
        val dir = overlay ?: return emptySet()
        val meta = OverlayStore.metaFile(dir)
        val stamp = if (meta.isFile) meta.lastModified() else 0L
        if (stamp == deletedAt) return deletedPaths
        deletedPaths = if (stamp == 0L) emptySet() else OverlayStore.deleted(dir)
        deletedAt = stamp
        return deletedPaths
    }

    private fun headers(mime: String): Map<String, String> =
        if (mime == "text/css") cssHeaders else otherHeaders

    private fun headerMap(cache: String): Map<String, String> = mapOf(
        "Content-Security-Policy" to csp,
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to cache,
    )

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            headers("text/plain"),
            ByteArrayInputStream(ByteArray(0)),
        )

    companion object {
        private val LEGACY_TEXT = Regex("\\.(css|js|mjs|json|html|svg)$")
        private val LEGACY_LAYER = Regex("\\bdeft\\.(reset|base|layout|components|utilities|theme)\\b")
        private val LEGACY_DATASET = Regex("\\bdataset\\.deft(?=[A-Z])")

        /** The runtime as it was named before the Deft rename: deft- → fl-, /_deft/ → /_fl/, deft.* layers → fl.*, dataset.deftX → dataset.flX (it reads data-fl-x). */
        fun legacyNames(text: String): String = text
            .replace("/_deft/", "/_fl/")
            .replace(Regex("(?<![A-Za-z0-9_])deft-(?=[a-z0-9])"), "fl-")
            .replace(LEGACY_LAYER) { "fl." + it.groupValues[1] }
            .replace(LEGACY_DATASET, "dataset.fl")

        internal fun legacyNames(bytes: ByteArray, name: String): ByteArray =
            if (LEGACY_TEXT.containsMatchIn(name)) legacyNames(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes

        internal fun legacyNames(css: ByteArray): ByteArray = legacyNames(css, "theme.css")

        private const val MAX_BYTES = 32L * 1024 * 1024

        /** PARITY D1: path (without the leading slash) of the background host page. */
        const val BACKGROUND_PAGE = "_deft/v1/background.html"

        /** W5: the page an agent tool run loads (see [toolHtml]). */
        const val TOOL_PAGE = "_deft/v1/tool.html"

        fun toolHtml(): String =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>tool</title>" +
                "<script type=\"module\" src=\"/_deft/v1/tool-runner.js\"></script></head><body></body></html>"

        /** [module] passed [DeftManifestParser.validBackgroundModule], so it needs no escaping. */
        fun backgroundHtml(module: String): String =
            "<!doctype html><html><head><meta charset=\"utf-8\"><title>background</title>" +
                "<script type=\"module\" src=\"$module\"></script></head><body></body></html>"

        fun loader(
            dir: File,
            host: String,
            policy: CspPolicy = CspPolicy(),
            overlay: File? = null,
            depStore: File? = null,
        ): WebViewAssetLoader =
            WebViewAssetLoader.Builder()
                .setDomain(host)
                .setHttpAllowed(false)
                .addPathHandler("/", LocalOriginPathHandler(dir, policy, overlay, depStore))
                .build()

        private val ELEMENTS_REF = Regex("/_(?:deft|fl)/v1/elements\\.js|deft-runtime/v1/elements\\.js|/_(?:deft|fl)/v1/motion\\.js")
        private const val ELEMENTS_TAG = "<script type=\"module\" src=\"/_deft/v1/elements.js\"></script>"

        /**
         * A page with `<deft-widget` that never loads elements.js would show a blank, dead element; add the
         * runtime script (same origin, allowed by script-src 'self'). Returns [html] itself when unchanged.
         */
        internal fun injectWidgetElements(html: String): String {
            if (!html.contains("<deft-widget", ignoreCase = true) || ELEMENTS_REF.containsMatchIn(html)) return html
            val head = HEAD_TAG.findAll(html).firstOrNull()
            val close = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)
            if (close != null) return html.substring(0, close.range.first) + ELEMENTS_TAG + html.substring(close.range.first)
            val body = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (body != null) return html.substring(0, body.range.last + 1) + ELEMENTS_TAG + html.substring(body.range.last + 1)
            if (head != null) return html.substring(0, head.range.last + 1) + ELEMENTS_TAG + html.substring(head.range.last + 1)
            return ELEMENTS_TAG + html
        }

        private val HEAD_TAG = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)

        private val BASE_CSS = Regex("/_(?:deft|fl)/v1/base\\.css|deft-runtime/v1/base\\.css")
        private val FONT_PRELOAD_MARK = Regex("rel=\"preload\"[^>]*/_(?:deft|fl)/v1/fonts/")
        private val FONT_FILE = Regex("fonts/[a-z0-9-]+\\.woff2")
        private val HTML_TAG = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)

        @Volatile
        var assets: AssetManager? = null

        private val runtimeCache = ConcurrentHashMap<String, ByteArray>()
        @Volatile private var themeCache: ByteArray? = null
        @Volatile private var themeStamp = ""

        fun hostOfOrigin(origin: String): String = Uri.parse(origin).host ?: origin.substringAfter("://")

        private fun themeCss(): ByteArray {
            val css = DeftRuntimeTheme.css()
            val cached = themeCache
            if (cached != null && themeStamp == css) return cached
            val bytes = css.toByteArray()
            themeCache = bytes
            themeStamp = css
            return bytes
        }

        private fun runtimeAsset(name: String): ByteArray? {
            runtimeCache[name]?.let { return it }
            val manager = assets ?: return null
            val bytes = runCatching { manager.open("deft-runtime/v1/$name").use { it.readBytes() } }.getOrNull()
                ?: return null
            runtimeCache[name] = bytes
            return bytes
        }

        private fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js", "mjs" -> "text/javascript"
                "json", "webmanifest" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "wasm" -> "application/wasm"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }
    }
}
