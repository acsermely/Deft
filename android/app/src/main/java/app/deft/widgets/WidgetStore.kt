package app.deft.widgets

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Home widgets for web apps. Data cards, not live pages: an app sets a title, text and
 * an optional value; Home draws them natively and a tap opens the app. Cheap on battery, and no
 * app code runs on the Home screen.
 */
class WidgetStore(private val file: File) {
    @Serializable
    data class Card(
        val webAppId: String,
        val id: String,
        val appName: String,
        val title: String,
        val text: String = "",
        val value: String? = null,
        val url: String? = null,
        val updatedAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _cards = MutableStateFlow(load())
    val cards: StateFlow<List<Card>> = _cards

    private fun load(): List<Card> =
        runCatching { json.decodeFromString(ListSerializer(Card.serializer()), file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    private fun save(next: List<Card>) {
        val sorted = next.sortedByDescending { it.updatedAt }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Card.serializer()), sorted))
        tmp.renameTo(file)
        _cards.value = sorted
    }

    /** Adds or replaces one card. Fails when the app already has [MAX_PER_APP] other cards. */
    @Synchronized
    fun upsert(card: Card): Boolean {
        val others = _cards.value.filterNot { it.webAppId == card.webAppId && it.id == card.id }
        if (others.count { it.webAppId == card.webAppId } >= MAX_PER_APP) return false
        save(others + card)
        return true
    }

    @Synchronized
    fun remove(webAppId: String, id: String? = null) {
        save(_cards.value.filterNot { it.webAppId == webAppId && (id == null || it.id == id) })
    }

    fun forApp(webAppId: String): List<Card> = _cards.value.filter { it.webAppId == webAppId }

    companion object {
        const val MAX_PER_APP = 3
        val ID = Regex("^[a-z0-9_-]{1,32}$")

        @Volatile private var instance: WidgetStore? = null

        fun get(context: Context): WidgetStore =
            instance ?: synchronized(this) {
                instance ?: WidgetStore(File(context.applicationContext.filesDir, "deft/widgets.json")).also { instance = it }
            }
    }
}
