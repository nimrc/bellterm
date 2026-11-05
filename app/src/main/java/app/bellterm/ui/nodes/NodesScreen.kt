package app.bellterm.ui.nodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.core.AgentStatus
import app.bellterm.core.Connection
import app.bellterm.data.AuthMethod
import app.bellterm.data.Keychain
import app.bellterm.data.Node
import app.bellterm.ui.components.ConfirmDialog
import app.bellterm.ui.components.HelpDialog
import app.bellterm.ui.components.NodeAvatar
import app.bellterm.ui.components.Pill
import app.bellterm.ui.components.SectionHeader
import app.bellterm.ui.components.UpdateBanner
import app.bellterm.ui.glass.CapsuleAction
import app.bellterm.ui.glass.GlassButton
import app.bellterm.ui.glass.GlassCapsule
import app.bellterm.ui.glass.GlassField
import app.bellterm.ui.glass.GlassMenu
import app.bellterm.ui.glass.GlassScreen
import app.bellterm.ui.glass.GroupShape
import app.bellterm.ui.glass.glass
import app.bellterm.ui.glass.pressScale
import app.bellterm.ui.theme.NodeColors
import app.bellterm.ui.theme.color

/** Where the nodes screen can send the user. */
interface NodesNavigation {
    fun open(node: Node)
    fun edit(node: Node?)
    fun importConfig()
    fun keys()
    fun knownHosts()
    fun settings()
}

/** Saved nodes, grouped, most recently used first. Tapping one attaches to its Herdr session. */
@Composable
fun NodesScreen(app: BelltermApp, navigation: NodesNavigation) {
    val nodes by app.nodes.nodes.collectAsState()
    val connections by app.connections.collectAsState()
    val identities by app.keychain.identities.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Node?>(null) }
    var help by remember { mutableStateOf(false) }

    val shown = nodes
        .filter { node ->
            query.isBlank() || listOf(node.title, node.address, node.user, node.group).any { it.contains(query.trim(), ignoreCase = true) }
        }
        .sortedWith(compareByDescending<Node> { connections[it.id]?.isOpen == true }.thenByDescending { it.lastUsed }.thenBy { it.title.lowercase() })
    val groups = shown.groupBy { it.group.trim() }.toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    val attached = connections.values.count { it.isOpen }

    GlassScreen(
        title = stringResource(R.string.app_name),
        subtitle = if (nodes.isEmpty()) null else pluralStringResource(R.plurals.node_count, nodes.size, nodes.size),
        large = true,
        titleAccessory = if (attached > 0) {
            { Pill(stringResource(R.string.attached_count, attached), MaterialTheme.colorScheme.tertiary, dot = true) }
        } else {
            null
        },
        actions = {
            GlassCapsule {
                if (nodes.isNotEmpty()) {
                    CapsuleAction(Icons.Default.Search, stringResource(R.string.search), onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }, modifier = Modifier.testTag("action:search"))
                }
                CapsuleAction(Icons.Default.Lock, stringResource(R.string.keys), onClick = navigation::keys, modifier = Modifier.testTag("action:keys"))
                Box {
                    CapsuleAction(Icons.Default.MoreVert, stringResource(R.string.more), onClick = { menu = true }, modifier = Modifier.testTag("action:more"))
                    GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(R.string.import_ssh_config, Icons.Default.Add) { menu = false; navigation.importConfig() }
                        MenuItem(R.string.known_hosts, Icons.Default.Lock) { menu = false; navigation.knownHosts() }
                        MenuItem(R.string.settings, Icons.Default.Settings) { menu = false; navigation.settings() }
                        MenuItem(R.string.help, painterResource(R.drawable.ic_help)) { menu = false; help = true }
                    }
                }
            }
        },
        // The empty state offers its own buttons.
        bottomBar = if (nodes.isEmpty()) null else {
            {
                GlassButton(
                    stringResource(R.string.add_node),
                    onClick = { navigation.edit(null) },
                    primary = true,
                    icon = Icons.Default.Add,
                    modifier = Modifier.testTag("add_node"),
                )
            }
        },
    ) { padding ->
        if (nodes.isEmpty()) {
            EmptyNodes(Modifier.padding(padding), navigation)
            return@GlassScreen
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag("nodes")) {
            item(key = "update") { UpdateBanner(app.updates) }
            item(key = "search") {
                AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    GlassField(
                        query, { query = it },
                        placeholder = { Text(stringResource(R.string.search_nodes)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(focus).testTag("search_field"),
                    )
                }
            }
            if (shown.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        stringResource(R.string.no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            groups.forEach { (group, members) ->
                item(key = "g:$group") {
                    SectionHeader(group.ifEmpty { if (groups.size > 1) stringResource(R.string.ungrouped) else stringResource(R.string.nodes) })
                }
                items(members, key = { it.id }) { node ->
                    NodeCard(
                        node = node,
                        jump = app.nodes.get(node.jumpId),
                        hasCertificate = node.auth == AuthMethod.Key && identities.firstOrNull { it.id == (node.identityId ?: Keychain.DEVICE) }?.certificate != null,
                        connection = connections[node.id],
                        onOpen = { navigation.open(node) },
                        onEdit = { navigation.edit(node) },
                        onDuplicate = { app.nodes.upsert(node.copy(id = java.util.UUID.randomUUID().toString(), name = node.title + " (2)", lastUsed = 0)) },
                        onDisconnect = { app.disconnect(node.id) },
                        onDelete = { deleting = node },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (help) HelpDialog(onDismiss = { help = false })

    deleting?.let { node ->
        ConfirmDialog(
            title = stringResource(R.string.delete_node_title, node.title),
            text = stringResource(R.string.delete_node_text),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                app.disconnect(node.id)
                app.nodes.delete(node)
                app.keychain.forgetPassword(node.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) =
    MenuItem(label, rememberVectorPainter(icon), destructive, onClick)

@Composable
private fun MenuItem(label: Int, icon: Painter, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(stringResource(label), color = color) },
        leadingIcon = { Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp),
    )
}

@Composable
private fun EmptyNodes(modifier: Modifier, navigation: NodesNavigation) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .glass(GroupShape, shadow = true)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NodeAvatar("B", 0, size = 76.dp)
            Text(stringResource(R.string.no_nodes_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.no_nodes_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            GlassButton(
                stringResource(R.string.add_node),
                onClick = { navigation.edit(null) },
                primary = true,
                icon = Icons.Default.Add,
                modifier = Modifier.fillMaxWidth().testTag("add_node"),
            )
            GlassButton(stringResource(R.string.import_ssh_config), onClick = navigation::importConfig, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun NodeCard(
    node: Node,
    jump: Node?,
    hasCertificate: Boolean,
    connection: Connection?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val state = connection?.state?.collectAsState()?.value
    val view = connection?.view?.collectAsState()?.value
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(interaction, 0.97f)
                .glass(GroupShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 6.dp)
                .testTag("node:${node.title}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                NodeAvatar(node.title, node.color, size = 50.dp)
                if (state is Connection.State.Connected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(3.dp, 3.dp)
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(AgentStatus.Done.color()),
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(node.destination)
                        if (node.session != "default") append(" · ").append(node.session)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pills = jump != null || hasCertificate || state != null
                if (pills) {
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when (state) {
                            is Connection.State.Connected -> Pill(
                                stringResource(R.string.attached_summary, view?.workspaces?.size ?: 0, view?.agents?.size ?: 0),
                                MaterialTheme.colorScheme.tertiary,
                                dot = true,
                            )
                            is Connection.State.Connecting -> Pill(stringResource(R.string.connecting), MaterialTheme.colorScheme.secondary, dot = true)
                            null -> {}
                            else -> Pill(stringResource(R.string.disconnected), MaterialTheme.colorScheme.error, dot = true)
                        }
                        view?.agents?.filter { it.status == AgentStatus.Blocked }?.take(2)?.forEach {
                            Pill(stringResource(R.string.needs_input_named, it.displayName), AgentStatus.Blocked.color(), dot = true)
                        }
                        jump?.let { Pill(stringResource(R.string.via_jump, it.title), NodeColors.of(it.color)) }
                        if (hasCertificate) Pill(stringResource(R.string.certificate), MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("node_more:${node.title}")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(R.string.connect, Icons.Default.PlayArrow) { menu = false; onOpen() }
                    MenuItem(R.string.edit, Icons.Default.Edit) { menu = false; onEdit() }
                    MenuItem(R.string.duplicate, Icons.Default.Add) { menu = false; onDuplicate() }
                    if (connection != null) {
                        MenuItem(R.string.disconnect, Icons.Default.Close) { menu = false; onDisconnect() }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    MenuItem(R.string.delete, Icons.Default.Delete, destructive = true) { menu = false; onDelete() }
                }
            }
        }
    }
}
