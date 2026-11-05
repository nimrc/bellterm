package app.bellterm.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bellterm.R
import app.bellterm.core.Agent
import app.bellterm.core.AgentStatus
import app.bellterm.core.Connection
import app.bellterm.core.ShellView
import app.bellterm.core.Workspace
import app.bellterm.data.Node
import app.bellterm.ui.components.NodeAvatar
import app.bellterm.ui.components.Pill
import app.bellterm.ui.glass.GlassButton
import app.bellterm.ui.glass.menuColor
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
    state: Connection.State,
    accent: Color,
    onWorkspace: (Workspace) -> Unit,
    onAgent: (Agent) -> Unit,
    onNewWorkspace: () -> Unit,
) {
    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
        drawerContainerColor = menuColor(),
        drawerTonalElevation = 0.dp,
        modifier = Modifier.testTag("session:drawer_sheet"),
    ) {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item {
                Row(
                    Modifier
                        .padding(top = 20.dp, bottom = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.06f))))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NodeAvatar(node.title, node.color, size = 52.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            node.destination + if (node.session != "default") " · ${node.session}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        when (state) {
                            is Connection.State.Connected -> Pill(
                                stringResource(R.string.attached_summary, view?.workspaces?.size ?: 0, view?.agents?.size ?: 0),
                                AgentStatus.Done.color(),
                                dot = true,
                            )
                            is Connection.State.Connecting -> Pill(stringResource(R.string.connecting), AgentStatus.Working.color(), dot = true)
                            else -> Pill(stringResource(R.string.disconnected), MaterialTheme.colorScheme.error, dot = true)
                        }
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.workspaces)) }
            items(view?.workspaces.orEmpty(), key = { "w" + it.id }) { workspace ->
                DrawerRow(
                    selected = workspace.focused,
                    accent = accent,
                    onClick = { onWorkspace(workspace) },
                    leading = { StatusDot(workspace.status) },
                    title = workspace.label,
                    detail = workspaceDetail(workspace),
                    badge = workspace.number.toString(),
                    modifier = Modifier.testTag("workspace:${workspace.label}"),
                )
            }
            item {
                GlassButton(
                    stringResource(R.string.new_workspace),
                    onClick = onNewWorkspace,
                    icon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            val agents = view?.agents.orEmpty()
            if (agents.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.agents)) }
                items(agents, key = { "a" + it.pane }) { agent ->
                    val workspace = view?.workspaces?.firstOrNull { it.id == agent.workspace }
                    DrawerRow(
                        selected = agent.focused,
                        accent = accent,
                        onClick = { onAgent(agent) },
                        leading = { StatusDot(agent.status) },
                        title = agent.displayName,
                        detail = listOfNotNull(statusLabel(agent.status), workspace?.label, agent.title).joinToString(" · "),
                    )
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun DrawerRow(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    detail: String?,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, label = "row")
    Row(
        modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
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
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
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
