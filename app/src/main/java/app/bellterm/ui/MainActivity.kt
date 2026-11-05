package app.bellterm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.bellterm.BelltermApp
import app.bellterm.data.AppLanguages
import app.bellterm.data.Node
import app.bellterm.data.ThemeMode
import app.bellterm.ui.components.PasswordsDialog
import app.bellterm.ui.keys.KeysScreen
import app.bellterm.ui.keys.KnownHostsScreen
import app.bellterm.ui.nodes.ImportConfigScreen
import app.bellterm.ui.nodes.NodeEditor
import app.bellterm.ui.nodes.NodesNavigation
import app.bellterm.ui.nodes.NodesScreen
import app.bellterm.ui.session.SessionScreen
import app.bellterm.ui.settings.SettingsScreen
import app.bellterm.ui.theme.BelltermTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val opened = mutableStateOf<Pair<String, String?>?>(null)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        val app = application as BelltermApp
        setContent {
            val settings by app.settings.settings.collectAsState()
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // System bar icons follow the app's theme, not the system's, so a
            // light theme on a dark system keeps dark, readable icons.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            BelltermTheme(dark = dark, dynamic = settings.dynamicColor) {
                // Test tags double as resource ids, so UiAutomator end-to-end tests can find them.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    Root(app, dark)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val node = intent?.getStringExtra(EXTRA_NODE) ?: return
        opened.value = node to intent.getStringExtra(EXTRA_PANE)
    }

    override fun onStart() {
        super.onStart()
        val app = application as BelltermApp
        app.foreground = true
        // At most daily; a process kept alive by a connection still rechecks.
        app.scope.launch { app.updates.check(force = false) }
    }

    override fun onStop() {
        (application as BelltermApp).foreground = false
        super.onStop()
    }

    /**
     * Screens by name, so the current one survives recreation: `nodes`,
     * `session:<id>`, `edit:<id>` or `edit:`, `import`, `keys`, `known`, `settings`.
     */
    @Composable
    private fun Root(app: BelltermApp, dark: Boolean) {
        var screen by rememberSaveable { mutableStateOf("nodes") }
        var askPasswords by rememberSaveable { mutableStateOf<String?>(null) }
        val connections by app.connections.collectAsState()
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

        opened.value?.let { (nodeId, _) ->
            if (connections.containsKey(nodeId) && screen != "session:$nodeId") screen = "session:$nodeId"
        }

        fun open(node: Node) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val live = app.connection(node.id)?.isOpen == true
            if (!live && app.passwordsToAsk(node).isNotEmpty()) {
                askPasswords = node.id
                return
            }
            app.connect(node, emptyMap(), dark)
            screen = "session:${node.id}"
        }

        val navigation = object : NodesNavigation {
            override fun open(node: Node) = open(node)
            override fun edit(node: Node?) { screen = "edit:${node?.id.orEmpty()}" }
            override fun importConfig() { screen = "import" }
            override fun keys() { screen = "keys" }
            override fun knownHosts() { screen = "known" }
            override fun settings() { screen = "settings" }
        }
        val home = { screen = "nodes" }

        // Screens cross-fade with a slight zoom, as COUI pages do.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith
                    (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.02f))
            },
            label = "screen",
        ) { target ->
            when {
                target == "keys" -> KeysScreen(app, onBack = home)
                target == "known" -> KnownHostsScreen(app, onBack = home)
                target == "settings" -> SettingsScreen(app, onBack = home)
                target == "import" -> ImportConfigScreen(app, onDone = home)
                target.startsWith("edit:") -> NodeEditor(app, app.nodes.get(target.removePrefix("edit:").ifEmpty { null }), onDone = home)
                target.startsWith("session:") -> {
                    val nodeId = target.removePrefix("session:")
                    val connection = connections[nodeId]
                    if (connection == null) {
                        screen = "nodes"
                    } else {
                        SessionScreen(
                            app = app,
                            connection = connection,
                            focusPane = opened.value?.takeIf { it.first == nodeId }?.second,
                            dark = dark,
                            onLeave = {
                                opened.value = null
                                screen = "nodes"
                            },
                        )
                    }
                }
                else -> NodesScreen(app, navigation)
            }
        }

        askPasswords?.let { nodeId ->
            val node = app.nodes.get(nodeId)
            val hops = node?.let(app::passwordsToAsk)
            if (node == null || hops.isNullOrEmpty()) {
                askPasswords = null
            } else {
                PasswordsDialog(
                    hops = hops,
                    onConnect = { secrets, remember ->
                        askPasswords = null
                        app.connect(node, secrets, dark, remember)
                        screen = "session:$nodeId"
                    },
                    onDismiss = { askPasswords = null },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_NODE = "node"
        private const val EXTRA_PANE = "pane"

        fun intent(context: Context, nodeId: String, pane: String?) = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_NODE, nodeId)
            .putExtra(EXTRA_PANE, pane)
    }
}
