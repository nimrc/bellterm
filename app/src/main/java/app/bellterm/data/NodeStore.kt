package app.bellterm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Why a node's route cannot be followed. */
sealed interface RouteProblem {
    data class MissingJump(val node: Node) : RouteProblem
    data class Loop(val node: Node) : RouteProblem
    data object TooLong : RouteProblem
}

/**
 * Saved nodes and the host keys the user trusted, in app-private preferences.
 * Saved passwords are secrets and live encrypted in the [Keychain] instead.
 */
class NodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("nodes", Context.MODE_PRIVATE)
    private val _nodes = MutableStateFlow(load())
    val nodes: StateFlow<List<Node>> = _nodes
    private val _known = MutableStateFlow(loadKnown())
    val knownHosts: StateFlow<Map<String, String>> = _known

    private fun load(): List<Node> {
        val array = runCatching { JSONArray(prefs.getString("nodes", "[]")) }.getOrNull() ?: return emptyList()
        return List(array.length()) { Node.fromJson(array.getJSONObject(it)) }
    }

    private fun save(nodes: List<Node>) {
        val array = JSONArray()
        nodes.forEach { array.put(it.toJson()) }
        prefs.edit().putString("nodes", array.toString()).apply()
        _nodes.value = nodes
    }

    fun get(id: String?): Node? = id?.let { wanted -> _nodes.value.firstOrNull { it.id == wanted } }

    fun upsert(node: Node) {
        val nodes = _nodes.value.toMutableList()
        val index = nodes.indexOfFirst { it.id == node.id }
        if (index >= 0) nodes[index] = node else nodes += node
        save(nodes)
    }

    fun upsertAll(added: List<Node>) {
        val byId = _nodes.value.associateBy { it.id }.toMutableMap()
        added.forEach { byId[it.id] = it }
        save(_nodes.value.map { byId.getValue(it.id) } + added.filter { new -> _nodes.value.none { it.id == new.id } })
    }

    /** Removes the node; nodes that jumped through it now connect directly. */
    fun delete(node: Node) {
        save(_nodes.value.filterNot { it.id == node.id }.map { if (it.jumpId == node.id) it.copy(jumpId = null) else it })
    }

    fun touch(node: Node) {
        get(node.id)?.let { upsert(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    /** The node's jump hosts in order, then the node itself. */
    fun route(node: Node): Result<List<Node>> {
        val chain = ArrayDeque<Node>()
        var here = node
        while (true) {
            val id = here.id
            if (chain.any { it.id == id }) return Result.failure(RouteException(RouteProblem.Loop(here)))
            if (chain.size >= Node.MAX_HOPS) return Result.failure(RouteException(RouteProblem.TooLong))
            chain.addFirst(here)
            val jumpId = here.jumpId ?: break
            here = get(jumpId) ?: return Result.failure(RouteException(RouteProblem.MissingJump(here)))
        }
        return Result.success(chain.toList())
    }

    /** Nodes that may serve as [node]'s jump host without making a loop. */
    fun jumpCandidates(node: Node?): List<Node> = _nodes.value.filter { candidate ->
        candidate.id != node?.id && generateSequence(candidate) { get(it.jumpId) }.take(Node.MAX_HOPS).none { it.id == node?.id }
    }

    // Known hosts: "host:port" -> "SHA256:…", as the SSH layer names them.

    private fun loadKnown(): Map<String, String> {
        val o = runCatching { JSONObject(prefs.getString("known_hosts", null) ?: "{}") }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun saveKnown(known: Map<String, String>) {
        prefs.edit().putString("known_hosts", JSONObject(known).toString()).apply()
        _known.value = known
    }

    fun trustedKey(hostKeyName: String): String? = _known.value[hostKeyName]

    fun trust(hostKeyName: String, fingerprint: String) = saveKnown(_known.value + (hostKeyName to fingerprint))

    fun forget(hostKeyName: String) = saveKnown(_known.value - hostKeyName)
}

class RouteException(val problem: RouteProblem) : Exception(problem.toString())
