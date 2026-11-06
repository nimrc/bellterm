package app.bellterm.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.data.AuthMethod
import app.bellterm.data.CertificateInfo
import app.bellterm.data.Identity
import app.bellterm.data.Keychain
import app.bellterm.ui.components.ConfirmDialog
import app.bellterm.ui.components.Group
import app.bellterm.ui.components.Pill
import app.bellterm.ui.components.SectionHeader
import app.bellterm.ui.components.TextDialog
import app.bellterm.ui.glass.GlassAlertDialog
import app.bellterm.ui.glass.GlassButton
import app.bellterm.ui.glass.GlassField
import app.bellterm.ui.glass.GlassMenu
import app.bellterm.ui.glass.GlassScreen
import app.bellterm.ui.glass.IconBadge
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface KeyDialog {
    data object Generate : KeyDialog
    data class Import(val text: String = "") : KeyDialog
    data class Rename(val identity: Identity) : KeyDialog
    data class Delete(val identity: Identity) : KeyDialog
    data class Certificate(val identity: Identity) : KeyDialog
}

/**
 * SSH keys and certificates. The device key is made on the phone; others are
 * imported (decrypted once with their passphrase) or generated. A certificate
 * attached to a key is presented whenever that key signs in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(app: BelltermApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val identities by app.keychain.identities.collectAsState()
    val nodes by app.nodes.nodes.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }
    var add by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size < 64 * 1024 }?.let { String(it) }
        }.getOrNull()
        dialog = KeyDialog.Import(text.orEmpty())
    }
    BackHandler(onBack = onBack)

    GlassScreen(
        title = stringResource(R.string.keys),
        onBack = onBack,
        snackbar = snackbar,
        bottomBar = {
            Box {
                GlassButton(
                    stringResource(R.string.add_key),
                    onClick = { add = true },
                    primary = true,
                    icon = Icons.Default.Add,
                    modifier = Modifier.testTag("add_key"),
                )
                GlassMenu(expanded = add, onDismissRequest = { add = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.generate_key)) }, onClick = { add = false; dialog = KeyDialog.Generate })
                    DropdownMenuItem(text = { Text(stringResource(R.string.paste_key)) }, onClick = { add = false; dialog = KeyDialog.Import() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.key_from_file)) }, onClick = { add = false; picker.launch(arrayOf("*/*")) })
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("keys")) {
            item {
                Text(
                    stringResource(R.string.keys_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
            }
            item { SectionHeader(stringResource(R.string.identities)) }
            items(identities.sortedWith(compareByDescending<Identity> { it.isDevice }.thenBy { it.created }), key = { it.id }) { identity ->
                KeyCard(
                    app = app,
                    identity = identity,
                    users = nodes.count { (it.identityId ?: Keychain.DEVICE) == identity.id && it.auth == AuthMethod.Key },
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ssh public key", identity.publicKey))
                        // Android 13 and later confirm copies themselves; a second notice would cover theirs.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.public_key_copied)) }
                        }
                    },
                    onShare = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, identity.publicKey), null))
                    },
                    onCertificate = { dialog = KeyDialog.Certificate(identity) },
                    onRename = { dialog = KeyDialog.Rename(identity) },
                    onDelete = { dialog = KeyDialog.Delete(identity) },
                )
            }
        }
    }

    when (val current = dialog) {
        null -> {}
        KeyDialog.Generate -> TextDialog(
            title = stringResource(R.string.generate_key),
            initial = stringResource(R.string.new_key_name),
            confirm = stringResource(R.string.generate),
            label = stringResource(R.string.key_name),
            onDone = { dialog = null },
        ) { name -> scope.launch { withContext(Dispatchers.IO) { app.keychain.generate(name) } } }
        is KeyDialog.Import -> ImportKeyDialog(current.text, onDismiss = { dialog = null }) { name, text, passphrase ->
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { app.keychain.import(name, text, passphrase) } }
                result.onSuccess { dialog = null }.onFailure {
                    snackbar.showSnackbar(context.getString(R.string.key_import_failed, it.message.orEmpty()))
                }
            }
        }
        is KeyDialog.Rename -> TextDialog(
            title = stringResource(R.string.rename),
            initial = current.identity.name,
            confirm = stringResource(R.string.rename),
            onDone = { dialog = null },
        ) { app.keychain.rename(current.identity.id, it) }
        is KeyDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.delete_key_title, current.identity.name),
            text = stringResource(R.string.delete_key_text),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                app.keychain.delete(current.identity.id)
                if (current.identity.isDevice) scope.launch(Dispatchers.IO) { app.keychain.device(context.getString(R.string.device_key_name)) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is KeyDialog.Certificate -> CertificateDialog(app, current.identity, onDismiss = { dialog = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCard(
    app: BelltermApp,
    identity: Identity,
    users: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCertificate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val certificate = remember(identity.certificate) {
        identity.certificate?.let { runCatching { app.keychain.certificateInfo(identity.id, it) }.getOrNull() }
    }
    Group(Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Lock, if (identity.isDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, Modifier.size(42.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(identity.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        identity.algorithm,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    GlassMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; onShare() })
                        DropdownMenuItem(
                            text = { Text(stringResource(if (identity.certificate == null) R.string.attach_certificate else R.string.manage_certificate)) },
                            onClick = { menu = false; onCertificate() },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(if (identity.isDevice) R.string.regenerate_key else R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Only a renamed device key needs the badge; otherwise its name already says it.
                val deviceName = stringResource(R.string.device_key_name)
                if (identity.isDevice && identity.name != deviceName) Pill(deviceName, MaterialTheme.colorScheme.primary)
                if (users > 0) Pill(pluralStringResource(R.plurals.used_by_nodes, users, users), MaterialTheme.colorScheme.tertiary)
                certificate?.let {
                    Pill(
                        if (it.current && it.matches) stringResource(R.string.certificate_valid) else stringResource(R.string.certificate_invalid),
                        if (it.current && it.matches) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        dot = true,
                    )
                }
            }
            Text(identity.fingerprint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                identity.publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(12.dp)
                    .testTag("public_key"),
            )
            GlassButton(stringResource(R.string.copy_public_key), onClick = onCopy, modifier = Modifier.testTag("copy_public_key"))
        }
    }
}

@Composable
private fun ImportKeyDialog(initial: String, onDismiss: () -> Unit, onImport: (String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(initial) }
    var passphrase by remember { mutableStateOf("") }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(name, { name = it }, label = { Text(stringResource(R.string.key_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                GlassField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.private_key)) },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
                )
                GlassField(
                    passphrase, { passphrase = it },
                    label = { Text(stringResource(R.string.passphrase_optional)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.import_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && name.isNotBlank(), onClick = { onImport(name.trim(), text, passphrase.ifEmpty { null }) }) {
                Text(stringResource(R.string.import_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Attach, inspect, or remove the OpenSSH certificate presented with a key. */
@Composable
private fun CertificateDialog(app: BelltermApp, identity: Identity, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(identity.certificate.orEmpty()) }
    val info: Result<CertificateInfo>? = remember(text) {
        text.takeIf { it.isNotBlank() }?.let { runCatching { app.keychain.certificateInfo(identity.id, it) } }
    }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    fun time(seconds: Long) = when (seconds) {
        0L -> "—"
        -1L, Long.MAX_VALUE -> "∞"
        else -> if (seconds < 0) "∞" else format.format(Date(seconds * 1000))
    }
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.certificate_for, identity.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(
                    text, { text = it },
                    placeholder = { Text("ssh-ed25519-cert-v01@openssh.com AAAA…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                )
                when {
                    info == null -> Text(stringResource(R.string.certificate_help), style = MaterialTheme.typography.bodySmall)
                    info.isFailure -> Text(stringResource(R.string.certificate_unreadable), color = MaterialTheme.colorScheme.error)
                    else -> info.getOrNull()?.let { cert ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (!cert.matches) Text(stringResource(R.string.certificate_other_key), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.certificate_id, cert.keyId), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.certificate_principals, cert.principals.joinToString(", ").ifEmpty { "*" }), style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.certificate_validity, time(cert.validAfter), time(cert.validBefore)),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cert.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                            Text(stringResource(R.string.certificate_ca, cert.caFingerprint), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = info?.getOrNull()?.matches == true,
                onClick = { app.keychain.setCertificate(identity.id, text); onDismiss() },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            if (identity.certificate != null) {
                TextButton(onClick = { app.keychain.setCertificate(identity.id, null); onDismiss() }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}
