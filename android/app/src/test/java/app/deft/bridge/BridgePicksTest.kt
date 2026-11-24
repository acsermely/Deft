package app.deft.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgePicksTest {
    @Test
    fun forgottenRequestIgnoresALateAnswer() {
        // Review: a timed-out pick or result is removed, so a late answer completes nothing.
        val intent = BridgePicks.registerIntent("i1")
        val tree = BridgePicks.registerTree("t1")
        val save = BridgePicks.registerSave("s1")
        val media = BridgePicks.registerMedia("m1")
        for (id in listOf("i1", "t1", "s1", "m1")) BridgePicks.forget(id)
        BridgePicks.completeIntent("i1", IntentReply(true, null, emptyList()))
        BridgePicks.completeTree("t1", "content://x")
        BridgePicks.completeSave("s1", "content://x")
        BridgePicks.completeMedia("m1", "content://x", null)
        assertFalse(intent.isCompleted || tree.isCompleted || save.isCompleted || media.isCompleted)
    }

    @Test
    fun timeoutIsTenMinutes() {
        assertEquals(600_000L, BridgePicks.RESULT_TIMEOUT_MS)
    }
}
