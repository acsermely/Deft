package app.deft.config

import android.content.Context
import android.content.res.Configuration
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.deft.device.DeviceTierStore
import app.deft.workspace.DeftPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigStore(private val context: Context) {
    private val _config = MutableStateFlow(defaults())
    val config: StateFlow<DeftConfig> = _config.asStateFlow()

    val root: File get() = File(context.filesDir, "config")

    private var observer: FileObserver? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        ensureUserTree()
        reload()
        watch()
    }

    fun reload() {
        val next = load()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _config.value = next
        } else {
            main.post { _config.value = next }
        }
    }

    fun listFiles(): List<String> {
        ensureUserTree()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "toml" }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()
    }

    fun read(relative: String): String {
        val file = resolve(relative)
        if (!file.isFile) throw IllegalArgumentException("missing config file: $relative")
        return file.readText()
    }

    fun write(relative: String, content: String) {
        val file = resolve(relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        reload()
    }

    fun reset(relative: String? = null) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backup = File(context.filesDir, "config.bak.$stamp")
        if (root.exists()) {
            root.copyRecursively(backup, overwrite = true)
        }
        if (relative == null) {
            root.deleteRecursively()
            copyAssets("config", root)
        } else {
            val dest = resolve(relative)
            val assetPath = "config/$relative"
            val bytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }
                .getOrElse { throw IllegalArgumentException("no default for $relative") }
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        }
        watch()
        reload()
    }

    /** Choices for the theme picker and theme.list: accent presets, then wallpaper. */
    fun themeChoices(): List<String> = ThemeSettings.PRESETS.keys.toList() + ThemeSettings.WALLPAPER

    /** Sets the accent (preset name, wallpaper, #hex or oklch) and optionally the appearance. */
    fun setTheme(accent: String, appearance: String? = null) {
        val chosenAppearance = appearance?.trim()?.lowercase()
        if (chosenAppearance != null && chosenAppearance !in ThemeSettings.APPEARANCES) {
            throw IllegalArgumentException("appearance must be one of ${ThemeSettings.APPEARANCES}")
        }
        val current = ThemeSettings.fromMap(readMap("theme.toml"))
        writeTheme(current.withChoice(accent, chosenAppearance))
        reload()
    }

    /** Settings switch "Reduce transparency" (8.4): forces the opaque material on any tier. */
    fun setReduceTransparency(on: Boolean) {
        writeTheme(ThemeSettings.fromMap(readMap("theme.toml")).copy(reduceTransparency = on))
        reload()
    }

    fun setMonochromeIcons(on: Boolean) {
        writeTheme(ThemeSettings.fromMap(readMap("theme.toml")).copy(monochromeIcons = on))
        reload()
    }

    private fun writeTheme(settings: ThemeSettings) {
        // Write-then-rename so a crash never leaves a half-written theme.toml (its presence ends the migration).
        val target = File(root, "theme.toml")
        val tmp = File(root, "theme.toml.tmp")
        tmp.writeText(
            Toml.encode(
                listOf(
                    "accent" to settings.accent,
                    "appearance" to settings.appearance,
                    "reduceTransparency" to settings.reduceTransparency,
                    "monochromeIcons" to settings.monochromeIcons,
                ),
            ),
        )
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun resolve(relative: String): File {
        val clean = relative.trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..") || File(clean).isAbsolute) {
            throw IllegalArgumentException("invalid path")
        }
        if (!clean.endsWith(".toml") && clean != "theme.name" && !clean.endsWith(".json")) {
            throw IllegalArgumentException("only .toml, palette.json, or theme.name allowed")
        }
        val file = File(root, clean).canonicalFile
        if (!file.path.startsWith(root.canonicalFile.path)) {
            throw IllegalArgumentException("path escapes config root")
        }
        return file
    }

    private fun load(): DeftConfig {
        ensureUserTree()
        val settings = ThemeSettings.fromMap(readMap("theme.toml"))
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val effectiveDark = when (settings.appearance) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }
        val wallpaper = if (settings.accent == ThemeSettings.WALLPAPER) Palette.wallpaperAccent(context, effectiveDark) else null
        val tier = DeviceTierStore.tier(context)
        val resolved = Palette.resolve(settings, wallpaper, systemDark, tier)
        Palette.publish(resolved)
        val shell = parseShell(readMap("shell.toml"))
        val home = parseHome(readMap("home.toml"))
        val agent = parseAgent(readMap("agent.toml"))
        val limits = parseLimits(readMap("limits.toml"))
        DeftPaths.active = limits
        return DeftConfig(Palette.colors(resolved), shell, home, agent, settings, limits, resolved.opaqueMaterial)
    }

    private fun readMap(relative: String): Map<String, String> {
        val file = File(root, relative)
        if (!file.isFile) return emptyMap()
        return Toml.parse(file.readText())
    }

    private fun ensureUserTree() {
        if (!root.exists()) {
            copyAssets("config", root)
        } else {
            migrateLegacyTheme()
            seedMissing("config", root)
        }
    }

    /** Copies asset files that a newer launcher added, without replacing files the user edited. */
    private fun seedMissing(assetDir: String, dest: File) {
        dest.mkdirs()
        val names = context.assets.list(assetDir) ?: return
        for (name in names) {
            val path = "$assetDir/$name"
            val children = context.assets.list(path)
            val target = File(dest, name)
            if (children != null && children.isNotEmpty()) {
                seedMissing(path, target)
            } else if (!target.exists()) {
                target.outputStream().use { out ->
                    context.assets.open(path).use { it.copyTo(out) }
                }
            }
        }
    }

    private fun copyAssets(assetDir: String, dest: File) {
        dest.mkdirs()
        val names = context.assets.list(assetDir) ?: return
        for (name in names) {
            val path = "$assetDir/$name"
            val children = context.assets.list(path)
            if (children != null && children.isNotEmpty()) {
                copyAssets(path, File(dest, name))
            } else {
                File(dest, name).outputStream().use { out ->
                    context.assets.open(path).use { it.copyTo(out) }
                }
            }
        }
    }

    private fun watch() {
        observer?.stopWatching()
        observer = object : FileObserver(
            root,
            CREATE or DELETE or MODIFY or MOVED_TO or MOVED_FROM or CLOSE_WRITE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (event and 0x8000 != 0) return
                reload()
            }
        }.also { it.startWatching() }
    }

    /**
     * Moves the retired terminal-theme files (theme.name, colors.toml, palette.json) to theme.toml once.
     * The old files are kept as theme.name.legacy, colors.toml.legacy and palette.legacy.json. Runs before seedMissing, which then adds defaults.
     */
    private fun migrateLegacyTheme() {
        if (File(root, "theme.toml").isFile) return
        val themeFile = File(root, "theme.name")
        val paletteFile = File(root, "palette.json")
        val colorsFile = File(root, "colors.toml")
        if (!themeFile.isFile && !paletteFile.isFile && !colorsFile.isFile) return
        val colors = if (colorsFile.isFile) Toml.parse(colorsFile.readText()) else emptyMap()
        val result = ThemeMigration.migrate(
            themeName = themeFile.takeIf { it.isFile }?.readText(),
            paletteJson = paletteFile.takeIf { it.isFile }?.readText(),
            colorsMode = colors["mode"],
            colorsAccent = colors["accent"],
        )
        writeTheme(result.settings)
        result.warnings.forEach { Log.w(TAG, it) }
        // Keep everything the user may have customised: rename, never delete.
        if (themeFile.isFile) themeFile.renameTo(File(root, "theme.name.legacy"))
        if (colorsFile.isFile) colorsFile.renameTo(File(root, "colors.toml.legacy"))
        if (paletteFile.isFile) paletteFile.renameTo(File(root, "palette.legacy.json"))
    }

    companion object {
        private const val TAG = "ConfigStore"

        fun defaults() = DeftConfig(
            colors = Palette.colors(Palette.resolve(ThemeSettings(), null, false)),
            shell = parseShell(emptyMap()),
            home = parseHome(emptyMap()),
            agent = parseAgent(emptyMap()),
            theme = ThemeSettings(),
            limits = DeftPaths.defaultLimits(),
        )
    }
}
