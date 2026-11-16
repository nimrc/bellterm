package app.bellterm.data

import org.json.JSONObject
import java.util.UUID

enum class AuthMethod { Key, Password }

/**
 * A saved SSH server: a machine whose Herdr daemon Bellterm attaches to, or a
 * jump host on the way to one. A node reached through another names it in
 * [jumpId]; chains are resolved by [NodeStore.route].
 *
 * A node with a [machine] is one of the SSH machines Herdr on its jump host
 * saved with `herdr machine add`: the jump host's own `ssh` reaches
 * [machine], so its address, user, and credentials here are unused.
 */
data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 22,
    val user: String,
    val session: String = "default",
    val auth: AuthMethod = AuthMethod.Key,
    /** The keychain identity to sign in with; null means the device key. */
    val identityId: String? = null,
    val jumpId: String? = null,
    val group: String = "",
    val color: Int = 0,
    /** SSH keepalive in seconds; 0 turns it off. */
    val keepAlive: Int = 15,
    val lastUsed: Long = 0,
    /** The SSH target the jump host's Herdr saved this machine under. */
    val machine: String? = null,
) {
    val title: String get() = name.ifBlank { address }
    val destination: String get() = machine ?: if (port == 22) "$user@$address" else "$user@$address:$port"

    /** How the known-hosts list names this server, as the SSH layer does. */
    val hostKeyName: String get() = "$address:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", address)
        .put("port", port)
        .put("user", user)
        .put("session", session)
        .put("auth", auth.name)
        .put("identity", identityId)
        .put("jump", jumpId)
        .put("group", group)
        .put("color", color)
        .put("keepalive", keepAlive)
        .put("last_used", lastUsed)
        .put("machine", machine)

    companion object {
        fun fromJson(o: JSONObject) = Node(
            id = o.getString("id"),
            name = o.optString("name", o.optString("label")),
            address = o.getString("address"),
            port = o.optInt("port", 22),
            user = o.getString("user"),
            session = o.optString("session", "default").ifBlank { "default" },
            auth = runCatching { AuthMethod.valueOf(o.optString("auth")) }.getOrDefault(AuthMethod.Key),
            identityId = o.optString("identity").takeUnless { o.isNull("identity") || it.isEmpty() },
            jumpId = o.optString("jump").takeUnless { o.isNull("jump") || it.isEmpty() },
            group = o.optString("group"),
            color = o.optInt("color"),
            keepAlive = o.optInt("keepalive", 15),
            lastUsed = o.optLong("last_used"),
            machine = o.optString("machine").takeUnless { o.isNull("machine") || it.isEmpty() },
        )

        /** Herdr session names are plain path components. */
        fun validSession(name: String) =
            name.isNotEmpty() && name.length <= 64 && name.all { it.isLetterOrDigit() || it in "-_." } &&
                name != "." && name != ".."

        fun validAddress(address: String) =
            address.isNotBlank() && address.none { it.isWhitespace() || it == '@' || it == '/' }

        fun validUser(user: String) = user.isNotBlank() && user.none { it.isWhitespace() || it == '@' || it == ':' }

        /** Longest jump chain followed before giving up. */
        const val MAX_HOPS = 8
    }
}
