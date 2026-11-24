package app.deft.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextTest {
    @Test
    fun editSummaryStatesChangedBytesOrNoChange() {
        assertTrue(AgentContext.editSummary("a.html", "abc", "abc").startsWith("no change"))
        assertEquals("edited a.html: changed 4 bytes (+4; 3B -> 7B)", AgentContext.editSummary("a.html", "abc", "abcdefg"))
        assertTrue(AgentContext.editSummary("a.html", "abcdef", "ab").contains("changed 4 bytes"))
        assertTrue(AgentContext.editSummary("a.html", "abc", "xyz").contains("size unchanged"))
    }

    private fun skillCall(id: String, name: String) = ChatMessage(
        role = "assistant",
        tool_calls = listOf(ToolCall(id = id, function = ToolCallFn(name = "skill.read", arguments = """{"name":"$name"}"""))),
    )

    @Test
    fun prunedSkillReadsNameTheFileAndCheckpointListsThem() {
        val big = "x".repeat(2_000)
        val history = mutableListOf(
            ChatMessage(role = "user", content = "first"),
            skillCall("c1", "deft-design"),
            ChatMessage(role = "tool", tool_call_id = "c1", name = "skill.read", content = big),
            ChatMessage(role = "user", content = "second"),
            ChatMessage(role = "assistant", content = "ok"),
            ChatMessage(role = "user", content = "third"),
        )
        AgentContext.pruneToolBodies(history)
        val stub = history[2].content!!
        assertTrue(stub, stub.startsWith("[cleared] skill.read deft-design"))
        assertTrue(stub.contains("re-read only if needed"))
        assertEquals(listOf("deft-design"), AgentContext.skillReads(history))
        val cp = AgentContext.wrapCheckpoint("sum", listOf("deft-design/SKILL.md", "js-bridge"))
        assertTrue(cp.contains("Previously read, now dropped: deft-design/SKILL.md, js-bridge"))
        assertTrue(!AgentContext.wrapCheckpoint("sum").contains("Previously read"))
    }
}
