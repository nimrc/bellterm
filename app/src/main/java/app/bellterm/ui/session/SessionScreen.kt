package app.bellterm.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.core.AgentStatus
import app.bellterm.core.Connection
import app.bellterm.core.Native
import app.bellterm.core.RenderFrame
import app.bellterm.core.Tab
import app.bellterm.data.AuthMethod
import app.bellterm.data.Node
import app.bellterm.ui.components.PasswordsDialog
import app.bellterm.ui.glass.GlassButton
import app.bellterm.ui.glass.GlassIconButton
import app.bellterm.ui.glass.GlassMenu
import app.bellterm.ui.glass.GlassSnackbarHost
import app.bellterm.ui.glass.GroupShape
import app.bellterm.ui.glass.IconBadge
import app.bellterm.ui.glass.LocalGlassBackdrop
import app.bellterm.ui.glass.LocalGlassStyle
import app.bellterm.ui.glass.captureBackdrop
import app.bellterm.ui.glass.glass
import app.bellterm.ui.terminal.DefaultExtraKeys
import app.bellterm.ui.terminal.ExtraKeysRow
import app.bellterm.ui.terminal.TerminalTypefaces
import app.bellterm.ui.terminal.TerminalView
import app.bellterm.ui.theme.NodeColors
import app.bellterm.ui.theme.TerminalSchemes
import app.bellterm.ui.theme.color
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop
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

    val chromeColor = Color(0xff000000 or scheme.background)
    val chrome = rememberLayerBackdrop()

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        drawerContent = {
            SessionDrawer(
                node = node,
                view = view,
                state = state,
                accent = accent,
                onWorkspace = { connection.focusWorkspace(it.id); scope.launch { drawer.close() } },
                onAgent = { connection.focusPane(it.pane); scope.launch { drawer.close() } },
                onNewWorkspace = { connection.newWorkspace(workspace?.id); scope.launch { drawer.close() } },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(chromeColor).imePadding()) {
            // A flat wash of the node's colour behind the header, lent to the glass controls on it.
            val headerColor = accent.copy(alpha = if (dark) 0.14f else 0.1f).compositeOver(chromeColor)
            Box {
                Box(
                    Modifier
                        .matchParentSize()
                        .captureBackdrop(chrome)
                        .background(headerColor),
                )
                CompositionLocalProvider(LocalGlassBackdrop provides chrome) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GlassIconButton(
                                Icons.Default.Menu,
                                stringResource(R.string.workspaces),
                                onClick = { terminal?.hideKeyboard(); scope.launch { drawer.open() } },
                                modifier = Modifier.testTag("session:drawer"),
                            )
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ConnectionDot(state)
                                    Text(
                                        workspace?.label ?: node.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("session:title"),
                                    )
                                }
                                val detail = listOfNotNull(workspace?.branch, view?.panes?.firstOrNull { it.id == focusedPane }?.cwd)
                                Text(
                                    detail.joinToString(" · ").ifEmpty { node.destination },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box {
                                GlassIconButton(
                                    Icons.Default.MoreVert,
                                    stringResource(R.string.more),
                                    onClick = { menu = true },
                                    modifier = Modifier.testTag("session:more"),
                                )
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
                        }
                        if (tabs.isNotEmpty()) {
                            TabStrip(
                                tabs = tabs,
                                selectedId = tab?.id,
                                accent = accent,
                                dark = dark,
                                onTab = { connection.focusTab(it) },
                                onNewTab = { workspace?.let { prompt = Prompt.NewTab(it.id) } },
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(accent.copy(alpha = 0.6f)),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // A reconnect is a new Connection: rebuild the view so its
                // listener never feeds a closed one.
                key(connection) {
                    AndroidView(
                        // TerminalView fills its canvas with drawColor; unclipped it would paint over the header.
                        modifier = Modifier.fillMaxSize().clipToBounds().testTag("terminal"),
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
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                        color = accent,
                        trackColor = Color.Transparent,
                    )
                }
                // The node behind a rejected sign-in, so its password can be asked again.
                val rejected = (state as? Connection.State.AuthRejected)?.let { rejection ->
                    app.nodes.route(app.nodes.get(node.id) ?: node).getOrNull()?.getOrNull(rejection.hop)
                }
                StateCard(
                    state = state,
                    accent = accent,
                    rejectedPassword = rejected?.auth == AuthMethod.Password,
                    onRetry = ::retry,
                    onSignIn = { rejected?.let { askPasswords = listOf(it) } },
                    onTrust = { trusted ->
                        app.nodes.trust(trusted.host, trusted.fingerprint)
                        retry()
                    },
                    onLeave = { app.disconnect(node.id); onLeave() },
                )
                GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            ExtraKeysRow(
                keys = DefaultExtraKeys,
                latched = latched,
                accent = accent,
                background = chromeColor,
                onLatch = { latched = it; terminal?.latchedMeta = it },
                onKey = { code, repeat -> terminal?.sendKey(code, 0, 0, repeat) },
                onText = { connection.text(focusedPane, it) },
                modifier = Modifier.navigationBarsPadding(),
            )
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
    @Composable
    fun item(label: Int, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, run: () -> Unit) {
        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        DropdownMenuItem(
            text = { Text(stringResource(label), color = if (enabled) color else color.copy(alpha = 0.38f)) },
            leadingIcon = { Icon(icon, null, tint = (if (enabled) color else color.copy(alpha = 0.38f)).copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) },
            enabled = enabled,
            onClick = { onDismiss(); run() },
            contentPadding = PaddingValues(horizontal = 18.dp),
        )
    }

    @Composable
    fun divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        item(R.string.paste, Icons.Default.Create) { onPaste() }
        divider()
        item(R.string.split_right, Icons.AutoMirrored.Filled.KeyboardArrowRight, pane != null) { pane?.let { connection.split(it, "right") } }
        item(R.string.split_down, Icons.Default.KeyboardArrowDown, pane != null) { pane?.let { connection.split(it, "down") } }
        item(if (zoomed) R.string.unzoom_pane else R.string.zoom_pane, Icons.Default.Search, pane != null) { pane?.let(connection::toggleZoom) }
        item(R.string.close_pane, Icons.Default.Close, pane != null, destructive = true) { pane?.let { onPrompt(Prompt.ClosePane(it)) } }
        divider()
        item(R.string.new_tab, Icons.Default.Add, workspaceId != null) { workspaceId?.let(connection::newTab) }
        item(R.string.rename_tab, Icons.Default.Edit, tabId != null) { tabId?.let { onPrompt(Prompt.RenameTab(it, tabLabel.orEmpty())) } }
        item(R.string.close_tab, Icons.Default.Close, tabId != null, destructive = true) { tabId?.let { onPrompt(Prompt.CloseTab(it, tabLabel.orEmpty())) } }
        divider()
        item(R.string.rename_workspace, Icons.Default.Edit, workspaceId != null) {
            workspaceId?.let { onPrompt(Prompt.RenameWorkspace(it, workspaceLabel.orEmpty())) }
        }
        item(R.string.close_workspace, Icons.Default.Delete, workspaceId != null, destructive = true) {
            workspaceId?.let { onPrompt(Prompt.CloseWorkspace(it, workspaceLabel.orEmpty())) }
        }
        divider()
        item(R.string.nodes, Icons.Default.Home) { onLeave() }
        item(R.string.disconnect, Icons.AutoMirrored.Filled.ExitToApp, destructive = true) { onDisconnect() }
    }
}

/** A dot for the connection: green attached, pulsing blue while connecting, red otherwise. */
@Composable
private fun ConnectionDot(state: Connection.State) {
    val color = when (state) {
        is Connection.State.Connected -> AgentStatus.Done.color()
        is Connection.State.Connecting -> AgentStatus.Working.color()
        else -> MaterialTheme.colorScheme.error
    }
    val pulse = if (state is Connection.State.Connecting) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(9.dp)
            .graphicsLayer { alpha = pulse }
            .clip(CircleShape)
            .background(color)
            .testTag("session:state:" + state::class.simpleName),
    )
}

/** The focused workspace's tabs as pills in a glass track; the chosen one wears the node's colour. */
@Composable
private fun TabStrip(
    tabs: List<Tab>,
    selectedId: String?,
    accent: Color,
    dark: Boolean,
    onTab: (String) -> Unit,
    onNewTab: () -> Unit,
) {
    val list = rememberLazyListState()
    val selected = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    LaunchedEffect(selected, tabs.size) { list.animateScrollToItem(selected) }
    val accentText = if (dark) lerp(accent, Color.White, 0.35f) else lerp(accent, Color.Black, 0.2f)
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(
            state = list,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .glass(CircleShape, highlight = LocalGlassStyle.current.smallHighlight)
                .padding(3.dp)
                .testTag("session:tabs"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(tabs, key = { _, t -> t.id }) { _, t ->
                val chosen = t.id == selectedId
                val fill by animateColorAsState(if (chosen) accent.copy(alpha = if (dark) 0.3f else 0.18f) else Color.Transparent, label = "tab")
                val text by animateColorAsState(if (chosen) accentText else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabText")
                Row(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 52.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .clickable(role = Role.Tab) { onTab(t.id) }
                        .padding(horizontal = 14.dp)
                        .testTag("tab:${t.label}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (t.status != AgentStatus.Unknown) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(t.status.color()))
                    }
                    Text(
                        t.label,
                        color = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GlassIconButton(Icons.Default.Add, stringResource(R.string.new_tab), onClick = onNewTab, size = 40.dp, modifier = Modifier.testTag("session:new_tab"))
    }
}

@Composable
private fun StateCard(
    state: Connection.State,
    accent: Color,
    rejectedPassword: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onTrust: (Connection.State.UntrustedHostKey) -> Unit,
    onLeave: () -> Unit,
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
    val warning = state is Connection.State.UntrustedHostKey && state.changed
    val (icon, tone) = when (state) {
        is Connection.State.UntrustedHostKey -> Icons.Default.Lock to if (warning) MaterialTheme.colorScheme.error else accent
        is Connection.State.AuthRejected -> Icons.Default.Lock to MaterialTheme.colorScheme.error
        is Connection.State.Incompatible -> Icons.Default.Warning to AgentStatus.Blocked.color()
        else -> Icons.Default.Refresh to MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(20.dp)
            .testTag("session:state_card"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .glass(GroupShape, tint = LocalGlassStyle.current.strongTint, shadow = true)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(icon, tone, Modifier.size(44.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                GlassButton(stringResource(R.string.nodes), onClick = onLeave, modifier = Modifier.weight(1f))
                val (label, action) = when (state) {
                    is Connection.State.UntrustedHostKey -> stringResource(R.string.trust_and_connect) to { onTrust(state) }
                    is Connection.State.AuthRejected -> if (rejectedPassword) {
                        stringResource(R.string.enter_password) to onSignIn
                    } else {
                        stringResource(R.string.reconnect) to onRetry
                    }
                    else -> stringResource(R.string.reconnect) to onRetry
                }
                GlassButton(
                    label,
                    onClick = action,
                    primary = true,
                    accent = if (warning) MaterialTheme.colorScheme.error else accent,
                    modifier = Modifier.weight(1.4f).testTag("session:primary_action"),
                )
            }
        }
    }
}
