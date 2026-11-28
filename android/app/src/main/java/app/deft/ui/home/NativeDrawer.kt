package app.deft.ui.home

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asImageBitmap
import app.deft.DeftApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-wide launcher-app list. Icons are loaded once, not on every Home resume. */
object NativeDrawer {
    @Volatile private var renderedMono: Boolean? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<NativeApp>>(emptyList())
    val apps: StateFlow<List<NativeApp>> = _apps.asStateFlow()

    @Volatile private var started = false

    fun warm(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        listen(app)
        scope.launch { refresh(app) }
        // The monochrome switch only changes which bitmap is cached, so re-render on change, not per frame.
        scope.launch {
            DeftApp.instance.configStore.config
                .map { it.theme.monochromeIcons }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh(app) }
        }
    }

    private fun listen(app: Context) {
        val launcher = app.getSystemService(LauncherApps::class.java) ?: return
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) { reload() }
            override fun onPackageRemoved(packageName: String, user: UserHandle) { reload() }
            override fun onPackageChanged(packageName: String, user: UserHandle) { reload() }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload() }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload() }
            private fun reload() {
                scope.launch { refresh(app) }
            }
        }
        launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    private suspend fun refresh(app: Context) {
        val self = app.packageName
        val size = (app.resources.displayMetrics.density * 48f).toInt().coerceIn(48, 128)
        val launcher = app.getSystemService(LauncherApps::class.java)
        val activities = withContext(Dispatchers.IO) {
            launcher?.getActivityList(null, Process.myUserHandle()).orEmpty()
                .filter { it.applicationInfo.packageName != self }
                .sortedBy { it.label.toString().lowercase() }
        }
        val mono = DeftApp.instance.configStore.config.value.theme.monochromeIcons
        // Per-app `mono` can be false in mono mode (no monochrome layer), so the cache is keyed on the mode itself.
        // Such apps keep their full-colour icon: a silhouette of a coloured icon would be a solid blob.
        val modeChanged = renderedMono != mono
        renderedMono = mono
        val cached = if (modeChanged) emptyMap() else _apps.value.associateBy { it.packageName + "/" + it.activityName }
        val skeleton = activities.map { info ->
            val packageName = info.applicationInfo.packageName
            val activityName = info.componentName.className
            val key = "$packageName/$activityName"
            val hit = cached[key]
            NativeApp(
                label = info.label.toString(),
                packageName = packageName,
                activityName = activityName,
                icon = hit?.icon,
                mono = hit?.mono ?: false,
            )
        }
        _apps.value = skeleton
        val withIcons = activities.map { info ->
            val packageName = info.applicationInfo.packageName
            val activityName = info.componentName.className
            val key = "$packageName/$activityName"
            val label = info.label.toString()
            val existing = cached[key]
            if (existing != null && existing.label == label && existing.icon != null) return@map existing
            val mask = if (mono) runCatching { info.toMonochromeMask(size) }.getOrNull() else null
            val icon = mask ?: runCatching { info.getBadgedIcon(0).toIconBitmap(size) }.getOrNull()
            NativeApp(label, packageName, activityName, icon, mask != null)
        }
        _apps.value = withIcons
    }
}

/**
 * Monochrome (themed) icon as an alpha mask, from the adaptive icon's monochrome layer (Android 13+). Null when the app
 * ships none: the caller then keeps the full-colour icon, because a silhouette of a coloured icon is a solid blob.
 * The layer is drawn directly (108dp canvas, 72dp visible), so no system mask or background can fill it.
 */
private fun LauncherActivityInfo.toMonochromeMask(size: Int): ImageBitmap? {
    if (Build.VERSION.SDK_INT < 33) return null
    val layer = monochromeLayer(getIcon(0)) ?: return null
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pad = size / 4 // (108 - 72) / 2 / 72 of the visible area
    layer.setBounds(-pad, -pad, size + pad, size + pad)
    layer.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

@RequiresApi(33)
private fun monochromeLayer(drawable: Drawable): Drawable? = (drawable as? AdaptiveIconDrawable)?.monochrome

private fun Drawable.toIconBitmap(size: Int): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null && bitmap.width == size && bitmap.height == size) {
        return bitmap.asImageBitmap()
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}
