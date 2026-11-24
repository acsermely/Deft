package app.deft.workspace

import android.content.Context
import app.deft.BuildConfig
import app.deft.DeftApp
import app.deft.data.AppRepository
import java.io.File
import java.security.MessageDigest

object BundledApps {
    const val TIER_LOCAL = "local"
    const val TIER_BUNDLED = "bundled"
    const val ID_PREFIX = "bundled:"
    const val HOST_SUFFIX = ".app.deft.internal"

    fun originId(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("bundled/$id".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
    const val ASSET_DIR = "deft-apps"

    fun origin(id: String): String = "https://${originId(id)}$HOST_SUFFIX"

    fun webAppId(id: String): String = ID_PREFIX + id

    fun idFromWebAppId(webAppId: String): String? =
        webAppId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    fun dir(context: Context, id: String): File = File(StorageMigration.bundledRoot(context), id)

    fun listIds(context: Context): List<String> {
        val root = StorageMigration.bundledRoot(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && File(it, "manifest.json").isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    fun sync(context: Context, repository: AppRepository) {
        val destRoot = StorageMigration.bundledRoot(context)
        destRoot.mkdirs()
        val stamp = File(destRoot, ".apk-version")
        // versionCode alone does not change between dev builds or every release, so the
        // install time is part of the stamp: any reinstall or update recopies the assets.
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val version = "${BuildConfig.VERSION_CODE}:$installed"
        if (stamp.readTextOrNull() != version) {
            destRoot.listFiles()?.forEach { child ->
                if (child.name != ".apk-version") child.deleteRecursively()
            }
            val names = context.assets.list(ASSET_DIR).orEmpty()
            for (id in names) {
                copyAssetDir(context, "$ASSET_DIR/$id", File(destRoot, id))
            }
            stamp.writeText(version)
        }
        kotlinx.coroutines.runBlocking {
            for (id in listIds(context)) {
                repository.ensureBundledApp(id)
            }
        }
    }

    fun copyToMyApps(context: Context, id: String, slug: String): ProjectInfo {
        val src = dir(context, id)
        if (!File(src, "manifest.json").isFile) {
            throw IllegalArgumentException("unknown bundled app $id")
        }
        val store = DeftApp.instance.myApps
        val dest = store.projectDir(slug)
        if (dest.exists()) throw IllegalArgumentException("app $slug already exists")
        src.copyRecursively(dest, overwrite = false)
        File(dest, ".apk-version").delete()
        val manifest = File(dest, "manifest.json")
        if (manifest.isFile) {
            val raw = manifest.readText()
            val rewritten = raw.replaceFirst(Regex("\"id\"\\s*:\\s*\"[^\"]+\""), "\"id\": \"$slug\"")
            manifest.writeText(rewritten)
        }
        return ProjectInfo(slug = slug, name = slug, dir = dest)
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(dest, child))
        }
    }

    private fun File.readTextOrNull(): String? =
        takeIf { it.isFile }?.readText()?.trim()
}
