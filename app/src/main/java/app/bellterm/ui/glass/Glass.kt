package app.bellterm.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.blur.Backdrop
import io.github.suqi8.coui.kmp.blur.LayerBackdrop
import io.github.suqi8.coui.kmp.blur.blur
import io.github.suqi8.coui.kmp.blur.colorControls
import io.github.suqi8.coui.kmp.blur.drawBackdrop
import io.github.suqi8.coui.kmp.blur.highlight.Highlight
import io.github.suqi8.coui.kmp.blur.isRuntimeShaderSupported
import io.github.suqi8.coui.kmp.blur.layerBackdrop

/**
 * How glass looks in the current theme. [blurred] is false below API 33, where
 * coui-blur has no RuntimeShader: surfaces then fall back to a denser tint.
 */
@Immutable
data class GlassStyle(
    val dark: Boolean,
    val base: Color,
    val tint: Color,
    val strongTint: Color,
    val rim: Color,
    val shadow: Color,
    val highlight: Highlight,
    val smallHighlight: Highlight,
    val blurred: Boolean,
)

val LocalGlassStyle = staticCompositionLocalOf<GlassStyle> { error("GlassTheme is missing") }

/** What glass surfaces refract: the ambient background of the current screen. */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun GlassTheme(dark: Boolean, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme.background
    val style = remember(dark, base) {
        GlassStyle(
            dark = dark,
            base = base,
            tint = if (dark) Color(0x1FFFFFFF) else Color(0x8CFFFFFF),
            strongTint = if (dark) Color(0x52161B26) else Color(0xB3FFFFFF),
            rim = if (dark) Color(0x24FFFFFF) else Color(0x14000000),
            shadow = if (dark) Color(0x66000000) else Color(0x1F2B3A55),
            highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
            smallHighlight = if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight,
            blurred = isRuntimeShaderSupported(),
        )
    }
    // Glass screens have no Surface to set it, so text would default to black.
    CompositionLocalProvider(
        LocalGlassStyle provides style,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        content = content,
    )
}

/**
 * A liquid-glass surface: the backdrop behind it blurred and saturated, a tint
 * on top, and coui's bloom highlight along the rim. [lens] adds the rounded
 * refraction band at the edge; it needs a corner-based [shape].
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = LocalGlassStyle.current.tint,
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    blur: Dp = 24.dp,
    lens: Boolean = false,
    highlight: Highlight? = LocalGlassStyle.current.highlight,
    shadow: Boolean = false,
): Modifier {
    val style = LocalGlassStyle.current
    val shadowed = if (shadow) dropShadow(shape, Shadow(radius = 24.dp, color = style.shadow, offset = DpOffset(0.dp, 8.dp))) else this
    if (backdrop == null || !style.blurred) {
        return shadowed
            .clip(shape)
            .background(tint.compositeOver(style.base.copy(alpha = if (style.dark) 0.72f else 0.6f)), shape)
            .border(1.dp, style.rim, shape)
    }
    return shadowed.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blur.toPx())
            colorControls(saturation = 1.6f)
            if (lens) lens(refractionHeight = 14.dp.toPx(), refractionAmount = 20.dp.toPx(), chromaticAberration = 0.08f)
        },
        highlight = highlight?.let { h -> { h } },
        onDrawSurface = { drawOutline(shape.createOutline(size, layoutDirection, this), tint) },
    )
}

/** Records this content as [backdrop] for glass to sample; a no-op where glass cannot blur. */
@Composable
fun Modifier.captureBackdrop(backdrop: LayerBackdrop): Modifier =
    if (LocalGlassStyle.current.blurred) layerBackdrop(backdrop) else this

/** COUI's press feedback: a damped shrink while the finger is down. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.95f): Modifier {
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (down) pressed else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

