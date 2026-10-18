package app.bellterm.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.bellterm.core.AgentStatus

// Bellterm's palette: teal for the bell, deep slate around it, and a brass
// counterpoint. Every screen draws from these through the colour scheme.
private val Brass = Color(0xFFC98A1B)
private val BrassLight = Color(0xFFF2B84B)
private val Slate = Color(0xFF1B2230)
private val Teal = Color(0xFF2F9E9A)
private val TealLight = Color(0xFF7FD6D2)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00696A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF3D5A80),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E3FF),
    onSecondaryContainer = Color(0xFF0E1D33),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDA8),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFFBF8F3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F2EB),
    surfaceContainer = Color(0xFFF0ECE4),
    surfaceContainerHigh = Color(0xFFEAE6DE),
    surfaceContainerHighest = Color(0xFFE4E0D8),
    outlineVariant = Color(0xFFD6CFC2),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFA9C7F2),
    onSecondary = Color(0xFF0F2F52),
    secondaryContainer = Color(0xFF29466A),
    onSecondaryContainer = Color(0xFFD5E3FF),
    tertiary = BrassLight,
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFDDA8),
    background = Color(0xFF12161F),
    surface = Color(0xFF12161F),
    surfaceContainerLowest = Color(0xFF0D1118),
    surfaceContainerLow = Color(0xFF181D27),
    surfaceContainer = Slate,
    surfaceContainerHigh = Color(0xFF232A38),
    surfaceContainerHighest = Color(0xFF2C3443),
    outlineVariant = Color(0xFF3A4252),
)

val BelltermShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun BelltermTheme(dark: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, shapes = BelltermShapes, content = content)
}

/** The daemon's agent status as a colour, the same on every client. */
fun AgentStatus.color(): Color = when (this) {
    AgentStatus.Working -> Color(0xFF4C8DF6)
    AgentStatus.Blocked -> Color(0xFFF2A93B)
    AgentStatus.Done -> Color(0xFF3FB868)
    AgentStatus.Idle -> Color(0xFF8A8F98)
    AgentStatus.Unknown -> Color.Transparent
}

/** Colour labels a node can wear, shown on its avatar and session bar. */
object NodeColors {
    val all = listOf(
        Teal, Color(0xFFE5674F), Color(0xFFD9467A), Color(0xFF8E5BD9),
        Color(0xFF4A6CF0), Color(0xFF2C98D6), Brass, Color(0xFF5DA03A),
    )

    fun of(index: Int): Color = all[index.mod(all.size)]
}
