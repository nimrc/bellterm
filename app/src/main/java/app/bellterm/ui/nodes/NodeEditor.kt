package app.bellterm.ui.nodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import app.bellterm.ui.glass.GlassButton
import app.bellterm.ui.glass.GlassField
import app.bellterm.ui.glass.GlassScreen
import app.bellterm.ui.glass.GlassSegmented
import app.bellterm.ui.glass.MenuShape
import app.bellterm.ui.glass.menuColor
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

    fun save() {
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
    }

    BackHandler(onBack = onDone)
    GlassScreen(
        title = stringResource(if (initial == null) R.string.add_node else R.string.edit_node),
        onBack = onDone,
        actions = {
            GlassButton(
                stringResource(R.string.save),
                onClick = ::save,
                primary = true,
                enabled = valid,
                icon = Icons.Default.Check,
                modifier = Modifier.testTag("save"),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
        ) {
            Group {
                Row(
                    Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NodeAvatar(name.ifBlank { address.ifBlank { "?" } }, color, size = 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.color_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ColorPicker(color) { color = it }
                    }
                }
            }

            SectionHeader(stringResource(R.string.section_connection))
            Fields {
                GlassField(name, { name = it }, label = { Text(stringResource(R.string.node_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field:name"))
                GlassField(
                    address, { address = it.trim() },
                    label = { Text(stringResource(R.string.host_address)) },
                    singleLine = true,
                    isError = address.isNotEmpty() && !Node.validAddress(address),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("field:address"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassField(
                        user, { user = it.trim() },
                        label = { Text(stringResource(R.string.host_user)) },
                        singleLine = true,
                        isError = user.isNotEmpty() && !Node.validUser(user),
                        modifier = Modifier.weight(2f).testTag("field:user"),
                    )
                    GlassField(
                        port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.host_port)) },
                        singleLine = true,
                        isError = portValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("field:port"),
                    )
                }
                GlassField(
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
                GlassSegmented(
                    options = AuthMethod.entries.map { stringResource(if (it == AuthMethod.Key) R.string.auth_key else R.string.auth_password) },
                    selected = AuthMethod.entries.indexOf(auth),
                    onSelect = { auth = AuthMethod.entries[it] },
                )
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
                    GlassField(
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
                        GlassButton(stringResource(R.string.forget_password), onClick = { forgetPassword = true }, destructive = true)
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
                GlassField(
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
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
        GlassField(
            value, onChange,
            label = { Text(stringResource(R.string.group)) },
            supportingText = { Text(stringResource(R.string.group_help)) },
            trailingIcon = { if (groups.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && groups.isNotEmpty(), onDismissRequest = { expanded = false }, shape = MenuShape, containerColor = menuColor()) {
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
        GlassField(
            selected, {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MenuShape, containerColor = menuColor()) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
