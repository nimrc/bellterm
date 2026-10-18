package app.bellterm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellViewTest {
    private val json = """
        {"revision":7,"focused_workspace":"w1","focused_tab":"w1:t2","focused_pane":"p2","update_available":null,
         "workspaces":[{"id":"w1","number":1,"label":"api","branch":"main","ahead":2,"behind":null,
           "worktree":"feat","linked_worktree":true,"active_tab":"w1:t2","focused":true,"status":"blocked"}],
         "tabs":[{"id":"w1:t2","workspace":"w1","number":2,"label":"agent","zoomed":false,"focused":true,"status":"working"},
                 {"id":"w1:t1","workspace":"w1","number":1,"label":"shell","zoomed":true,"focused":false,"status":"unknown"}],
         "panes":[{"id":"p2","workspace":"w1","tab":"w1:t2","label":null,"cwd":"/src/api","focused":true}],
         "agents":[{"pane":"p2","workspace":"w1","tab":"w1:t2","name":null,"agent":"claude","title":"Fix tests",
           "status":"blocked","focused":true}]}
    """.trimIndent()

    @Test
    fun parsesHerdrConcepts() {
        val view = ShellView.parse(json)
        assertEquals(7L, view.revision)
        assertEquals("api", view.workspace?.label)
        assertEquals(2, view.workspace?.ahead)
        assertNull(view.workspace?.behind)
        assertEquals(AgentStatus.Blocked, view.workspace?.status)
        assertEquals(listOf("shell", "agent"), view.tabsOf("w1").map { it.label })
        assertEquals("agent", view.tab?.label)
        assertEquals("/src/api", view.panesOf("w1:t2").single().cwd)
        val agent = view.agentIn("p2")!!
        assertEquals("claude", agent.displayName)
        assertEquals(AgentStatus.Blocked, agent.status)
        assertNull(view.updateAvailable)
    }

    @Test
    fun unknownStatusesAreUnknown() {
        assertEquals(AgentStatus.Unknown, AgentStatus.parse("sleeping"))
    }
}
