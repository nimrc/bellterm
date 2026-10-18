package app.bellterm.ui.nodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.data.AuthMethod
import app.bellterm.data.Keychain
import app.bellterm.data.Node
import app.bellterm.ui.components.ColorPicker
import app.bellterm.ui.components.Group
import app.bellterm.ui.components.NodeAvatar
import app.bellterm.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Adds or edits a node: where it is, how to sign in, how to reach it, how it looks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditor(app: BelltermApp, initial: Node?, onDone: () -> Unit) {
    val identities by app.keychain.identities.collectAsState()
    val nodes by app.nodes.nodes.collectAsState()
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var address by rememberSaveable { mutableStateOf(initial?.address ?: "") }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(initial?.user ?: "") }
    var session by rememberSaveable { mutableStateOf(initial?.session ?: "default") }
    var auth by rememberSaveable { mutableStateOf(initial?.auth ?: AuthMethod.Key) }
    var identityId by rememberSaveable { mutableStateOf(initial?.identityId) }
    var jumpId by rememberSaveable { mutableStateOf(initial?.jumpId) }
    var group by rememberSaveable { mutableStateOf(initial?.group ?: "") }
    var color by rememberSaveable { mutableStateOf(initial?.color ?: nodes.size % 8) }
    var keepAlive by rememberSaveable { mutableStateOf((initial?.keepAlive ?: 15).toString()) }
    // Blank keeps whatever is saved; the saved password itself is never shown.
    var password by rememberSaveable { mutableStateOf("") }
    var forgetPassword by rememberSaveable { mutableStateOf(false) }
    val hasSaved = initial?.let { app.keychain.hasPassword(it.id) } == true && !forgetPassword

    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val keepAliveValue = keepAlive.toIntOrNull()?.takeIf { it in 0..3600 }
    val valid = Node.validAddress(address) && Node.validUser(user) && portValue != null &&
        Node.validSession(session) && keepAliveValue != null
    val candidates = app.nodes.jumpCandidates(initial)

    BackHandler(onBack = onDone)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (initial == null) R.string.add_node else R.string.edit_node)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            val saved = (initial ?: Node(name = "", address = "", user = "")).copy(
                                name = name.trim(),
                                address = address.trim(),
                                port = portValue ?: 22,
                                user = user.trim(),
                                session = session.trim(),
                                auth = auth,
                                identityId = identityId.takeIf { auth == AuthMethod.Key },
                                jumpId = jumpId,
                                group = group.trim(),
                                color = color,
                                keepAlive = keepAliveValue ?: 15,
                            )
                            app.nodes.upsert(saved)
                            val secret = password.takeIf { auth == AuthMethod.Password && it.isNotEmpty() }
                            val forget = auth != AuthMethod.Password || forgetPassword
                            app.scope.launch(Dispatchers.IO) {
                                when {
                                    secret != null -> app.keychain.savePassword(saved.id, secret)
                                    forget -> app.keychain.forgetPassword(saved.id)
                                }
                            }
                            onDone()
                        },
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NodeAvatar(name.ifBlank { address.ifBlank { "?" } }, color, size = 56.dp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.color_label), style = MaterialTheme.typography.labelLarge)
                    ColorPicker(color) { color = it }
                }
            }

            SectionHeader(stringResource(R.string.section_connection))
            Fields {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.node_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    address, { address = it.trim() },
                    label = { Text(stringResource(R.string.host_address)) },
                    singleLine = true,
                    isError = address.isNotEmpty() && !Node.validAddress(address),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        user, { user = it.trim() },
                        label = { Text(stringResource(R.string.host_user)) },
                        singleLine = true,
                        isError = user.isNotEmpty() && !Node.validUser(user),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.host_port)) },
                        singleLine = true,
                        isError = portValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    session, { session = it.trim() },
                    label = { Text(stringResource(R.string.host_session)) },
                    supportingText = { Text(stringResource(R.string.host_session_help)) },
                    singleLine = true,
                    isError = !Node.validSession(session),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader(stringResource(R.string.section_sign_in))
            Fields {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AuthMethod.entries.forEachIndexed { index, method ->
                        SegmentedButton(
                            selected = auth == method,
                            onClick = { auth = method },
                            shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                        ) {
                            Text(stringResource(if (method == AuthMethod.Key) R.string.auth_key else R.string.auth_password))
                        }
                    }
                }
                if (auth == AuthMethod.Key) {
                    Picker(
                        label = stringResource(R.string.identity),
                        selected = identities.firstOrNull { it.id == (identityId ?: Keychain.DEVICE) }?.let { identity ->
                            identity.name + if (identity.certificate != null) " · " + stringResource(R.string.certificate) else ""
                        } ?: stringResource(R.string.device_key_name),
                        options = identities.map { identity ->
                            identity.id to identity.name + "  " + identity.algorithm.removePrefix("ssh-") +
                                if (identity.certificate != null) " · " + stringResource(R.string.certificate) else ""
                        },
                        onSelect = { identityId = it.takeUnless { id -> id == Keychain.DEVICE } },
                    )
                    Hint(stringResource(R.string.auth_key_help))
                } else {
                    OutlinedTextField(
                        password, { password = it },
                        label = { Text(stringResource(R.string.auth_password)) },
                        placeholder = { if (hasSaved) Text(stringResource(R.string.password_saved_placeholder)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(if (hasSaved) R.string.password_saved_help else R.string.password_unsaved_help))
                    if (hasSaved && password.isEmpty()) {
                        TextButton(onClick = { forgetPassword = true }) { Text(stringResource(R.string.forget_password)) }
                    }
                }
            }

            SectionHeader(stringResource(R.string.section_route))
            Fields {
                val none = stringResource(R.string.jump_none)
                Picker(
                    label = stringResource(R.string.jump_host),
                    selected = nodes.firstOrNull { it.id == jumpId }?.title ?: none,
                    options = listOf<Pair<String?, String>>(null to none) + candidates.map { it.id to "${it.title}  (${it.destination})" },
                    onSelect = { jumpId = it },
                )
                Hint(stringResource(R.string.jump_help))
            }

            SectionHeader(stringResource(R.string.section_more))
            Fields {
                GroupField(
                    group,
                    groups = nodes.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() },
                    onChange = { group = it },
                )
                OutlinedTextField(
                    keepAlive, { keepAlive = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.keepalive)) },
                    supportingText = { Text(stringResource(R.string.keepalive_help)) },
                    singleLine = true,
                    isError = keepAliveValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Fields(content: @Composable () -> Unit) {
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    // Indented like a text field's supporting text, so every hint lines up.
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** A group typed in, picked from the [groups] already in use, or left blank for none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(value: String, groups: List<String>, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && groups.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value, onChange,
            label = { Text(stringResource(R.string.group)) },
            supportingText = { Text(stringResource(R.string.group_help)) },
            trailingIcon = { if (groups.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && groups.isNotEmpty(), onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_group)) }, onClick = { onChange(""); expanded = false })
            groups.forEach { group ->
                DropdownMenuItem(text = { Text(group) }, onClick = { onChange(group); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, selected: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            selected, {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
