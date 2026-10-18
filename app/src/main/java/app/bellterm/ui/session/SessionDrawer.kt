package app.bellterm.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bellterm.R
import app.bellterm.core.Agent
import app.bellterm.core.AgentStatus
import app.bellterm.core.ShellView
import app.bellterm.core.Workspace
import app.bellterm.data.Node
import app.bellterm.ui.components.NodeAvatar
import app.bellterm.ui.theme.color

/**
 * Herdr's sidebar as a navigation drawer: every workspace with its branch and
 * the daemon's status dot, and every agent. Tabs are switched from the
 * session's own tab row, not here.
 */
@Composable
fun SessionDrawer(
    node: Node,
    view: ShellView?,
    onWorkspace: (Workspace) -> Unit,
    onAgent: (Agent) -> Unit,
    onNewWorkspace: () -> Unit,
) {
    ModalDrawerSheet {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NodeAvatar(node.title, node.color, size = 48.dp)
                    Column {
                        Text(node.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            node.destination + if (node.session != "default") " · ${node.session}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.workspaces)) }
            items(view?.workspaces.orEmpty(), key = { "w" + it.id }) { workspace ->
                NavigationDrawerItem(
                    selected = workspace.focused,
                    onClick = { onWorkspace(workspace) },
                    icon = { StatusDot(workspace.status) },
                    label = {
                        Column {
                            Text(workspace.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            workspaceDetail(workspace)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    badge = { Text(workspace.number.toString(), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).padding(vertical = 2.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    selected = false,
                    onClick = onNewWorkspace,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text(stringResource(R.string.new_workspace)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            val agents = view?.agents.orEmpty()
            if (agents.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionLabel(stringResource(R.string.agents))
                }
                items(agents, key = { "a" + it.pane }) { agent ->
                    val workspace = view?.workspaces?.firstOrNull { it.id == agent.workspace }
                    NavigationDrawerItem(
                        selected = agent.focused,
                        onClick = { onAgent(agent) },
                        icon = { StatusDot(agent.status) },
                        label = {
                            Column {
                                Text(agent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(statusLabel(agent.status), workspace?.label, agent.title)
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

private fun workspaceDetail(workspace: Workspace): String? {
    val parts = mutableListOf<String>()
    workspace.branch?.let { parts += it }
    val ahead = workspace.ahead ?: 0
    val behind = workspace.behind ?: 0
    if (ahead > 0) parts += "↑$ahead"
    if (behind > 0) parts += "↓$behind"
    if (workspace.linkedWorktree) workspace.worktree?.let { parts += "⎇ $it" }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}

@Composable
fun statusLabel(status: AgentStatus): String? = when (status) {
    AgentStatus.Working -> stringResource(R.string.status_working)
    AgentStatus.Blocked -> stringResource(R.string.status_blocked)
    AgentStatus.Done -> stringResource(R.string.status_done)
    AgentStatus.Idle -> stringResource(R.string.status_idle)
    AgentStatus.Unknown -> null
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun StatusDot(status: AgentStatus, small: Boolean = false) {
    val size = if (small) 8.dp else 10.dp
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (status != AgentStatus.Unknown) {
            Box(Modifier.size(size).clip(CircleShape).background(status.color()))
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(Color.Transparent))
        }
    }
}

@Composable
fun TabLabel(label: String, status: AgentStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status != AgentStatus.Unknown) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(status.color()))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
