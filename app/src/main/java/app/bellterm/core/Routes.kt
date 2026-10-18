package app.bellterm.core

import app.bellterm.data.AuthMethod
import app.bellterm.data.Keychain
import app.bellterm.data.Node
import app.bellterm.data.NodeStore
import app.bellterm.ui.theme.TerminalScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the route document `native/src/config.rs` reads: every jump host in
 * order, then the node, each with its pinned host key and credential. Private
 * keys and saved passwords are decrypted from the keychain here and go
 * straight to the core; a password typed for this connection wins.
 */
object Routes {
    /** Nodes in [route] that sign in with a password. */
    fun passwordHops(route: List<Node>): List<Node> = route.filter { it.auth == AuthMethod.Password }

    /** Password nodes in [route] with no saved password, which must be asked for. */
    fun unsavedPasswordHops(route: List<Node>, saved: (Node) -> Boolean): List<Node> =
        passwordHops(route).filterNot(saved)

    fun document(
        route: List<Node>,
        nodes: NodeStore,
        keychain: Keychain,
        passwords: Map<String, String>,
        grid: IntArray,
        scheme: TerminalScheme,
    ): String {
        val target = route.last()
        val hops = JSONArray()
        for (node in route) {
            val auth = when (node.auth) {
                AuthMethod.Password -> JSONObject().put("type", "password").put("password", passwords[node.id] ?: keychain.password(node.id).orEmpty())
                AuthMethod.Key -> JSONObject()
                    .put("type", "key")
                    .put("private", keychain.privateKey(node.identityId))
                    .put("certificate", keychain.get(node.identityId)?.certificate)
            }
            hops.put(
                JSONObject()
                    .put("host", node.address)
                    .put("port", node.port)
                    .put("user", node.user)
                    .put("pinned", nodes.trustedKey(node.hostKeyName))
                    .put("auth", auth),
            )
        }
        return JSONObject()
            .put("session", target.session)
            .put("hops", hops)
            .put("keepalive_secs", target.keepAlive)
            .put("cols", grid[0])
            .put("rows", grid[1])
            .put("cell_width", grid[2])
            .put("cell_height", grid[3])
            .put("theme", scheme.toJson())
            .toString()
    }
}
