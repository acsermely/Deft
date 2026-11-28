package app.deft.registry

import app.deft.catalog.Capability
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DeftCapabilityDecl(
    val id: String,
    val reason: String,
)

/**
 * One CDN file from manifest.dependencies. [integrity] is an SRI hash,
 * null until a local app pins it. [alias] is an optional bare import name.
 */
data class DeftDependency(
    val url: String,
    val integrity: String?,
    val alias: String? = null,
) {
    val host: String get() = url.removePrefix("https://").substringBefore('/')
    val path: String get() = url.removePrefix("https://").substringAfter('/', "")
}

/**
 * Manifest `assetPacks[]`. A zip the launcher downloads after a consent line
 * that shows [size], checks against [sha256] (lowercase hex of the zip) and serves same-origin
 * under `/_deft/packs/<name>/`.
 */
data class DeftAssetPack(
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

data class DeftManifest(
    val deft: Int,
    val id: String,
    val name: String,
    val version: String,
    val profile: String,
    val startUrl: String,
    val capabilities: List<Pair<Capability, String>>,
    val network: List<String>,
    /** Third-party origins for `web.embed` ([DeftManifestParser.validEmbedOrigin]). */
    val embeds: List<String> = emptyList(),
    val dependencies: List<DeftDependency> = emptyList(),
    val shortcuts: List<DeftShortcut> = emptyList(),
    /** The module the host may run when the app is closed; null when not declared. */
    val background: DeftBackground? = null,
    /** What other Android apps may open in this app ("Open with"); null when not declared. */
    val handles: DeftHandles? = null,
    /** The page the launcher's live wallpaper shows (role `wallpaper`); null when not declared. */
    val wallpaper: DeftWallpaper? = null,
    /** The page the launcher's keyboard shows (role `ime`); null when not declared. */
    val keyboard: DeftKeyboard? = null,
    /** The page the home canvas shows as an `deft-widget` (role `widget`); null when not declared. */
    val widget: DeftWidget? = null,
    /** Tools the on-device agent may call ([DeftToolsParser]); empty when none are declared. */
    val tools: List<DeftTool> = emptyList(),
    /** Websites (relying party ids) whose passkeys the app may use ([app.deft.bridge.PasskeyPolicy]); empty when none. */
    val passkeyRpIds: List<String> = emptyList(),
    /** Asset packs ([DeftManifestParser.parseAssetPacks]); empty when none. */
    val assetPacks: List<DeftAssetPack> = emptyList(),
    val license: String?,
    val source: String?,
)

/**
 * Manifest `background`. [module] is an absolute same-origin path ("/bg.js"; "./bg.js"
 * in the manifest is stored without the dot). [triggers] are the declared names, `periodic:N`
 * kept as written.
 */
data class DeftBackground(val module: String, val triggers: List<String>)

/**
 * Manifest `wallpaper`. [page] is an absolute same-origin path ("/wallpaper.html";
 * "./wallpaper.html" in the manifest is stored without the dot).
 */
data class DeftWallpaper(val page: String)

/**
 * Manifest `keyboard`. [page] is an absolute same-origin path ("/keyboard/index.html";
 * "./keyboard/index.html" in the manifest is stored without the dot). Same path rule as [DeftWallpaper].
 */
data class DeftKeyboard(val page: String)

/**
 * Manifest `widget`. [page] is an absolute same-origin path ("/widget.html"; "./widget.html" in the
 * manifest is stored without the dot), the same rule as [DeftWallpaper]. [sizes] are the grid sizes the
 * page adapts to, from [DeftManifestParser.WIDGET_SIZES].
 */
data class DeftWidget(val page: String, val sizes: List<String>)

/**
 * Manifest `handles`. [mimeTypes] are `type/subtype` or a whole type (`image/` + star), never star/star,
 * [schemes] `web+name` or `mailto`, [links] https origins. Only the launcher's fixed aliases reach
 * an app (see `ui/handlers/HandlerAlias`): a declared `web+foo` other than `web+deft` and every
 * `links` entry are accepted but inert, because Android intent filters are static.
 */
data class DeftHandles(
    val mimeTypes: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
    val links: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = mimeTypes.isEmpty() && schemes.isEmpty() && links.isEmpty()
}

/** A launcher shortcut: long-press the app icon, pick it, the app opens at [url]. */
data class DeftShortcut(val id: String, val name: String, val url: String)

object DeftManifestParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val slug = Regex("^[a-z0-9-]{2,40}$")
    private val origin = Regex("^https://[a-z0-9.-]+(?::[0-9]{1,5})?$")

    /**
     * A public https origin: a dotted hostname with non-empty labels, optional port. No IP
     * literals, no localhost, no `*.deft.internal` app origins. Kept identical to deft-lint FL704
     * (`validNetworkOrigin` in tools/deft-lint/src/index.js).
     */
    fun validNetworkOrigin(value: String): Boolean {
        if (!origin.matches(value)) return false
        val host = value.removePrefix("https://").substringBefore(':')
        if (!host.contains('.') || host.split('.').any { it.isEmpty() }) return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host == "deft.internal" || host.endsWith(".deft.internal")) return false
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)) return false
        return true
    }

    const val MAX_EMBEDS = 16

    /**
     * A manifest `embeds` entry. Same rule as [validNetworkOrigin], plus `*.` allowed as
     * the whole leftmost label (`*.paypal.com`) with at least two labels after it that are not a
     * public suffix ([EmbedSuffixes]: no `*.github.io`, `*.co.uk`). Kept identical to deft-lint
     * FL712 (`validEmbedOrigin` in tools/deft-lint/src/index.js).
     */
    fun validEmbedOrigin(value: String): Boolean {
        val rest = value.removePrefix("https://")
        if (rest == value) return false
        val wildcard = rest.startsWith("*.")
        val plain = if (wildcard) "https://" + rest.removePrefix("*.") else value
        if (plain.contains('*') || !validNetworkOrigin(plain)) return false
        if (wildcard && EmbedSuffixes.publicSuffix(plain.removePrefix("https://").substringBefore(':'))) return false
        return true
    }

    /** `embeds`, an array of at most [MAX_EMBEDS] origins; duplicates are kept once. */
    fun parseEmbeds(element: kotlinx.serialization.json.JsonElement?): List<String> {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return emptyList()
        val list = element as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("embeds must be an array of https origins")
        if (list.size > MAX_EMBEDS) throw IllegalArgumentException("at most $MAX_EMBEDS embeds")
        return list.map { el ->
            val value = (el as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("embeds must be an array of https origins")
            if (!validEmbedOrigin(value)) throw IllegalArgumentException("invalid embed origin '$value'")
            value
        }.distinct()
    }

    fun parse(raw: String): DeftManifest {
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("manifest.json must be an object")
        if (root.containsKey("requiredCapabilities")) {
            throw IllegalArgumentException(
                "requiredCapabilities is rejected. Use capabilities: [{id, reason}] with ids from the bridge catalog.",
            )
        }
        val version1 = root["deft"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("manifest.json is missing deft (must be 1)")
        if (version1 != 1) throw IllegalArgumentException("unsupported deft version $version1")
        val id = root.str("id")
        if (!slug.matches(id)) throw IllegalArgumentException("id must be 2-40 chars [a-z0-9-]")
        val name = root.str("name")
        if (name.isBlank() || name.length > 30) throw IllegalArgumentException("name must be 1-30 chars")
        val version = root["version"]?.jsonPrimitive?.content ?: "0.0.0"
        val profile = root["profile"]?.jsonPrimitive?.content ?: "myapp"
        if (profile != "myapp" && profile != "canvas") {
            throw IllegalArgumentException("profile must be myapp or canvas")
        }
        val startUrl = root["start_url"]?.jsonPrimitive?.content ?: "./index.html"
        val caps = mutableListOf<Pair<Capability, String>>()
        val capEl = root["capabilities"]
        if (capEl != null) {
            for (item in capEl.jsonArray) {
                val obj = item as? JsonObject ?: throw IllegalArgumentException("capability must be {id, reason}")
                val capId = obj["id"]?.jsonPrimitive?.content.orEmpty()
                val reason = obj["reason"]?.jsonPrimitive?.content.orEmpty()
                val cap = Capability.fromId(capId)
                    ?: throw IllegalArgumentException("unknown capability '$capId'")
                if (reason.length !in 10..140) {
                    throw IllegalArgumentException("capability reason for $capId must be 10-140 chars")
                }
                caps += cap to reason
            }
        }
        val network = root["network"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        for (n in network) {
            if (!validNetworkOrigin(n)) throw IllegalArgumentException("invalid network origin '$n'")
        }
        if (profile == "canvas" && (caps.isNotEmpty() || network.isNotEmpty())) {
            throw IllegalArgumentException("canvas forbids capabilities and network")
        }
        val embeds = parseEmbeds(root["embeds"])
        if (profile == "canvas" && embeds.isNotEmpty()) {
            throw IllegalArgumentException("canvas forbids embeds")
        }
        val dependencies = parseDependencies(root["dependencies"])
        if (profile == "canvas" && dependencies.isNotEmpty()) {
            throw IllegalArgumentException("canvas forbids dependencies")
        }
        val shortcuts = parseShortcuts(root["shortcuts"])
        val background = parseBackground(root["background"])
        if (profile == "canvas" && background != null) {
            throw IllegalArgumentException("canvas forbids background")
        }
        val handles = parseHandles(root["handles"])
        if (profile == "canvas" && handles != null) {
            throw IllegalArgumentException("canvas forbids handles")
        }
        val wallpaper = parseWallpaper(root["wallpaper"])
        if (profile == "canvas" && wallpaper != null) {
            throw IllegalArgumentException("canvas forbids wallpaper")
        }
        val keyboard = parseKeyboard(root["keyboard"])
        if (profile == "canvas" && keyboard != null) {
            throw IllegalArgumentException("canvas forbids keyboard")
        }
        val widget = parseWidget(root["widget"])
        if (profile == "canvas" && widget != null) {
            throw IllegalArgumentException("canvas forbids widget")
        }
        val tools = DeftToolsParser.parse(root["tools"])
        if (profile == "canvas" && tools.isNotEmpty()) {
            throw IllegalArgumentException("canvas forbids tools")
        }
        val passkeyRpIds = app.deft.bridge.PasskeyPolicy.parseManifest(root["passkeys"]).orEmpty()
        if (profile == "canvas" && passkeyRpIds.isNotEmpty()) {
            throw IllegalArgumentException("canvas forbids passkeys")
        }
        val assetPacks = parseAssetPacks(root["assetPacks"])
        if (profile == "canvas" && assetPacks.isNotEmpty()) {
            throw IllegalArgumentException("canvas forbids assetPacks")
        }
        return DeftManifest(
            deft = version1,
            id = id,
            name = name,
            version = version,
            profile = profile,
            startUrl = startUrl,
            capabilities = caps,
            network = network,
            embeds = embeds,
            dependencies = dependencies,
            shortcuts = shortcuts,
            background = background,
            handles = handles,
            wallpaper = wallpaper,
            keyboard = keyboard,
            widget = widget,
            tools = tools,
            passkeyRpIds = passkeyRpIds,
            assetPacks = assetPacks,
            license = root["license"]?.jsonPrimitive?.content,
            source = root["source"]?.jsonPrimitive?.content,
        )
    }

    /** Trigger names a manifest may declare, besides `periodic:N`. */
    val BACKGROUND_TRIGGERS = setOf("alarms", "sms", "calls", "notify.action", "boot", "network", "charging", "push", "geofence", "sync", "notification", "screening")
    const val MIN_PERIODIC_MINUTES = 15
    const val MAX_PERIODIC_MINUTES = 10_080
    private val periodic = Regex("^periodic:([0-9]{1,5})$")
    private val modulePath = Regex("^\\.?/[A-Za-z0-9._~/-]+\\.m?js$")

    /** Host paths an app may not claim: the runtime `/_deft/`. */
    internal fun reservedPath(absolute: String): Boolean = absolute.lowercase().startsWith("/_deft/")

    /**
     * `background.module`, a same-origin script path such as `./bg.js`: starts with
     * `./` or `/`, only `[A-Za-z0-9._~/-]`, ends in `.js` or `.mjs`, no `..`, `//` or `/_deft/`.
     * Kept identical to deft-lint FL713 (`validBackgroundModule` in tools/deft-lint/src/index.js).
     */
    fun validBackgroundModule(path: String): Boolean = normalizeModulePath(path) != null

    /**
     * The absolute, dot-segment-free form of a valid module path (`./a/./b.js` and `/./a/b.js` both give
     * `/a/b.js`), or null when [path] is not valid. The `/_deft/` check runs on this normalised form, so
     * `/./_deft/x.js` cannot slip through. `%` is not in the allowed character set, so encoded forms
     * (`%2e%2e`, `%5f`) are refused. Kept identical to deft-lint `normalizeModulePath`.
     */
    fun normalizeModulePath(path: String): String? {
        if (path.length > 256 || !modulePath.matches(path)) return null
        if (path.contains("..") || path.contains("//")) return null
        val absolute = path.removePrefix(".")
        val normalized = "/" + absolute.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (reservedPath(normalized) || !normalized.matches(modulePath)) return null
        return normalized
    }

    /** One of [BACKGROUND_TRIGGERS] or `periodic:N` with N from 15 to 10080 minutes. */
    fun validBackgroundTrigger(value: String): Boolean {
        if (value in BACKGROUND_TRIGGERS) return true
        val minutes = periodic.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return minutes in MIN_PERIODIC_MINUTES..MAX_PERIODIC_MINUTES
    }

    /** Kept identical to deft-lint FL713 (`scanBackground` in tools/deft-lint/src/index.js). */
    fun parseBackground(element: kotlinx.serialization.json.JsonElement?): DeftBackground? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("background must be {module, triggers}")
        val module = (obj["module"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("background.module must be a path like ./bg.js")
        if (!validBackgroundModule(module)) {
            throw IllegalArgumentException("background.module must be a same-origin .js path like ./bg.js")
        }
        val list = obj["triggers"] as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("background.triggers must be an array")
        val triggers = list.map { el ->
            val value = (el as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("background.triggers must be an array of names")
            if (!validBackgroundTrigger(value)) {
                throw IllegalArgumentException(
                    "unknown background trigger '$value' (periodic needs at least $MIN_PERIODIC_MINUTES minutes)",
                )
            }
            value
        }.distinct()
        if (triggers.count { it.startsWith("periodic:") } > 1) {
            throw IllegalArgumentException("background.triggers may hold one periodic entry")
        }
        return DeftBackground(module.removePrefix("."), triggers)
    }

    private val pagePath = Regex("^\\.?/[A-Za-z0-9._~/-]+\\.html?$")

    /**
     * `wallpaper.page`, a same-origin page path such as `./wallpaper.html`: the same
     * rules as [validBackgroundModule], ending in `.html` or `.htm`.
     * Kept identical to deft-lint FL715 (`validWallpaperPage` in tools/deft-lint/src/index.js).
     */
    fun validWallpaperPage(path: String): Boolean {
        if (path.length > 256 || !pagePath.matches(path)) return false
        if (path.contains("..") || path.contains("//")) return false
        val absolute = path.removePrefix(".")
        return !reservedPath(absolute)
    }

    /** Kept identical to deft-lint FL715 (`scanWallpaper` in tools/deft-lint/src/index.js). */
    fun parseWallpaper(element: kotlinx.serialization.json.JsonElement?): DeftWallpaper? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("wallpaper must be {page}")
        val page = (obj["page"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("wallpaper.page must be a path like ./wallpaper.html")
        if (!validWallpaperPage(page)) {
            throw IllegalArgumentException("wallpaper.page must be a same-origin .html path like ./wallpaper.html")
        }
        return DeftWallpaper(page.removePrefix("."))
    }

    /**
     * `keyboard.page`, the page the launcher's keyboard (role `ime`) shows; the same path
     * rule as [validWallpaperPage]. Kept identical to deft-lint FL716 (`scanKeyboard` in tools/deft-lint/src/index.js).
     */
    fun parseKeyboard(element: kotlinx.serialization.json.JsonElement?): DeftKeyboard? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("keyboard must be {page}")
        val page = (obj["page"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("keyboard.page must be a path like ./keyboard/index.html")
        if (!validWallpaperPage(page)) {
            throw IllegalArgumentException("keyboard.page must be a same-origin .html path like ./keyboard/index.html")
        }
        return DeftKeyboard(page.removePrefix("."))
    }

    /** The grid sizes a widget may declare (columns x rows). */
    val WIDGET_SIZES = setOf("1x1", "2x1", "2x2", "4x1", "4x2")

    /**
     * `widget {page, sizes}`, the same page rule as [parseWallpaper] and a non-empty array of
     * [WIDGET_SIZES]. Kept identical to deft-lint FL717 (`scanWidget` in tools/deft-lint/src/index.js).
     */
    fun parseWidget(element: kotlinx.serialization.json.JsonElement?): DeftWidget? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("widget must be {page, sizes}")
        val page = (obj["page"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("widget.page must be a path like ./widget.html")
        if (!validWallpaperPage(page)) {
            throw IllegalArgumentException("widget.page must be a same-origin .html path like ./widget.html")
        }
        val list = obj["sizes"] as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("widget.sizes must be an array")
        val sizes = list.map { el ->
            val value = (el as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("widget.sizes must be an array of strings")
            if (value !in WIDGET_SIZES) throw IllegalArgumentException("unknown widget size '$value' (one of ${WIDGET_SIZES.joinToString()})")
            value
        }.distinct()
        if (sizes.isEmpty()) throw IllegalArgumentException("widget.sizes must not be empty")
        return DeftWidget(page.removePrefix("."), sizes)
    }

    const val MAX_HANDLES = 16
    private val handleMime = Regex("^[a-z0-9][a-z0-9.+-]{0,62}/(\\*|[a-z0-9][a-z0-9.+-]{0,126})$")
    private val handleScheme = Regex("^web\\+[a-z]{1,32}$")

    /** `type/subtype` or `type/` + star, lowercase; star/star is refused. Kept identical to deft-lint FL714. */
    fun validHandleMime(value: String): Boolean = handleMime.matches(value)

    /** `web+name` (lowercase letters) or `mailto`. Kept identical to deft-lint FL714. */
    fun validHandleScheme(value: String): Boolean = value == "mailto" || handleScheme.matches(value)

    /**
     * `handles {mimeTypes?, schemes?, links?}`, each an array of at most [MAX_HANDLES]
     * strings; duplicates are kept once. An object with no entries is null. Kept identical to
     * deft-lint FL714 (`scanHandles` in tools/deft-lint/src/index.js).
     */
    fun parseHandles(element: kotlinx.serialization.json.JsonElement?): DeftHandles? {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("handles must be {mimeTypes?, schemes?, links?}")
        fun list(key: String, valid: (String) -> Boolean, what: String): List<String> {
            val el = obj[key]
            if (el == null || el is kotlinx.serialization.json.JsonNull) return emptyList()
            val arr = el as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalArgumentException("handles.$key must be an array")
            if (arr.size > MAX_HANDLES) throw IllegalArgumentException("at most $MAX_HANDLES handles.$key")
            return arr.map { item ->
                val value = (item as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("handles.$key must be an array of strings")
                if (!valid(value)) throw IllegalArgumentException("invalid handles.$key entry '$value' ($what)")
                value
            }.distinct()
        }
        val handles = DeftHandles(
            mimeTypes = list("mimeTypes", ::validHandleMime, "type/subtype or type/*, lowercase, not */*"),
            schemes = list("schemes", ::validHandleScheme, "web+name or mailto"),
            links = list("links", ::validNetworkOrigin, "an https origin"),
        )
        return handles.takeUnless { it.isEmpty() }
    }

    /** Kept identical to deft-lint FL708 (`validShortcut` in tools/deft-lint/src/index.js). */
    fun parseShortcuts(element: kotlinx.serialization.json.JsonElement?): List<DeftShortcut> {
        if (element == null) return emptyList()
        val list = element as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("shortcuts must be an array")
        if (list.size > 8) throw IllegalArgumentException("at most 8 shortcuts")
        return list.map { el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("each shortcut must be {id, name, url}")
            val id = o["id"]?.jsonPrimitive?.content.orEmpty()
            val name = o["name"]?.jsonPrimitive?.content.orEmpty()
            val url = o["url"]?.jsonPrimitive?.content.orEmpty()
            if (!Regex("^[a-z0-9-]{1,32}$").matches(id)) throw IllegalArgumentException("shortcut id must be 1-32 chars [a-z0-9-]")
            if (name.isBlank() || name.length > 30) throw IllegalArgumentException("shortcut name must be 1-30 chars")
            if (!validShortcutUrl(url)) throw IllegalArgumentException("shortcut url must be a same-origin path like ./#new")
            DeftShortcut(id, name.trim(), url)
        }
    }

    fun validShortcutUrl(url: String): Boolean {
        if (url.length > 512) return false
        if (!(url.startsWith("./") || url.startsWith("/"))) return false
        if (url.startsWith("//") || url.contains("..") || url.contains('\\') || url.any { it.isWhitespace() }) return false
        return !reservedPath(url)
    }

    private val depUrl = Regex("^https://[a-z0-9.-]+\\.[a-z]{2,}(?::[0-9]{1,5})?/[A-Za-z0-9@._~+/-]+$")
    private val sri = Regex("^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$")
    private val alias = Regex("^(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*$")

    /** At most this many packs per app. */
    const val MAX_ASSET_PACKS = 8
    private val packName = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val packSha = Regex("^[0-9a-f]{64}$")

    /**
     * `assetPacks: [{name, url, sha256, size}]`. Same URL rule as
     * dependencies, ending in `.zip`; `sha256` is the lowercase hex of the zip; `size` its byte
     * count, at most the `storage.large` package cap. Names are unique. Deft-lint FL720 mirrors this.
     */
    fun parseAssetPacks(element: kotlinx.serialization.json.JsonElement?): List<DeftAssetPack> {
        if (element == null || element is kotlinx.serialization.json.JsonNull) return emptyList()
        val list = element as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("assetPacks must be an array of {name, url, sha256, size}")
        if (list.size > MAX_ASSET_PACKS) throw IllegalArgumentException("at most $MAX_ASSET_PACKS asset packs")
        val seen = mutableSetOf<String>()
        return list.map { el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("each asset pack must be {name, url, sha256, size}")
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val url = (o["url"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val sha = (o["sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val size = (o["size"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            if (!packName.matches(name)) throw IllegalArgumentException("asset pack name must be 1-32 chars [a-z0-9-]")
            if (!seen.add(name)) throw IllegalArgumentException("asset pack $name is listed twice")
            if (url.length > 2048 || !depUrl.matches(url) || url.contains("..") || url.indexOf("//", 8) >= 0 || !url.endsWith(".zip")) {
                throw IllegalArgumentException("asset pack $name url must be an https .zip URL without query or fragment")
            }
            if (!packSha.matches(sha)) throw IllegalArgumentException("asset pack $name sha256 must be 64 lowercase hex characters")
            if (size == null || size < 1 || size > app.deft.workspace.DeftPaths.LARGE_PACKAGE_MAX_BYTES) {
                throw IllegalArgumentException("asset pack $name size must be 1 to ${app.deft.workspace.DeftPaths.LARGE_PACKAGE_MAX_BYTES} bytes")
            }
            DeftAssetPack(name, url, sha, size)
        }
    }

    fun parseDependencies(element: kotlinx.serialization.json.JsonElement?): List<DeftDependency> {
        if (element == null) return emptyList()
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("dependencies must be an object of https URL -> SRI hash")
        return obj.entries.sortedBy { it.key }.map { (url, value) ->
            if (url.length > 2048 || !depUrl.matches(url) || url.contains("..") || url.indexOf("//", 8) >= 0) {
                throw IllegalArgumentException("dependency '$url' must be an https file URL without query or fragment")
            }
            val last = url.substringAfterLast('/')
            val ext = last.substringAfterLast('.', "").lowercase()
            // Only a letters-only suffix is an extension: `three@0.160.0` is extensionless JS.
            // A .wasm dependency is allowed; deft-lint requires its hash and the wasm capability.
            if (ext.matches(Regex("[a-z]+")) && ext !in setOf("js", "mjs", "json", "wasm")) {
                throw IllegalArgumentException("dependency $url must be a JavaScript module (.js, .mjs), .json or .wasm")
            }
            val spec = value as? JsonObject
            val integrity = when {
                spec != null -> spec["integrity"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullCompat() }
                value is kotlinx.serialization.json.JsonNull -> null
                else -> value.jsonPrimitive.content
            }
            if (integrity != null && !sri.matches(integrity)) {
                throw IllegalArgumentException("dependency $url hash must be sha256-, sha384- or sha512-<base64>")
            }
            val name = spec?.get("as")?.jsonPrimitive?.content
            if (name != null && (name.length > 214 || !alias.matches(name))) {
                throw IllegalArgumentException("dependency alias '$name' must be a bare package name")
            }
            DeftDependency(url, integrity, name)
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullCompat(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("manifest.json is missing $key")
}
