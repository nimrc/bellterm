package app.bellterm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.bellterm.core.Connection
import app.bellterm.core.Routes
import app.bellterm.data.AppLanguages
import app.bellterm.data.Keychain
import app.bellterm.data.Node
import app.bellterm.data.NodeStore
import app.bellterm.data.RouteException
import app.bellterm.data.RouteProblem
import app.bellterm.data.SettingsStore
import app.bellterm.data.UpdateChecker
import app.bellterm.service.ConnectionService
import app.bellterm.ui.terminal.FontManager
import app.bellterm.ui.theme.NodeColors
import app.bellterm.ui.theme.TerminalSchemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide state. Connections outlive activities, as Herdr sessions
 * outlive clients: leaving the screen keeps the attachment, and only an
 * explicit disconnect ends it. Disconnecting never stops the daemon.
 */
class BelltermApp : Application() {
    lateinit var nodes: NodeStore
        private set
    lateinit var keychain: Keychain
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var fonts: FontManager
        private set
    lateinit var updates: UpdateChecker
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connections = MutableStateFlow<Map<String, Connection>>(emptyMap())
    val connections: StateFlow<Map<String, Connection>> = _connections

    /** Whether an activity is showing, so notifications only go out when not. */
    @Volatile
    var foreground = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onCreate() {
        super.onCreate()
        instance = this
        nodes = NodeStore(this)
        keychain = Keychain(this)
        settings = SettingsStore(this)
        fonts = FontManager(this)
        updates = UpdateChecker(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENTS, getString(R.string.channel_agents), NotificationManager.IMPORTANCE_HIGH),
        )
        scope.launch(Dispatchers.IO) { keychain.device(getString(R.string.device_key_name)) }
    }

    fun connection(nodeId: String): Connection? = _connections.value[nodeId]

    /**
     * Attach to [node] along its jump hosts, reusing a live connection.
     * [passwords] maps node IDs on the route to passwords typed for this
     * connection; [remember] saves them to the keychain, else any saved
     * password for those hops is forgotten. Hops without one use their saved
     * password.
     */
    fun connect(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean = false): Connection {
        _connections.value[node.id]?.takeIf { it.isOpen }?.let { return it }
        _connections.value[node.id]?.close()
        val connection = Connection(node)
        _connections.value = _connections.value + (node.id to connection)
        nodes.touch(node)
        scope.launch(Dispatchers.IO) {
            val route = nodes.route(node).getOrElse {
                connection.fail(routeMessage((it as? RouteException)?.problem))
                return@launch
            }
            keychain.device(getString(R.string.device_key_name))
            // A password typed but not remembered also clears any saved one,
            // which is how a rejected saved password is replaced or dropped.
            if (remember) passwords.forEach(keychain::savePassword) else passwords.keys.forEach(keychain::forgetPassword)
            val settings = settings.settings.value
            val scheme = TerminalSchemes.byId(if (dark) settings.darkScheme else settings.lightScheme, dark)
                .withCursor(NodeColors.of(node.color))
            val document = runCatching {
                Routes.document(route, nodes, keychain, passwords, this@BelltermApp.settings.lastGrid, scheme)
            }.getOrElse {
                connection.fail(getString(R.string.key_unreadable))
                return@launch
            }
            connection.start(document)
        }
        ConnectionService.update(this)
        return connection
    }

    /** Nodes on [node]'s route that sign in with a password and have none saved. */
    fun passwordsToAsk(node: Node): List<Node> =
        nodes.route(node).getOrNull()?.let { route -> Routes.unsavedPasswordHops(route) { keychain.hasPassword(it.id) } }.orEmpty()

    private fun routeMessage(problem: RouteProblem?): String = when (problem) {
        is RouteProblem.MissingJump -> getString(R.string.route_missing_jump, problem.node.title)
        is RouteProblem.Loop -> getString(R.string.route_loop, problem.node.title)
        RouteProblem.TooLong, null -> getString(R.string.route_too_long, Node.MAX_HOPS)
    }

    /** Retry after the user trusted a host key or changed credentials. */
    fun reconnect(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean = false): Connection {
        disconnect(node.id)
        return connect(nodes.get(node.id) ?: node, passwords, dark, remember)
    }

    fun disconnect(nodeId: String) {
        _connections.value[nodeId]?.close()
        _connections.value = _connections.value - nodeId
        ConnectionService.update(this)
    }

    fun disconnectAll() {
        _connections.value.values.forEach { it.close() }
        _connections.value = emptyMap()
        ConnectionService.update(this)
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_AGENTS = "agents"

        lateinit var instance: BelltermApp
            private set
    }
}
