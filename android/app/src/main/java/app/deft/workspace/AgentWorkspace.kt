package app.deft.workspace

import app.deft.ui.home.HomeCanvasStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class OpenTab(
    val id: String,
    val workspaceId: String?,
) {
    val empty: Boolean get() = workspaceId == null
}

/** Open harness tabs plus the selected tab. Home is always present. */
class AgentWorkspace(private val recentsFile: File? = null) {
    private val _tabs = MutableStateFlow(listOf(homeTab()))
    val tabs: StateFlow<List<OpenTab>> = _tabs.asStateFlow()

    private val _selected = MutableStateFlow(HOME_ID)
    val selected: StateFlow<String> = _selected.asStateFlow()

    private var nextEmpty = 1
    private val recentsLock = Any()
    private var recentsCache: List<String>? = null

    fun isHome(): Boolean = isHomeSlug(_selected.value)

    fun selectedTab(): OpenTab? = _tabs.value.firstOrNull { it.id == _selected.value }

    fun tab(id: String): OpenTab? = _tabs.value.firstOrNull { it.id == id }

    fun tabForWorkspace(slug: String): OpenTab? {
        val key = if (isHomeSlug(slug)) HOME_ID else MyAppsStore.requireSlug(slug)
        return _tabs.value.firstOrNull { it.workspaceId == key }
    }

    fun select(id: String) {
        if (_tabs.value.none { it.id == id }) return
        _selected.value = id
    }

    fun selectHome() = select(HOME_ID)

    /** Untitled tab, like a VS Code window with no folder. Reuses an existing empty tab and moves it to the end. */
    fun openEmpty(): String {
        val current = _tabs.value
        val existing = current.firstOrNull { it.empty }
        if (existing != null) {
            if (current.lastOrNull()?.id != existing.id) {
                _tabs.value = current.filterNot { it.id == existing.id } + existing
            }
            return existing.id
        }
        val id = EMPTY_PREFIX + nextEmpty
        nextEmpty += 1
        _tabs.value = current + OpenTab(id, null)
        return id
    }

    /** Bind an untitled tab to a my-app. No-op if the tab is already bound. */
    fun bind(tabId: String, slug: String) {
        val key = MyAppsStore.requireSlug(slug)
        if (isHomeSlug(tabId)) return
        val current = _tabs.value
        val tab = current.firstOrNull { it.id == tabId } ?: return
        if (tab.workspaceId != null) return
        _tabs.value = current.map { if (it.id == tabId) it.copy(workspaceId = key) else it }
        touchRecent(key)
    }

    /** Find or create a bound tab for this my-app. Does not select. */
    fun openWorkspace(slug: String): String {
        val key = MyAppsStore.requireSlug(slug)
        val existing = _tabs.value.firstOrNull { it.workspaceId == key }
        if (existing != null) {
            touchRecent(key)
            return existing.id
        }
        val id = EMPTY_PREFIX + nextEmpty
        nextEmpty += 1
        _tabs.value = _tabs.value + OpenTab(id, key)
        touchRecent(key)
        return id
    }

    fun close(tabId: String) {
        if (isHomeSlug(tabId)) throw IllegalArgumentException("cannot close Home")
        val current = _tabs.value
        if (current.none { it.id == tabId }) return
        _tabs.value = current.filterNot { it.id == tabId }
        if (_selected.value == tabId) _selected.value = HOME_ID
    }

    fun closeWorkspace(slug: String) {
        val tab = tabForWorkspace(slug) ?: return
        close(tab.id)
    }

    fun recents(): List<String> = synchronized(recentsLock) {
        recentsCache ?: loadRecents().also { recentsCache = it }
    }

    fun touchRecent(slug: String) {
        val key = MyAppsStore.requireSlug(slug)
        synchronized(recentsLock) {
            recentsCache = (listOf(key) + recents().filter { it != key }).take(MAX_RECENTS)
            saveRecents(recentsCache!!)
        }
    }

    fun forgetRecent(slug: String) {
        val key = runCatching { MyAppsStore.requireSlug(slug) }.getOrNull() ?: return
        synchronized(recentsLock) {
            recentsCache = recents().filter { it != key }
            saveRecents(recentsCache!!)
        }
    }

    private fun loadRecents(): List<String> {
        val file = recentsFile ?: return emptyList()
        if (!file.isFile) return emptyList()
        return file.readLines()
            .map { it.trim().lowercase() }
            .mapNotNull { runCatching { MyAppsStore.requireSlug(it) }.getOrNull() }
            .distinct()
            .take(MAX_RECENTS)
    }

    private fun saveRecents(items: List<String>) {
        val file = recentsFile ?: return
        file.parentFile?.mkdirs()
        file.writeText(items.joinToString("\n"))
    }

    companion object {
        const val HOME_ID = "home"
        const val EMPTY_PREFIX = "tab:"
        private const val MAX_RECENTS = 8

        fun isHomeSlug(raw: String?): Boolean {
            val slug = raw?.trim()?.lowercase().orEmpty()
            return slug == HOME_ID || slug == HomeCanvasStore.SLUG
        }

        private fun homeTab() = OpenTab(HOME_ID, HOME_ID)
    }
}
