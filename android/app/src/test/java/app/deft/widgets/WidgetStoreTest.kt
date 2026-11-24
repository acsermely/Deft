package app.deft.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WidgetStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun card(app: String, id: String, at: Long) = WidgetStore.Card(app, id, "App", "Title $id", updatedAt = at)

    @Test
    fun upsertReplacesAndKeepsNewestFirst() {
        val store = WidgetStore(File(tmp.root, "w.json"))
        store.upsert(card("a", "one", 1))
        store.upsert(card("b", "one", 2))
        store.upsert(card("a", "one", 3).copy(title = "updated"))
        assertEquals(listOf("a", "b"), store.cards.value.map { it.webAppId })
        assertEquals("updated", store.cards.value.first().title)
    }

    @Test
    fun limitPerAppAndPersistence() {
        val f = File(tmp.root, "w.json")
        val store = WidgetStore(f)
        repeat(WidgetStore.MAX_PER_APP) { assertTrue(store.upsert(card("a", "c$it", it.toLong()))) }
        assertFalse(store.upsert(card("a", "extra", 9)))
        assertTrue(store.upsert(card("a", "c0", 10))) // replacing is fine
        assertEquals(WidgetStore.MAX_PER_APP, WidgetStore(f).forApp("a").size)
    }

    @Test
    fun removeOneOrAll() {
        val store = WidgetStore(File(tmp.root, "w.json"))
        store.upsert(card("a", "x", 1)); store.upsert(card("a", "y", 2)); store.upsert(card("b", "x", 3))
        store.remove("a", "x")
        assertEquals(listOf("y"), store.forApp("a").map { it.id })
        store.remove("a")
        assertTrue(store.forApp("a").isEmpty())
        assertEquals(1, store.cards.value.size)
    }
}
