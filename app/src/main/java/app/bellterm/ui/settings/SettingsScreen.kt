package app.bellterm.ui.settings

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bellterm.BelltermApp
import app.bellterm.data.AppLanguage
import app.bellterm.data.AppLanguages
import app.bellterm.R
import app.bellterm.data.ThemeMode
import app.bellterm.data.UpdateState
import app.bellterm.ui.components.Group
import app.bellterm.ui.components.ItemRow
import app.bellterm.ui.components.Pill
import app.bellterm.ui.components.SectionHeader
import app.bellterm.ui.components.rememberOpenDownload
import app.bellterm.ui.terminal.FontChoice
import app.bellterm.ui.theme.TerminalScheme
import app.bellterm.ui.theme.TerminalSchemes
import kotlinx.coroutines.launch

/** Shown in every font preview: letters that differ between fonts, then Nerd Font icons. */
private const val PREVIEW = "0O1lI {}[] =>     "

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: BelltermApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsState()
    val fontChanges by app.fonts.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val progress = remember { mutableStateMapOf<String, Float>() }
    var licenses by remember { mutableStateOf(false) }
    val update by app.updates.state.collectAsState()
    val download = rememberOpenDownload()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "font.ttf"
        scope.launch {
            runCatching { app.fonts.importCustom(uri, name) }
                .onSuccess { id -> app.settings.update { s -> s.copy(font = id) } }
                .onFailure { snackbar.showSnackbar(context.getString(R.string.font_import_failed)) }
        }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp)) {
            item { SectionHeader(stringResource(R.string.section_appearance)) }
            item {
                Group {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.bodyLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.themeMode == mode,
                                    onClick = { app.settings.update { it.copy(themeMode = mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                ) {
                                    Text(stringResource(when (mode) {
                                        ThemeMode.System -> R.string.theme_system
                                        ThemeMode.Light -> R.string.theme_light
                                        ThemeMode.Dark -> R.string.theme_dark
                                    }))
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    LanguagePicker()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        HorizontalDivider()
                        ItemRow(
                            title = stringResource(R.string.dynamic_color),
                            detail = stringResource(R.string.dynamic_color_help),
                            onClick = { app.settings.update { it.copy(dynamicColor = !it.dynamicColor) } },
                            trailing = { Switch(settings.dynamicColor, { on -> app.settings.update { it.copy(dynamicColor = on) } }) },
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.dark_scheme)) }
            item {
                SchemeRow(TerminalSchemes.all.filter { it.dark }, settings.darkScheme) { id -> app.settings.update { it.copy(darkScheme = id) } }
            }
            item { SectionHeader(stringResource(R.string.light_scheme)) }
            item {
                SchemeRow(TerminalSchemes.all.filter { !it.dark }, settings.lightScheme) { id -> app.settings.update { it.copy(lightScheme = id) } }
            }

            item { SectionHeader(stringResource(R.string.section_font)) }
            item {
                val choices = remember(fontChanges) { app.fonts.choices() }
                Group {
                    choices.forEachIndexed { index, choice ->
                        if (index > 0) HorizontalDivider()
                        FontRow(
                            app = app,
                            choice = choice,
                            selected = settings.font == choice.id,
                            progress = progress[choice.id],
                            changes = fontChanges,
                            onSelect = { app.settings.update { it.copy(font = choice.id) } },
                            onDownload = {
                                val font = choice.catalog ?: return@FontRow
                                progress[choice.id] = 0f
                                scope.launch {
                                    runCatching { app.fonts.download(font) { p -> progress[choice.id] = p } }
                                        .onSuccess { app.settings.update { it.copy(font = choice.id) } }
                                        .onFailure { snackbar.showSnackbar(context.getString(R.string.font_download_failed, it.message.orEmpty())) }
                                    progress.remove(choice.id)
                                }
                            },
                            onDelete = {
                                if (choice.catalog != null) app.fonts.deleteDownloads(choice.catalog) else app.fonts.deleteCustom(choice.id)
                                if (settings.font == choice.id && choice.catalog?.bundled != true) {
                                    app.settings.update { it.copy(font = "jetbrains-mono") }
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                    Box(Modifier.padding(12.dp)) {
                        OutlinedButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*")) }) {
                            Text(stringResource(R.string.import_font))
                        }
                    }
                }
            }
            item {
                Group(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.font_size, settings.fontSize.toInt()), style = MaterialTheme.typography.bodyLarge)
                        Slider(
                            value = settings.fontSize,
                            onValueChange = { size -> app.settings.update { it.copy(fontSize = size.toInt().toFloat()) } },
                            valueRange = 8f..24f,
                            steps = 15,
                        )
                        Text(stringResource(R.string.font_size_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_terminal)) }
            item {
                Group {
                    ItemRow(
                        title = stringResource(R.string.vibrate_on_bell),
                        onClick = { app.settings.update { it.copy(vibrateOnBell = !it.vibrateOnBell) } },
                        trailing = { Switch(settings.vibrateOnBell, { on -> app.settings.update { it.copy(vibrateOnBell = on) } }) },
                    )
                    HorizontalDivider()
                    ItemRow(
                        title = stringResource(R.string.keep_screen_on),
                        detail = stringResource(R.string.keep_screen_on_help),
                        onClick = { app.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } },
                        trailing = { Switch(settings.keepScreenOn, { on -> app.settings.update { it.copy(keepScreenOn = on) } }) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.section_about)) }
            item {
                Group {
                    ItemRow(
                        title = stringResource(R.string.app_name),
                        detail = stringResource(R.string.about_text, context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""),
                    )
                    HorizontalDivider()
                    ItemRow(
                        title = stringResource(R.string.check_updates),
                        detail = when (val state = update) {
                            UpdateState.Idle -> null
                            UpdateState.Checking -> stringResource(R.string.update_checking)
                            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                            UpdateState.Failed -> stringResource(R.string.update_failed)
                            is UpdateState.Available -> stringResource(R.string.update_available_detail, state.info.versionName)
                        },
                        onClick = {
                            when (val state = update) {
                                is UpdateState.Available -> download(state.info)
                                UpdateState.Checking -> Unit
                                // The app's scope, so leaving the screen does not cancel the check midway.
                                else -> app.scope.launch { app.updates.check(force = true) }
                            }
                        },
                        trailing = if (update == UpdateState.Checking) {
                            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider()
                    ItemRow(title = stringResource(R.string.licenses), onClick = { licenses = true })
                }
            }
        }
    }

    if (licenses) {
        val text = remember {
            listOf(
                "licenses/NOTICE.txt",
                "licenses/LICENSE.txt",
                "licenses/herdr-protocol-NOTICE.md",
                "fonts/JetBrainsMono-OFL.txt",
                "fonts/NerdFonts-LICENSE.txt",
                "fonts/SymbolsNerdFont-LICENSE.txt",
            ).joinToString("\n\n———\n\n") { path ->
                context.assets.open(path).bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = {
                LazyColumn { item { Text(text, style = MaterialTheme.typography.bodySmall) } }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun SchemeRow(schemes: List<TerminalScheme>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        schemes.forEach { scheme -> SchemeCard(scheme, scheme.id == selected) { onSelect(scheme.id) } }
    }
}

/** A tiny terminal in the scheme's colours. */
@Composable
private fun SchemeCard(scheme: TerminalScheme, selected: Boolean, onClick: () -> Unit) {
    fun c(v: Long) = Color(0xff000000 or v)
    Column(
        Modifier
            .width(148.dp)
            .clip(MaterialTheme.shapes.medium)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .background(c(scheme.background))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("~ ", color = c(scheme.ansi[4]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("git ", color = c(scheme.ansi[2]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("main", color = c(scheme.ansi[5]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Box(Modifier.padding(start = 2.dp).size(7.dp, 13.dp).background(c(scheme.cursor)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..6).forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(c(scheme.ansi[it]))) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(scheme.name, color = c(scheme.foreground), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = c(scheme.foreground), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FontRow(
    app: BelltermApp,
    choice: FontChoice,
    selected: Boolean,
    progress: Float?,
    changes: Int,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    // Spelled out rather than produceState, whose lint check misses this assignment.
    var family by remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(choice.id, choice.available, changes) {
        family = if (choice.available) FontFamily(app.fonts.typefaces(choice.id).regular) else null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = choice.available) { onSelect() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = choice.available)
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                if (choice.nerd) Pill("Nerd Font", MaterialTheme.colorScheme.tertiary)
            }
            if (family != null) {
                Text(PREVIEW, fontFamily = family, fontSize = 14.sp, maxLines = 1)
            } else {
                val size = (choice.catalog?.downloadSize ?: 0) / 1_000_000.0
                Text(
                    stringResource(R.string.font_download_size, "%.1f".format(size)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp).padding(2.dp))
            choice.catalog != null && !app.fonts.fullyDownloaded(choice.catalog) ->
                TextButton(onClick = onDownload) {
                    Text(stringResource(if (choice.available) R.string.font_get_styles else R.string.download))
                }
            choice.catalog?.bundled == false || choice.id.startsWith("custom:") ->
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

/**
 * The display language. Language names are written in their own language so
 * a reader who cannot read the current one still finds theirs.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    val current = remember(context) { AppLanguages.current(context) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AppLanguage.entries.forEachIndexed { index, language ->
                SegmentedButton(
                    selected = current == language,
                    onClick = { if (language != current) activity?.let { AppLanguages.set(it, language) } },
                    shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                ) {
                    Text(stringResource(when (language) {
                        AppLanguage.System -> R.string.language_system
                        AppLanguage.English -> R.string.language_english
                        AppLanguage.Chinese -> R.string.language_chinese
                    }))
                }
            }
        }
    }
}
