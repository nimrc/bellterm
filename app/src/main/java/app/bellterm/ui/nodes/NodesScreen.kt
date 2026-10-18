package app.bellterm.ui.nodes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val shown = nodes
        .filter { node ->
            query.isBlank() || listOf(node.title, node.address, node.user, node.group).any { it.contains(query.trim(), ignoreCase = true) }
        }
        .sortedWith(compareByDescending<Node> { connections[it.id]?.isOpen == true }.thenByDescending { it.lastUsed }.thenBy { it.title.lowercase() })
    val groups = shown.groupBy { it.group.trim() }.toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        if (connections.values.any { it.isOpen }) {
                            Pill(stringResource(R.string.attached_count, connections.values.count { it.isOpen }), MaterialTheme.colorScheme.tertiary, dot = true)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = navigation::keys) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.keys))
                    }
                    IconButton(onClick = { help = true }) {
                        Icon(painterResource(R.drawable.ic_help), contentDescription = stringResource(R.string.help))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.import_ssh_config)) }, onClick = { menu = false; navigation.importConfig() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.known_hosts)) }, onClick = { menu = false; navigation.knownHosts() })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menu = false; navigation.settings() },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            // The empty state offers its own buttons.
            if (nodes.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { navigation.edit(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_node)) },
            )
        },
    ) { padding ->
        if (nodes.isEmpty()) {
            EmptyNodes(Modifier.padding(padding), navigation)
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 104.dp),
        ) {
            item(key = "update") { UpdateBanner(app.updates) }
            if (searching) {
                item {
                    OutlinedTextField(
                        query, { query = it },
                        placeholder = { Text(stringResource(R.string.search_nodes)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
private fun EmptyNodes(modifier: Modifier, navigation: NodesNavigation) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NodeAvatar("B", 0, size = 72.dp)
            Text(stringResource(R.string.no_nodes_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(4.dp))
            Button(onClick = { navigation.edit(null) }) { Text(stringResource(R.string.add_node)) }
            OutlinedButton(onClick = navigation::importConfig) { Text(stringResource(R.string.import_ssh_config)) }
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
) {
    var menu by remember { mutableStateOf(false) }
    val state = connection?.state?.collectAsState()?.value
    val view = connection?.view?.collectAsState()?.value
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NodeAvatar(node.title, node.color)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(node.destination)
                        if (node.session != "default") append(" · ").append(node.session)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pills = jump != null || hasCertificate || state != null
                if (pills) {
                    FlowRow(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.connect)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; onDuplicate() })
                    if (connection != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.disconnect)) }, onClick = { menu = false; onDisconnect() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
