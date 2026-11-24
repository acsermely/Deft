package app.deft.workspace

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DeftJcsTest {
    @Test
    fun sortsKeys() {
        val raw = Json.parseToJsonElement("""{"b":1,"a":"x"}""")
        assertEquals("""{"a":"x","b":1}""", DeftJcs.of(raw))
        val again = Json.parseToJsonElement("""{"a":"x","b":1}""")
        assertEquals(DeftJcs.of(raw), DeftJcs.of(again))
        assertNotEquals(DeftJcs.of(raw), """{"b":1,"a":"x"}""")
    }
}
