package app.bellterm.ui.terminal

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
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
 * or character, as on Termux; arrows and paging keys repeat while held. Keys
 * are small glass caps; a latched modifier fills with the session's [accent].
 */
@Composable
fun ExtraKeysRow(
    keys: List<ExtraKey>,
    latched: Int,
    accent: Color,
    background: Color,
    onLatch: (Int) -> Unit,
    onKey: (keyCode: Int, repeat: Boolean) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { drawRect(onSurface.copy(alpha = 0.08f), size = size.copy(height = 1.dp.toPx())) }
            .then(modifier)
            .testTag("extra_keys"),
        // Centred when the keys fit; a narrower screen scrolls them instead.
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            for (key in keys) {
                val active = key is ExtraKey.Modifier && latched and key.meta != 0
                var down by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (down) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "key")
                val fill by animateColorAsState(
                    when {
                        active -> accent
                        down -> onSurface.copy(alpha = 0.16f)
                        else -> onSurface.copy(alpha = 0.07f)
                    },
                    label = "keyFill",
                )
                val base = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .height(40.dp)
                    .widthIn(min = 46.dp)
                    .clip(KeyShape)
                    .background(fill)
                    .border(0.5.dp, onSurface.copy(alpha = if (active) 0f else 0.08f), KeyShape)
                    .testTag("key:${key.label}")
                val gesture = when (key) {
                    is ExtraKey.Key -> if (key.repeats) {
                        base.pointerInput(key) {
                            awaitEachGesture {
                                awaitFirstDown()
                                down = true
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
                                down = false
                            }
                        }
                    } else {
                        base.press({ down = it }) { onKey(key.keyCode, false) }
                    }
                    is ExtraKey.Text -> base.press({ down = it }) { onText(key.text) }
                    is ExtraKey.Modifier -> base.press({ down = it }) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLatch(latched xor key.meta)
                    }
                    is ExtraKey.Action -> base.press({ down = it }) { key.run() }
                }
                Box(gesture.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        key.label,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        color = if (active) (if (accent.luminance() > 0.5f) Color(0xFF10151C) else Color.White) else onSurface,
                    )
                }
            }
        }
    }
}

private val KeyShape = RoundedCornerShape(12.dp)

/** A tap that reports press state, for the cap's shrink, without a ripple. */
private fun Modifier.press(onPressed: (Boolean) -> Unit, onTap: () -> Unit): Modifier = pointerInput(onTap) {
    detectTapGestures(
        onPress = {
            onPressed(true)
            tryAwaitRelease()
            onPressed(false)
        },
        onTap = { onTap() },
    )
}
