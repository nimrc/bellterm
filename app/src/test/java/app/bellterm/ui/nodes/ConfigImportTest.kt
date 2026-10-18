package app.bellterm.ui.nodes

import app.bellterm.data.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigImportTest {
    private fun entry(alias: String, host: String? = null, user: String? = null, port: Int? = null, jumps: List<String> = emptyList()) =
        ConfigEntry(alias, host, user, port, jumps, null, null)

    @Test
    fun parsesTheCoreDocument() {
        val (entries, skipped) = ConfigImport.parse(
            """{"entries":[{"alias":"dev","host_name":"10.0.0.5","user":null,"port":2222,"proxy_jump":["bastion"],
               "identity_file":"~/.ssh/id","server_alive_interval":30}],"skipped":["line 3: Match"]}""",
        )
        assertEquals(entry("dev", "10.0.0.5", null, 2222, listOf("bastion")).copy(identityFile = "~/.ssh/id", serverAliveInterval = 30), entries.single())
        assertEquals(listOf("line 3: Match"), skipped)
    }

    @Test
    fun jumpAliasesBecomeTheirOwnNodesAndChainsLinkInOrder() {
        val bastion = entry("bastion", "bastion.example.com", "ops")
        val dev = entry("dev", "10.0.0.5", jumps = listOf("bastion"))
        val deep = entry("deep", "10.9.9.9", "me", jumps = listOf("ops@outer:2200", "inner"))
        val nodes = ConfigImport.nodes(listOf(dev, deep), listOf(bastion, dev, deep), emptyList(), "me", "ssh")
        val byName = nodes.associateBy { it.name }

        assertEquals("bastion.example.com", byName.getValue("bastion").address)
        assertEquals("ops", byName.getValue("bastion").user)
        assertEquals(byName.getValue("bastion").id, byName.getValue("dev").jumpId)
        assertEquals("the fallback user", "me", byName.getValue("dev").user)

        val outer = byName.getValue("outer")
        assertEquals(2200, outer.port)
        assertNull(outer.jumpId)
        val inner = byName.getValue("inner")
        assertEquals(outer.id, inner.jumpId)
        assertEquals(inner.id, byName.getValue("deep").jumpId)
        assertEquals(setOf("bastion", "dev", "deep", "outer", "inner"), byName.keys)
    }

    @Test
    fun existingNodesAreReusedNotDuplicated() {
        val saved = Node(name = "bastion", address = "b", user = "u")
        val dev = entry("dev", "d", "me", jumps = listOf("bastion"))
        val nodes = ConfigImport.nodes(listOf(dev), listOf(dev), listOf(saved), "me", "ssh")
        assertEquals(listOf("dev"), nodes.map { it.name })
        assertEquals(saved.id, nodes.single().jumpId)
    }

    @Test
    fun specsSplitIntoUserHostAndPort() {
        assertEquals(Triple("ops", "bastion", 2200), ConfigImport.splitSpec("ops@bastion:2200"))
        assertEquals(Triple(null, "bastion", null), ConfigImport.splitSpec("bastion"))
        assertEquals(Triple("me", "::1", 22), ConfigImport.splitSpec("me@[::1]:22"))
        assertEquals(Triple(null, "fe80::1", null), ConfigImport.splitSpec("fe80::1"))
    }
}
