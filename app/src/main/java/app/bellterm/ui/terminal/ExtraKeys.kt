package app.bellterm.ui.terminal

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one extra key does. */
sealed interface ExtraKey {
    val label: String

    data class Key(override val label: String, val keyCode: Int, val repeats: Boolean = false) : ExtraKey
    data class Text(override val label: String, val text: String) : ExtraKey
    data class Modifier(override val label: String, val meta: Int) : ExtraKey
    data class Action(override val label: String, val run: () -> Unit) : ExtraKey
}

val DefaultExtraKeys: List<ExtraKey> = listOf(
    ExtraKey.Key("Esc", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Key("Tab", KeyEvent.KEYCODE_TAB),
    ExtraKey.Modifier("Ctrl", KeyEvent.META_CTRL_ON),
    ExtraKey.Modifier("Alt", KeyEvent.META_ALT_ON),
    ExtraKey.Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeats = true),
    ExtraKey.Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeats = true),
    ExtraKey.Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeats = true),
    ExtraKey.Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeats = true),
    ExtraKey.Text("|", "|"),
    ExtraKey.Text("-", "-"),
    ExtraKey.Text("/", "/"),
    ExtraKey.Text("~", "~"),
    ExtraKey.Key("Home", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Key("End", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Key("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeats = true),
    ExtraKey.Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeats = true),
)

/**
 * The row of keys a phone keyboard lacks. Ctrl and Alt latch for the next key
 * or character, as on Termux; arrows and paging keys repeat while held.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysRow(
    keys: List<ExtraKey>,
    latched: Int,
    onLatch: (Int) -> Unit,
    onKey: (keyCode: Int, repeat: Boolean) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        // Centred when the keys fit; a narrower screen scrolls them instead.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (key in keys) {
                    val active = key is ExtraKey.Modifier && latched and key.meta != 0
                    val shape = RoundedCornerShape(8.dp)
                    val base = Modifier
                        .padding(horizontal = 2.dp)
                        .height(40.dp)
                        .widthIn(min = 44.dp)
                        .clip(shape)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    val gesture = when (key) {
                        is ExtraKey.Key -> if (key.repeats) {
                            base.pointerInput(key) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onKey(key.keyCode, false)
                                    val repeat = scope.launch {
                                        delay(400)
                                        while (true) {
                                            onKey(key.keyCode, true)
                                            delay(60)
                                        }
                                    }
                                    waitForUpOrCancellation()
                                    repeat.cancel()
                                }
                            }
                        } else {
                            base.combinedClickable { onKey(key.keyCode, false) }
                        }
                        is ExtraKey.Text -> base.combinedClickable { onText(key.text) }
                        is ExtraKey.Modifier -> base.combinedClickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLatch(latched xor key.meta)
                        }
                        is ExtraKey.Action -> base.combinedClickable { key.run() }
                    }
                    Box(gesture.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            key.label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
