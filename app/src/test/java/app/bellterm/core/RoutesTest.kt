package app.bellterm.core

import app.bellterm.data.AuthMethod
import app.bellterm.data.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    private val jump = Node(id = "jump", name = "", address = "j", user = "u", auth = AuthMethod.Password)
    private val keyed = Node(id = "keyed", name = "", address = "k", user = "u", jumpId = "jump")
    private val target = Node(id = "target", name = "", address = "t", user = "u", auth = AuthMethod.Password, jumpId = "keyed")
    private val route = listOf(jump, keyed, target)

    @Test
    fun asksForEveryPasswordHopWhenNoneIsSaved() {
        assertEquals(listOf(jump, target), Routes.unsavedPasswordHops(route) { false })
    }

    @Test
    fun skipsHopsWithSavedPasswords() {
        assertEquals(listOf(target), Routes.unsavedPasswordHops(route) { it.id == "jump" })
        assertEquals(emptyList<Node>(), Routes.unsavedPasswordHops(route) { true })
    }

    @Test
    fun keyHopsAreNeverAskedFor() {
        assertEquals(emptyList<Node>(), Routes.unsavedPasswordHops(listOf(keyed)) { false })
    }

    @Test
    fun aSavedMachineIsNoHopOfOurs() {
        val machine = Node(id = "machine", name = "b", address = "b", user = "u", auth = AuthMethod.Password, jumpId = "target", machine = "b")
        val relayed = route + machine
        assertEquals(route, Routes.sshHops(relayed))
        assertEquals(route, Routes.sshHops(route))
        assertEquals(listOf(jump, target), Routes.unsavedPasswordHops(relayed) { false })
    }

    @Test
    fun aSavedMachineSurvivesStorage() {
        val machine = Node(id = "machine", name = "b", address = "b", user = "u", jumpId = "target", machine = "ssh://me@b:2222", session = "agents")
        assertEquals(machine, Node.fromJson(machine.toJson()))
        assertEquals("ssh://me@b:2222", machine.destination)
        assertEquals(null, Node.fromJson(target.toJson()).machine)
    }
}
