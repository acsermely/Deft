package app.deft.tiles

import android.content.Context

/**
 * Quick Settings tiles for web apps. Tiles must be declared in the manifest, so the
 * launcher ships [COUNT] slots; an app claims one with `tiles.request` and owns it until it
 * releases it or is uninstalled.
 */
object TileSlots {
    const val COUNT = 3
    private const val PREFS = "fl_tiles"

    data class State(val webAppId: String, val label: String, val subtitle: String?, val active: Boolean, val opensApp: Boolean)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun owner(context: Context, slot: Int): String? = prefs(context).getString("s$slot.app", null)

    fun slotOf(context: Context, webAppId: String): Int? = (1..COUNT).firstOrNull { owner(context, it) == webAppId }

    /** The app's slot, or a free one claimed for it, or null when all are taken. */
    fun claim(context: Context, webAppId: String, label: String): Int? {
        slotOf(context, webAppId)?.let { return it }
        val free = (1..COUNT).firstOrNull { owner(context, it) == null } ?: return null
        prefs(context).edit()
            .putString("s$free.app", webAppId)
            .putString("s$free.label", label)
            .remove("s$free.subtitle").putBoolean("s$free.active", false).putBoolean("s$free.open", false)
            .apply()
        return free
    }

    fun state(context: Context, slot: Int): State? {
        val p = prefs(context)
        val app = p.getString("s$slot.app", null) ?: return null
        return State(app, p.getString("s$slot.label", "") ?: "", p.getString("s$slot.subtitle", null), p.getBoolean("s$slot.active", false), p.getBoolean("s$slot.open", false))
    }

    fun update(context: Context, slot: Int, label: String?, subtitle: String?, active: Boolean?, opensApp: Boolean?) {
        val e = prefs(context).edit()
        label?.let { e.putString("s$slot.label", it.take(30)) }
        subtitle?.let { e.putString("s$slot.subtitle", it.take(40)) }
        active?.let { e.putBoolean("s$slot.active", it) }
        opensApp?.let { e.putBoolean("s$slot.open", it) }
        e.apply()
    }

    fun release(context: Context, webAppId: String) {
        val slot = slotOf(context, webAppId) ?: return
        prefs(context).edit().apply { listOf("app", "label", "subtitle", "active", "open").forEach { remove("s$slot.$it") } }.apply()
    }

    fun serviceClass(slot: Int): Class<out DeftTileService> = when (slot) {
        1 -> DeftTile1::class.java
        2 -> DeftTile2::class.java
        else -> DeftTile3::class.java
    }
}
