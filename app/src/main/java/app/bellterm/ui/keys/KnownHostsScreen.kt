package app.bellterm.ui.keys

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.ui.components.ConfirmDialog
import app.bellterm.ui.components.Group
import app.bellterm.ui.components.ItemRow

/** Host keys the user trusted, by `host:port`; forgetting one re-prompts on the next connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(app: BelltermApp, onBack: () -> Unit) {
    val known by app.nodes.knownHosts.collectAsState()
    val nodes by app.nodes.nodes.collectAsState()
    var forgetting by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.known_hosts)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Text(
                    stringResource(if (known.isEmpty()) R.string.known_hosts_empty else R.string.known_hosts_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (known.isNotEmpty()) {
                item {
                    Group {
                        known.entries.sortedBy { it.key }.forEachIndexed { index, (host, fingerprint) ->
                            if (index > 0) HorizontalDivider()
                            val names = nodes.filter { it.hostKeyName == host }.joinToString(", ") { it.title }
                            ItemRow(
                                title = names.ifEmpty { host },
                                detail = (if (names.isEmpty()) "" else "$host\n") + fingerprint,
                                trailing = {
                                    IconButton(onClick = { forgetting = host }) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.forget), tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    forgetting?.let { host ->
        ConfirmDialog(
            title = stringResource(R.string.forget_host_title, host),
            text = stringResource(R.string.forget_host_text),
            confirm = stringResource(R.string.forget),
            onConfirm = { app.nodes.forget(host); forgetting = null },
            onDismiss = { forgetting = null },
        )
    }
}
