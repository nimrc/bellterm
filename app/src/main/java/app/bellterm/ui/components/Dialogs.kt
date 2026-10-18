package app.bellterm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bellterm.R
import app.bellterm.data.Node

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    confirm: String,
    onDone: () -> Unit,
    label: String? = null,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it },
                label = label?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()); onDone() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val WEBSITE = "https://bellterm.app/"
private const val DISCORD = "https://discord.gg/4ZPV5667Sq"

/** Links to the website and the Discord community, with the installed version underneath. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    // No browser installed is the only way this fails; there is nothing better to offer then.
    fun open(link: String) {
        runCatching { uri.openUri(link) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.help)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { open(WEBSITE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.visit_website))
                }
                Button(onClick = { open(DISCORD) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.join_discord))
                }
                Text(
                    stringResource(R.string.about_text, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * Asks for the password of every node on a route that signs in with one and
 * has none saved, jump hosts included. [onConnect] receives the passwords and
 * whether to save them.
 */
@Composable
fun PasswordsDialog(hops: List<Node>, onConnect: (Map<String, String>, Boolean) -> Unit, onDismiss: () -> Unit) {
    val secrets = remember { mutableStateMapOf<String, String>() }
    var remember by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passwords_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hops.forEachIndexed { index, node ->
                    OutlinedTextField(
                        secrets[node.id].orEmpty(), { secrets[node.id] = it },
                        label = { Text(node.destination) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(focus) else Modifier),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it })
                    Text(stringResource(R.string.remember_password))
                }
                Text(
                    stringResource(if (remember) R.string.password_saved_encrypted else R.string.password_not_stored),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = hops.all { !secrets[it.id].isNullOrEmpty() },
                onClick = { onConnect(secrets.toMap(), remember) },
            ) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
