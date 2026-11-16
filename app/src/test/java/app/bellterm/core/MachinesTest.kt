package app.bellterm.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MachinesTest {
    @Test
    fun parsesWhatTheCoreReturns() {
        val json = """[{"id":"a1","label":"build","target":"me@build","session":"agents","enabled":true},
                       {"id":"b2","label":"","target":"ssh://box:2200","session":"","enabled":true}]"""
        assertEquals(
            listOf(Machine("build", "me@build", "agents"), Machine("ssh://box:2200", "ssh://box:2200", "default")),
            Machines.parse(json),
        )
        assertEquals(emptyList<Machine>(), Machines.parse("[]"))
    }
}
