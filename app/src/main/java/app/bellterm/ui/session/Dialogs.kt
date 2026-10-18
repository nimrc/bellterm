package app.bellterm.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.bellterm.R
import app.bellterm.core.Connection
import app.bellterm.ui.components.ConfirmDialog

/** Renames ask for a label; closing anything Herdr runs asks first. */
@Composable
internal fun PromptDialog(prompt: Prompt, connection: Connection, onDone: () -> Unit) {
    when (prompt) {
        is Prompt.NewTab -> NewTabDialog(onDone) { connection.newTab(prompt.workspaceId, it) }
        is Prompt.RenameTab -> RenameDialog(stringResource(R.string.rename_tab), prompt.label, onDone) {
            connection.renameTab(prompt.id, it)
        }
        is Prompt.RenameWorkspace -> RenameDialog(stringResource(R.string.rename_workspace), prompt.label, onDone) {
            connection.renameWorkspace(prompt.id, it)
        }
        is Prompt.CloseTab -> ConfirmDialog(
            title = stringResource(R.string.close_tab_title, prompt.label),
            text = stringResource(R.string.close_tab_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closeTab(prompt.id); onDone() },
            onDismiss = onDone,
        )
        is Prompt.ClosePane -> ConfirmDialog(
            title = stringResource(R.string.close_pane_title),
            text = stringResource(R.string.close_pane_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closePane(prompt.id); onDone() },
            onDismiss = onDone,
        )
        is Prompt.CloseWorkspace -> ConfirmDialog(
            title = stringResource(R.string.close_workspace_title, prompt.label),
            text = stringResource(R.string.close_workspace_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closeWorkspace(prompt.id); onDone() },
            onDismiss = onDone,
        )
    }
}

/** Names the new tab; left blank, Herdr numbers it as it would anyway. */
@Composable
private fun NewTabDialog(onDone: () -> Unit, onCreate: (String) -> Unit) {
    var label by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.new_tab)) },
        text = {
            OutlinedTextField(
                label, { label = it },
                label = { Text(stringResource(R.string.tab_name)) },
                placeholder = { Text(stringResource(R.string.tab_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCreate(label); onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(label); onDone() }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(title: String, initial: String, onDone: () -> Unit, onRename: (String) -> Unit) {
    var label by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            OutlinedTextField(label, { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onRename(label.trim()); onDone() }) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}
