package app.bellterm.ui.nodes

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.core.Native
import app.bellterm.data.Node
import app.bellterm.ui.components.Group
import app.bellterm.ui.components.NodeAvatar
import app.bellterm.ui.components.Pill
import app.bellterm.ui.components.SectionHeader
import org.json.JSONObject

/** One `Host` alias from an OpenSSH configuration, as `ssh_config.rs` reads it. */
data class ConfigEntry(
    val alias: String,
    val hostName: String?,
    val user: String?,
    val port: Int?,
    val proxyJump: List<String>,
    val identityFile: String?,
    val serverAliveInterval: Int?,
)

/** Turns parsed entries into nodes, with `ProxyJump` hops as jump-host nodes. */
object ConfigImport {
    fun parse(json: String): Pair<List<ConfigEntry>, List<String>> {
        val o = JSONObject(json)
        val entries = o.getJSONArray("entries")
        val skipped = o.getJSONArray("skipped")
        return List(entries.length()) {
            val e = entries.getJSONObject(it)
            val jumps = e.getJSONArray("proxy_jump")
            ConfigEntry(
                alias = e.getString("alias"),
                hostName = e.optString("host_name").takeUnless { _ -> e.isNull("host_name") },
                user = e.optString("user").takeUnless { _ -> e.isNull("user") },
                port = if (e.isNull("port")) null else e.getInt("port"),
                proxyJump = List(jumps.length()) { i -> jumps.getString(i) },
                identityFile = e.optString("identity_file").takeUnless { _ -> e.isNull("identity_file") },
                serverAliveInterval = if (e.isNull("server_alive_interval")) null else e.getInt("server_alive_interval"),
            )
        } to List(skipped.length()) { skipped.getString(it) }
    }

    /**
     * Nodes for [selected], plus a node for every jump hop that is not itself
     * an imported alias or an existing node. [fallbackUser] fills entries
     * without a `User`.
     */
    fun nodes(selected: List<ConfigEntry>, all: List<ConfigEntry>, existing: List<Node>, fallbackUser: String, group: String): List<Node> {
        val made = mutableMapOf<String, Node>()
        fun nodeFor(entry: ConfigEntry): Node = made.getOrPut(entry.alias) {
            Node(
                name = entry.alias,
                address = entry.hostName ?: entry.alias,
                port = entry.port ?: 22,
                user = entry.user ?: fallbackUser,
                group = group,
                color = (entry.alias.hashCode() and 0x7fffffff) % 8,
                keepAlive = entry.serverAliveInterval ?: 15,
            )
        }
        for (entry in selected) {
            var previous: Node? = null
            for (spec in entry.proxyJump) {
                val alias = all.firstOrNull { it.alias == spec }
                val known = existing.firstOrNull { it.name == spec }
                // An alias or a saved node keeps its own route; a bare
                // `user@host:port` hop continues the chain so far.
                previous = when {
                    alias != null -> nodeFor(alias)
                    known != null -> known
                    else -> made.getOrPut("jump:$spec") {
                        val (user, host, port) = splitSpec(spec)
                        Node(
                            name = host, address = host, port = port ?: 22, user = user ?: fallbackUser,
                            group = group, color = 0, jumpId = previous?.id,
                        )
                    }
                }
            }
            made[entry.alias] = nodeFor(entry).copy(jumpId = previous?.id)
        }
        return made.values.filter { new -> existing.none { it.id == new.id } }
    }

    /** `[user@]host[:port]`, with IPv6 in brackets. */
    fun splitSpec(spec: String): Triple<String?, String, Int?> {
        val at = spec.lastIndexOf('@')
        val user = if (at >= 0) spec.substring(0, at) else null
        val rest = spec.substring(at + 1)
        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end > 0) return Triple(user, rest.substring(1, end), rest.substring(end + 1).removePrefix(":").toIntOrNull())
        }
        val colon = rest.lastIndexOf(':')
        return if (colon > 0 && rest.indexOf(':') == colon) {
            Triple(user, rest.substring(0, colon), rest.substring(colon + 1).toIntOrNull())
        } else {
            Triple(user, rest, null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportConfigScreen(app: BelltermApp, onDone: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var fallbackUser by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = remember(text) {
        if (text.isBlank()) null else runCatching { ConfigImport.parse(Native.parseSshConfig(text)) }.getOrNull()
    }
    val existing by app.nodes.nodes.collectAsState()
    val unchecked = remember(parsed) { mutableStateListOf<String>().apply {
        parsed?.first?.filter { e -> existing.any { it.name == e.alias } }?.forEach { add(it.alias) }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.takeIf { it.size < 512 * 1024 }?.let { String(it) }
        }.getOrNull()?.let { text = it } ?: run { error = context.getString(R.string.import_unreadable) }
    }
    BackHandler(onBack = onDone)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_ssh_config)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.import_help), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        text, { text = it },
                        placeholder = { Text("Host devbox\n    HostName 10.0.0.5\n    User me\n    ProxyJump bastion") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.choose_file)) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val entries = parsed?.first.orEmpty()
                    if (entries.any { it.user == null }) {
                        OutlinedTextField(
                            fallbackUser, { fallbackUser = it.trim() },
                            label = { Text(stringResource(R.string.import_default_user)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            val entries = parsed?.first.orEmpty()
            if (entries.isNotEmpty()) {
                item { SectionHeader(pluralStringResource(R.plurals.import_found, entries.size, entries.size)) }
                item {
                    Group {
                        entries.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Checkbox(
                                    checked = entry.alias !in unchecked,
                                    onCheckedChange = { if (it) unchecked.remove(entry.alias) else unchecked.add(entry.alias) },
                                )
                                NodeAvatar(entry.alias, (entry.alias.hashCode() and 0x7fffffff) % 8, size = 36.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.alias, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${entry.user ?: fallbackUser.ifEmpty { "?" }}@${entry.hostName ?: entry.alias}" +
                                            (entry.port?.takeIf { it != 22 }?.let { ":$it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (entry.proxyJump.isNotEmpty()) {
                                        Pill(stringResource(R.string.via_jump, entry.proxyJump.joinToString(" → ")), MaterialTheme.colorScheme.tertiary)
                                    }
                                    entry.identityFile?.let {
                                        Text(
                                            stringResource(R.string.import_identity_file, it),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                parsed?.second?.takeIf { it.isNotEmpty() }?.let { skipped ->
                    item {
                        Text(
                            stringResource(R.string.import_skipped, skipped.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item {
                    val selected = entries.filter { it.alias !in unchecked }
                    val needsUser = selected.any { it.user == null } && fallbackUser.isEmpty()
                    Button(
                        enabled = selected.isNotEmpty() && !needsUser,
                        onClick = {
                            app.nodes.upsertAll(
                                ConfigImport.nodes(selected, entries, app.nodes.nodes.value, fallbackUser, context.getString(R.string.import_group)),
                            )
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(pluralStringResource(R.plurals.import_nodes, selected.size, selected.size)) }
                }
            } else if (text.isNotBlank()) {
                item {
                    Text(stringResource(R.string.import_none), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
