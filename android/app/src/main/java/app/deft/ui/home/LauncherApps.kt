package app.deft.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import app.deft.data.WebAppEntity

data class NativeApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: ImageBitmap? = null,
    /** True when [icon] is an alpha mask (monochrome theme); the draw site tints it with the label colour. */
    val mono: Boolean = false,
)

sealed class Launchable {
    abstract val key: String
    abstract val label: String
    abstract val subtitle: String?

    data class Web(val app: WebAppEntity) : Launchable() {
        override val key: String get() = "web:${app.id}"
        override val label: String get() = app.name
        override val subtitle: String get() = app.origin
    }

    data class Native(val app: NativeApp) : Launchable() {
        override val key: String get() = "native:${app.packageName}/${app.activityName}"
        override val label: String get() = app.label
        override val subtitle: String? get() = null
    }
}
