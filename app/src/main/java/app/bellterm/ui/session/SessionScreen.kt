package app.bellterm.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import app.bellterm.ui.theme.NodeColors
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.core.Connection
import app.bellterm.core.Native
import app.bellterm.core.RenderFrame
import app.bellterm.data.AuthMethod
import app.bellterm.data.Node
import app.bellterm.ui.components.PasswordsDialog
import app.bellterm.ui.terminal.TerminalTypefaces
import app.bellterm.ui.theme.TerminalSchemes
import app.bellterm.ui.terminal.DefaultExtraKeys
import app.bellterm.ui.terminal.ExtraKeysRow
import app.bellterm.ui.terminal.TerminalView
import kotlinx.coroutines.launch

/** What a confirmation or text dialog is for. */
internal sealed interface Prompt {
    data class NewTab(val workspaceId: String) : Prompt
    data class RenameTab(val id: String, val label: String) : Prompt
    data class RenameWorkspace(val id: String, val label: String) : Prompt
    data class CloseTab(val id: String, val label: String) : Prompt
    data class ClosePane(val id: String) : Prompt
    data class CloseWorkspace(val id: String, val label: String) : Prompt
}

/**
 * An attached Herdr session: the drawer holds workspaces and agents, the tab
 * row holds the focused workspace's tabs, and the terminal shows the focused
 * tab's panes as the daemon lays them out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(app: BelltermApp, connection: Connection, focusPane: String?, dark: Boolean, onLeave: () -> Unit) {
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsState()
    val fontChanges by app.fonts.changes.collectAsState()
    val state by connection.state.collectAsState()
    val view by connection.view.collectAsState()
    val frame by connection.frame.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var latched by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var askPasswords by remember { mutableStateOf<List<Node>?>(null) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    val node = connection.node
    // The node's colour label marks this session: the bar under the tabs, the
    // selected tab, and the terminal cursor.
    val accent = NodeColors.of(node.color)
    val scheme = TerminalSchemes.byId(if (dark) settings.darkScheme else settings.lightScheme, dark).withCursor(accent)
    // Spelled out rather than produceState, whose lint check misses this assignment.
    var typefaces by remember { mutableStateOf<TerminalTypefaces?>(null) }
    LaunchedEffect(settings.font, fontChanges) { typefaces = app.fonts.typefaces(settings.font) }

    /** Reconnect along the node's route, asking for any unsaved passwords it needs first. */
    fun retry() {
        val needed = app.passwordsToAsk(app.nodes.get(node.id) ?: node)
        if (needed.isEmpty()) app.reconnect(node, emptyMap(), dark) else askPasswords = needed
    }

    LaunchedEffect(scheme, state) { connection.setTheme(scheme.toJson().toString()) }
    LaunchedEffect(typefaces, terminal) { typefaces?.let { terminal?.setTypefaces(it) } }
    LaunchedEffect(settings.fontSize, terminal) { terminal?.fontSizeSp = settings.fontSize }
    LaunchedEffect(settings.keepScreenOn, terminal) { terminal?.keepScreenOn = settings.keepScreenOn }
    LaunchedEffect(focusPane, state) {
        if (focusPane != null && state is Connection.State.Connected) connection.focusPane(focusPane)
    }
    LaunchedEffect(frame) { terminal?.frame = frame }
    LaunchedEffect(connection) {
        connection.events.collect { event ->
            when (event) {
                is Connection.Event.Clipboard -> context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Bellterm", event.text))
                is Connection.Event.Bell -> if (settings.vibrateOnBell) terminal?.performHapticFeedback(BellHaptic)
                is Connection.Event.Problem -> snackbar.showSnackbar(event.message)
                is Connection.Event.Notification -> launch {
                    val action = event.pane?.let { context.getString(R.string.show) }
                    val result = snackbar.showSnackbar(
                        listOfNotNull(event.title, event.body).joinToString(" — "),
                        actionLabel = action,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.pane?.let(connection::focusPane)
                }
            }
        }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen) { onLeave() }

    val workspace = view?.workspace
    val tabs = workspace?.let { view?.tabsOf(it.id) }.orEmpty()
    val tab = view?.tab
    val focusedPane = frame?.panes?.firstOrNull { it.focused }?.id ?: view?.focusedPane

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            SessionDrawer(
                node = node,
                view = view,
                onWorkspace = { connection.focusWorkspace(it.id); scope.launch { drawer.close() } },
                onAgent = { connection.focusPane(it.pane); scope.launch { drawer.close() } },
                onNewWorkspace = { connection.newWorkspace(workspace?.id); scope.launch { drawer.close() } },
            )
        },
    ) {
        Scaffold(
            // The column below pads for the keyboard and navigation bar itself.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        navigationIcon = {
                            IconButton(onClick = { terminal?.hideKeyboard(); scope.launch { drawer.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.workspaces))
                            }
                        },
                        title = {
                            Column {
                                Text(workspace?.label ?: node.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val detail = listOfNotNull(workspace?.branch, view?.panes?.firstOrNull { it.id == focusedPane }?.cwd)
                                if (detail.isNotEmpty()) {
                                    Text(
                                        detail.joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                                }
                                SessionMenu(
                                    expanded = menu,
                                    onDismiss = { menu = false },
                                    connection = connection,
                                    workspaceId = workspace?.id,
                                    workspaceLabel = workspace?.label,
                                    tabId = tab?.id,
                                    tabLabel = tab?.label,
                                    pane = focusedPane,
                                    zoomed = tab?.zoomed == true,
                                    onPrompt = { prompt = it },
                                    onPaste = { pasteClipboard(context, connection, focusedPane) },
                                    onLeave = onLeave,
                                    onDisconnect = { app.disconnect(node.id); onLeave() },
                                )
                            }
                        },
                    )
                    if (tabs.isNotEmpty()) {
                        val selected = tabs.indexOfFirst { it.id == tab?.id }.coerceAtLeast(0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = selected,
                                edgePadding = 8.dp,
                                modifier = Modifier.weight(1f),
                                containerColor = Color.Transparent,
                                indicator = {
                                    TabRowDefaults.PrimaryIndicator(
                                        Modifier.tabIndicatorOffset(selected, matchContentSize = true),
                                        width = Dp.Unspecified,
                                        color = accent,
                                    )
                                },
                                divider = {},
                            ) {
                                tabs.forEach { t ->
                                    Tab(
                                        selected = t.id == tab?.id,
                                        onClick = { connection.focusTab(t.id) },
                                        text = { TabLabel(t.label, t.status) },
                                        selectedContentColor = accent,
                                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { workspace?.let { prompt = Prompt.NewTab(it.id) } }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_tab))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // A reconnect is a new Connection: rebuild the view so its
                    // listener never feeds a closed one.
                    key(connection) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx).also { view ->
                                view.fontSizeSp = app.settings.settings.value.fontSize
                                typefaces?.let(view::setTypefaces)
                                view.listener = terminalListener(app, connection, view) { latched = it }
                                terminal = view
                            }
                        },
                        update = { view ->
                            view.latchedMeta = latched
                            view.frame = frame
                        },
                    )
                    }
                    if (state is Connection.State.Connecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                    // The node behind a rejected sign-in, so its password can be asked again.
                    val rejected = (state as? Connection.State.AuthRejected)?.let { rejection ->
                        app.nodes.route(app.nodes.get(node.id) ?: node).getOrNull()?.getOrNull(rejection.hop)
                    }
                    StateCard(
                        state = state,
                        rejectedPassword = rejected?.auth == AuthMethod.Password,
                        onRetry = ::retry,
                        onSignIn = { rejected?.let { askPasswords = listOf(it) } },
                        onTrust = { trusted ->
                            app.nodes.trust(trusted.host, trusted.fingerprint)
                            retry()
                        },
                        onLeave = { app.disconnect(node.id); onLeave() },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                ExtraKeysRow(
                    keys = DefaultExtraKeys,
                    latched = latched,
                    onLatch = { latched = it; terminal?.latchedMeta = it },
                    onKey = { code, repeat -> terminal?.sendKey(code, 0, 0, repeat) },
                    onText = { connection.text(focusedPane, it) },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }

    prompt?.let { current ->
        PromptDialog(current, connection) { prompt = null }
    }
    askPasswords?.let { hops ->
        PasswordsDialog(
            hops = hops,
            onConnect = { secrets, remember ->
                askPasswords = null
                app.reconnect(node, secrets, dark, remember)
            },
            onDismiss = { askPasswords = null },
        )
    }
}

/** REJECT, the closest haptic to a bell, exists from API 30; older devices get a long press. */
private val BellHaptic =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

private fun pasteClipboard(context: android.content.Context, connection: Connection, pane: String?) {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
    val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
    if (text.isNotEmpty()) connection.paste(pane, text)
}

private fun terminalListener(
    app: BelltermApp,
    connection: Connection,
    view: TerminalView,
    onLatchCleared: (Int) -> Unit,
) = object : TerminalView.Listener {
    private fun pane() = view.frame?.panes?.firstOrNull { it.focused }?.id

    override fun onGrid(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        app.settings.lastGrid = intArrayOf(cols, rows, cellWidth, cellHeight)
        connection.resize(cols, rows, cellWidth, cellHeight)
    }

    override fun onFocusPane(pane: RenderFrame.PaneArea) = connection.focusPane(pane.id)

    override fun onTap(pane: RenderFrame.PaneArea?, column: Int, row: Int) =
        connection.touch(pane?.id ?: "", Native.TOUCH_TAP, column, row)

    override fun onScroll(pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Boolean, lines: Int) =
        connection.touch(pane?.id ?: "", if (up) Native.TOUCH_SCROLL_UP else Native.TOUCH_SCROLL_DOWN, column, row, lines)

    override fun onKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) =
        connection.key(pane(), keyCode, metaState, unicode, repeat)

    override fun onText(text: String) = connection.text(pane(), text)

    override fun onChord(codepoint: Int, metaState: Int) = connection.chord(pane(), codepoint, metaState)

    override fun onCopy(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int) {
        val text = connection.selectionText(startColumn, startRow, endColumn, endRow)
        if (text.isNotEmpty()) {
            view.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Bellterm", text))
        }
    }

    override fun onPaste() = pasteClipboard(view.context, connection, pane())

    override fun onFontSize(sp: Float) {
        app.settings.update { it.copy(fontSize = sp.coerceIn(8f, 24f)) }
    }

    override fun onModifiersConsumed() = onLatchCleared(0)
}

@Composable
private fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    connection: Connection,
    workspaceId: String?,
    workspaceLabel: String?,
    tabId: String?,
    tabLabel: String?,
    pane: String?,
    zoomed: Boolean,
    onPrompt: (Prompt) -> Unit,
    onPaste: () -> Unit,
    onLeave: () -> Unit,
    onDisconnect: () -> Unit,
) {
    fun item(label: Int, enabled: Boolean = true, run: () -> Unit): @Composable () -> Unit = {
        DropdownMenuItem(
            text = { Text(stringResource(label)) },
            enabled = enabled,
            onClick = { onDismiss(); run() },
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        item(R.string.paste) { onPaste() }()
        HorizontalDivider()
        item(R.string.split_right, pane != null) { pane?.let { connection.split(it, "right") } }()
        item(R.string.split_down, pane != null) { pane?.let { connection.split(it, "down") } }()
        item(if (zoomed) R.string.unzoom_pane else R.string.zoom_pane, pane != null) { pane?.let(connection::toggleZoom) }()
        item(R.string.close_pane, pane != null) { pane?.let { onPrompt(Prompt.ClosePane(it)) } }()
        HorizontalDivider()
        item(R.string.new_tab, workspaceId != null) { workspaceId?.let(connection::newTab) }()
        item(R.string.rename_tab, tabId != null) { tabId?.let { onPrompt(Prompt.RenameTab(it, tabLabel.orEmpty())) } }()
        item(R.string.close_tab, tabId != null) { tabId?.let { onPrompt(Prompt.CloseTab(it, tabLabel.orEmpty())) } }()
        HorizontalDivider()
        item(R.string.rename_workspace, workspaceId != null) {
            workspaceId?.let { onPrompt(Prompt.RenameWorkspace(it, workspaceLabel.orEmpty())) }
        }()
        item(R.string.close_workspace, workspaceId != null) {
            workspaceId?.let { onPrompt(Prompt.CloseWorkspace(it, workspaceLabel.orEmpty())) }
        }()
        HorizontalDivider()
        item(R.string.nodes) { onLeave() }()
        item(R.string.disconnect) { onDisconnect() }()
    }
}

@Composable
private fun StateCard(
    state: Connection.State,
    rejectedPassword: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onTrust: (Connection.State.UntrustedHostKey) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body) = when (state) {
        is Connection.State.Connecting, is Connection.State.Connected -> return
        is Connection.State.Disconnected -> stringResource(R.string.disconnected) to state.reason
        is Connection.State.AuthRejected -> stringResource(R.string.sign_in_rejected) to stringResource(
            if (rejectedPassword) R.string.password_rejected_text else R.string.key_rejected_text,
            state.host,
        )
        is Connection.State.Incompatible -> {
            val version = state.serverVersion ?: stringResource(R.string.unknown_version)
            if (state.daemonOutdated) {
                stringResource(R.string.incompatible_daemon_title) to
                    stringResource(R.string.incompatible_daemon_text, version, state.minVersion)
            } else {
                stringResource(R.string.incompatible_app_title) to stringResource(R.string.incompatible_app_text, version)
            }
        }
        is Connection.State.Failed -> stringResource(R.string.could_not_connect) to state.reason
        is Connection.State.UntrustedHostKey -> stringResource(
            if (state.changed) R.string.host_key_changed else R.string.host_key_unknown,
        ) to stringResource(
            if (state.changed) R.string.host_key_changed_text else R.string.host_key_unknown_text,
            if (state.hop > 0) stringResource(R.string.host_on_hop, state.host, state.hop + 1) else state.host,
            state.fingerprint,
        )
    }
    Card(modifier.padding(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is Connection.State.UntrustedHostKey -> Button(onClick = { onTrust(state) }) {
                        Text(stringResource(R.string.trust_and_connect))
                    }
                    is Connection.State.AuthRejected -> if (rejectedPassword) {
                        Button(onClick = onSignIn) { Text(stringResource(R.string.enter_password)) }
                    } else {
                        Button(onClick = onRetry) { Text(stringResource(R.string.reconnect)) }
                    }
                    else -> Button(onClick = onRetry) { Text(stringResource(R.string.reconnect)) }
                }
                OutlinedButton(onClick = onLeave) { Text(stringResource(R.string.nodes)) }
            }
        }
    }
}
