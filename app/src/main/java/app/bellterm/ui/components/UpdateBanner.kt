package app.bellterm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bellterm.R
import app.bellterm.data.UpdateChecker
import app.bellterm.data.UpdateInfo
import app.bellterm.data.UpdateState

/** Opens the release's APK in the browser; no browser installed is the only failure, with nothing better to offer. */
@Composable
fun rememberOpenDownload(): (UpdateInfo) -> Unit {
    val uri = LocalUriHandler.current
    return { info -> runCatching { uri.openUri(info.url) } }
}

/** A newer release, unless the user skipped it: its notes, a download link, and a way to skip it. */
@Composable
fun UpdateBanner(updates: UpdateChecker, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsState()
    val skipped by updates.skipped.collectAsState()
    val info = (state as? UpdateState.Available)?.info?.takeIf { it.versionCode != skipped } ?: return
    val language = LocalContext.current.resources.configuration.locales[0].language
    val download = rememberOpenDownload()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.update_available_title, info.versionName), style = MaterialTheme.typography.titleMedium)
            info.notesFor(language).takeIf { it.isNotEmpty() }?.let { notes ->
                Text(
                    notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { updates.skip(info) }) { Text(stringResource(R.string.update_skip)) }
                Button(onClick = { download(info) }) { Text(stringResource(R.string.download)) }
            }
        }
    }
}
