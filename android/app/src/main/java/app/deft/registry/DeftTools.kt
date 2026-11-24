package app.deft.registry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest

/**
 * W5: one agent tool an app declares in its manifest `tools`. [handlerPath] is an absolute same-origin
 * script path ("/tools.js"; "./tools.js" in the manifest is stored without the dot) and [fn] the exported
 * function (a global function for an [isolate] handler, which is a plain script, not a module).
 */
data class DeftTool(
    val name: String,
    val description: String,
    val params: JsonObject,
    val handlerPath: String,
    val fn: String,
    val isolate: Boolean,
    /**
     * Hash of the code the tool runs (see [DeftToolCode]); empty only where no files are read (the parser, tests).
     * Not part of the manifest: the repository fills it in with [withCode].
     */
    val codeHash: String = "",
) {
    fun withCode(hash: String): DeftTool = copy(codeHash = hash)

    /**
     * Identifies exactly what the user consented to (name, description, schema, handler, mode and the handler's
     * code). Any change to one of them is a new fingerprint, so an edited tool, or a handler file the agent rewrote
     * after consent, is asked again.
     */
    fun fingerprint(): String {
        val text = listOf(name, description, params.toString(), "$handlerPath#$fn", isolate.toString(), codeHash).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** The key a consent row stores: `name@fingerprint`. */
    fun consentKey(): String = "$name@${fingerprint()}"
}

/**
 * W5: parsing and validation of manifest `tools` and of the arguments the model passes. Pure and unit tested.
 * Kept identical to deft-lint FL718 (`scanTools` in tools/deft-lint/src/index.js).
 */
object DeftToolsParser {
    const val MAX_TOOLS_PER_APP = 8
    const val MAX_DESCRIPTION = 300
    const val MAX_PROPERTIES = 12
    const val MAX_SCHEMA_BYTES = 2048
    const val MAX_ENUM = 24
    private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val PROP = Regex("^[A-Za-z_][A-Za-z0-9_]{0,31}$")
    private val FN = Regex("^[A-Za-z_$][A-Za-z0-9_$]{0,63}$")
    private val PRIMITIVES = setOf("string", "number", "integer", "boolean")
    private val PROP_KEYS = setOf("type", "description", "enum", "items", "minimum", "maximum", "maxLength")

    fun validName(value: String): Boolean = NAME.matches(value)

    /** `./tools.js#fn`: a same-origin script path (the background-module rule) and an identifier. */
    fun parseHandlerRef(value: String): Pair<String, String>? {
        val at = value.indexOf('#')
        if (at <= 0) return null
        val path = value.substring(0, at)
        val fn = value.substring(at + 1)
        val normalized = DeftManifestParser.normalizeModulePath(path)
        if (normalized == null || !FN.matches(fn)) return null
        return normalized to fn
    }

    fun parse(element: JsonElement?): List<DeftTool> {
        if (element == null || element is JsonNull) return emptyList()
        val list = element as? JsonArray
            ?: throw IllegalArgumentException("tools must be an array of {name, description, params, handler}")
        if (list.size > MAX_TOOLS_PER_APP) throw IllegalArgumentException("at most $MAX_TOOLS_PER_APP tools per app")
        val seen = HashSet<String>()
        return list.map { el ->
            val obj = el as? JsonObject
                ?: throw IllegalArgumentException("each tool must be {name, description, params, handler}")
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (!validName(name)) {
                throw IllegalArgumentException("tool name '$name' must match [a-z][a-z0-9_]{0,31}")
            }
            if (!seen.add(name)) throw IllegalArgumentException("duplicate tool '$name'")
            val description = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().trim()
            if (description.isEmpty() || description.length > MAX_DESCRIPTION) {
                throw IllegalArgumentException("tool '$name' description must be 1-$MAX_DESCRIPTION characters")
            }
            val params = (obj["params"] as? JsonObject)
                ?: if (obj["params"] == null) emptyParams() else throw IllegalArgumentException("tool '$name' params must be a JSON schema object")
            schemaError(params)?.let { throw IllegalArgumentException("tool '$name' params: $it") }
            val handler = obj["handler"]
            val isolate: Boolean
            val ref: String
            when (handler) {
                is JsonPrimitive -> {
                    if (!handler.isString) throw IllegalArgumentException("tool '$name' handler must be \"./tools.js#fn\"")
                    isolate = false
                    ref = handler.content
                }
                is JsonObject -> {
                    if (handler.keys != setOf("isolate")) {
                        throw IllegalArgumentException("tool '$name' handler object must be {\"isolate\": \"./tools.js#fn\"}")
                    }
                    isolate = true
                    ref = (handler["isolate"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                }
                else -> throw IllegalArgumentException("tool '$name' handler must be \"./tools.js#fn\" or {\"isolate\": \"./tools.js#fn\"}")
            }
            val (path, fn) = parseHandlerRef(ref)
                ?: throw IllegalArgumentException("tool '$name' handler must be a same-origin .js path and a function, like ./tools.js#run")
            DeftTool(name, description, params, path, fn, isolate)
        }
    }

    fun emptyParams(): JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())),
    )

    /**
     * Why [schema] is outside the supported subset, or null. The subset: an object schema with up to
     * [MAX_PROPERTIES] properties (string, number, integer, boolean, or an array of one of those), each with
     * optional description, enum, minimum, maximum and maxLength, and `required` naming existing properties.
     */
    fun schemaError(schema: JsonObject): String? {
        if (schema.toString().length > MAX_SCHEMA_BYTES) return "schema is larger than $MAX_SCHEMA_BYTES bytes"
        for (key in schema.keys) {
            if (key !in setOf("type", "properties", "required", "description")) return "unsupported schema keyword '$key'"
        }
        if ((schema["type"] as? JsonPrimitive)?.content != "object") return "type must be \"object\""
        val props = (schema["properties"] as? JsonObject) ?: if (schema["properties"] == null) JsonObject(emptyMap()) else return "properties must be an object"
        if (props.size > MAX_PROPERTIES) return "at most $MAX_PROPERTIES properties"
        for ((name, def) in props) {
            if (!PROP.matches(name)) return "property name '$name' must match [A-Za-z_][A-Za-z0-9_]{0,31}"
            val prop = def as? JsonObject ?: return "property '$name' must be an object"
            propertyError(name, prop)?.let { return it }
        }
        val required = schema["required"]
        if (required != null) {
            val arr = required as? JsonArray ?: return "required must be an array"
            for (item in arr) {
                val key = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (key == null || key !in props) return "required names an unknown property '${(item as? JsonPrimitive)?.content}'"
            }
        }
        return null
    }

    private fun propertyError(name: String, prop: JsonObject): String? {
        for (key in prop.keys) if (key !in PROP_KEYS) return "property '$name': unsupported keyword '$key'"
        val type = (prop["type"] as? JsonPrimitive)?.content
        if (type == null) return "property '$name' needs a type"
        if (type == "array") {
            val items = prop["items"] as? JsonObject ?: return "property '$name': array needs items {type}"
            val itemType = (items["type"] as? JsonPrimitive)?.content
            if (items.keys != setOf("type") || itemType !in PRIMITIVES) {
                return "property '$name': items must be {type: string|number|integer|boolean}"
            }
        } else if (type !in PRIMITIVES) {
            return "property '$name': type must be string, number, integer, boolean or array"
        }
        prop["description"]?.let {
            if ((it as? JsonPrimitive)?.isString != true || (it as JsonPrimitive).content.length > 200) {
                return "property '$name': description must be a string of at most 200 characters"
            }
        }
        prop["enum"]?.let { en ->
            if (type !in PRIMITIVES) return "property '$name': enum only on string, number, integer or boolean"
            val arr = en as? JsonArray ?: return "property '$name': enum must be an array"
            if (arr.isEmpty() || arr.size > MAX_ENUM) return "property '$name': enum needs 1-$MAX_ENUM values"
            for (v in arr) if (!matchesType(type, v)) return "property '$name': enum value $v is not a $type"
        }
        for (bound in listOf("minimum", "maximum")) {
            prop[bound]?.let {
                if (type != "number" && type != "integer") return "property '$name': $bound only on number or integer"
                if ((it as? JsonPrimitive)?.doubleOrNull == null || (it as JsonPrimitive).isString) return "property '$name': $bound must be a number"
            }
        }
        prop["maxLength"]?.let {
            if (type != "string") return "property '$name': maxLength only on string"
            val n = (it as? JsonPrimitive)?.doubleOrNull
            if (n == null || n < 1 || n != Math.floor(n)) return "property '$name': maxLength must be a positive integer"
        }
        return null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean {
        val p = value as? JsonPrimitive ?: return false
        return when (type) {
            "string" -> p.isString
            "boolean" -> !p.isString && p.booleanOrNull != null
            "number" -> !p.isString && p.doubleOrNull != null
            "integer" -> !p.isString && p.doubleOrNull?.let { it == Math.floor(it) && !it.isInfinite() } == true
            else -> false
        }
    }

    /**
     * Why the model's [args] do not fit [schema] (already checked by [schemaError]), or null. Missing required
     * properties, unknown properties, wrong types, values outside enum/minimum/maximum/maxLength are all refused,
     * with a message the model can act on.
     */
    fun argsError(schema: JsonObject, args: JsonObject): String? {
        val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        for (key in required) if (args[key] == null || args[key] is JsonNull) return "missing required argument '$key'"
        for ((key, value) in args) {
            val prop = props[key] as? JsonObject ?: return "unknown argument '$key' (allowed: ${props.keys.joinToString().ifBlank { "none" }})"
            if (value is JsonNull) continue
            valueError(key, prop, value)?.let { return it }
        }
        return null
    }

    private fun valueError(key: String, prop: JsonObject, value: JsonElement): String? {
        val type = (prop["type"] as? JsonPrimitive)?.content.orEmpty()
        if (type == "array") {
            val arr = value as? JsonArray ?: return "argument '$key' must be an array"
            if (arr.size > MAX_ARRAY_ITEMS) return "argument '$key' has more than $MAX_ARRAY_ITEMS items"
            val itemType = ((prop["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content.orEmpty()
            for (item in arr) if (!matchesType(itemType, item)) return "argument '$key' must be an array of $itemType"
            return null
        }
        if (!matchesType(type, value)) return "argument '$key' must be a $type"
        val p = value as JsonPrimitive
        (prop["enum"] as? JsonArray)?.let { en ->
            if (en.none { (it as? JsonPrimitive)?.content == p.content }) {
                return "argument '$key' must be one of ${en.joinToString { (it as JsonPrimitive).content }}"
            }
        }
        val number = p.doubleOrNull
        if (number != null && !p.isString) {
            (prop["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number < it) return "argument '$key' must be at least ${prop["minimum"]}" }
            (prop["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { if (number > it) return "argument '$key' must be at most ${prop["maximum"]}" }
        }
        if (p.isString) {
            val max = (prop["maxLength"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: MAX_STRING
            if (p.content.length > max) return "argument '$key' is longer than $max characters"
        }
        return null
    }

    const val MAX_ARRAY_ITEMS = 100
    const val MAX_STRING = 8192
}

/**
 * W5: the code a tool's handler can run, hashed into its consent fingerprint so a silent swap (the agent writes
 * `tools.js` after the user allowed the tool) cannot keep the old consent. An isolate handler is one plain script, so
 * its file is the code. A page handler is a module that may import its siblings, so every script of the app counts.
 * Scripts that come from `/_deft/deps/` are not read here; the manifest `dependencies` (url, integrity hash, alias) are folded into the hash instead, so a changed pin is asked again.
 */
object DeftToolCode {
    private val SCRIPT = Regex(".*\\.(js|mjs|cjs)$", RegexOption.IGNORE_CASE)

    /** [files] is the merged app tree (path to file). A missing handler file hashes as such, so creating it later changes the key. */
    fun hash(tool: DeftTool, files: Map<String, java.io.File>, dependencies: List<DeftDependency> = emptyList()): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // The manifest's hash-pinned CDN dependencies are code the handler can import: a changed pin is a new consent (an isolate handler has no imports, so it ignores them).
        if (!tool.isolate) for (dep in dependencies.sortedBy { it.url }) digest.update("dep:${dep.url}|${dep.integrity.orEmpty()}|${dep.alias.orEmpty()}\n".toByteArray())
        val handler = tool.handlerPath.trimStart('/')
        val picked = if (tool.isolate) files.filterKeys { it == handler } else files.filterKeys { it == handler || SCRIPT.matches(it) }
        digest.update("handler:$handler:${handler in files}\n".toByteArray())
        for ((rel, file) in picked.toSortedMap()) {
            digest.update("$rel\n".toByteArray())
            runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
            }
            digest.update(0)
        }
        return digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }
}
