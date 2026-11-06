package app.bellterm.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.bellterm.R
import io.github.suqi8.coui.kmp.blur.BlendColorEntry
import io.github.suqi8.coui.kmp.blur.BlurColors
import io.github.suqi8.coui.kmp.blur.ProgressiveBlur
import io.github.suqi8.coui.kmp.blur.progressiveTextureBlur
import io.github.suqi8.coui.kmp.blur.rememberLayerBackdrop

val GroupShape = RoundedCornerShape(26.dp)
val FieldShape = RoundedCornerShape(16.dp)

/**
 * A screen on the ambient background: content scrolls under a floating top bar
 * that blurs it progressively, and an optional floating [bottomBar] sits above
 * the navigation bar. [content] gets the padding that clears both.
 */
@Composable
fun GlassScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    titleAccessory: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val style = LocalGlassStyle.current
    val density = LocalDensity.current
    val ambient = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    var barHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize().background(style.base)) {
        Box(Modifier.fillMaxSize().captureBackdrop(page)) {
            Box(Modifier.fillMaxSize().captureBackdrop(ambient).background(style.base))
            CompositionLocalProvider(LocalGlassBackdrop provides ambient) {
                content(
                    PaddingValues(
                        top = with(density) { barHeight.toDp() } + 4.dp,
                        bottom = if (bottomBar != null) with(density) { bottomHeight.toDp() } + navigation + 28.dp else navigation + 24.dp,
                    ),
                )
            }
        }
        GlassTopBar(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            large = large,
            titleAccessory = titleAccessory,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeight = it.height }
                .let {
                    if (style.blurred) {
                        it.progressiveTextureBlur(
                            backdrop = page,
                            shape = RectangleShape,
                            blurRadius = 18f,
                            gradient = ProgressiveBlur.Top.copy(startFraction = 0.55f, endFraction = 1f),
                            colors = BlurColors(blendColors = listOf(BlendColorEntry(style.base.copy(alpha = if (style.dark) 0.55f else 0.5f)))),
                        )
                    } else {
                        it.background(style.base.copy(alpha = 0.96f))
                    }
                },
        )
        if (bottomBar != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .onSizeChanged { bottomHeight = it.height },
            ) {
                CompositionLocalProvider(LocalGlassBackdrop provides page) { bottomBar() }
            }
        }
        snackbar?.let { GlassSnackbarHost(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = with(density) { bottomHeight.toDp() })) }
    }
}

/** Snackbars as dark frosted capsules, readable over any background. */
@Composable
fun GlassSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xF21C212B),
            contentColor = Color.White,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
        )
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    large: Boolean,
    titleAccessory: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = if (onBack != null) 12.dp else 24.dp, end = 12.dp, top = 8.dp, bottom = 14.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onBack?.let {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = it, modifier = Modifier.testTag("back"))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    title,
                    style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                titleAccessory?.invoke()
            }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A round glass button holding one icon. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = LocalGlassStyle.current.tint,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .glass(CircleShape, tint = tint, highlight = LocalGlassStyle.current.smallHighlight)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = contentColor, modifier = Modifier.size(size * 0.5f))
    }
}

/** A glass capsule grouping several [CapsuleAction]s, as an iOS 26 toolbar does. */
@Composable
fun GlassCapsule(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(44.dp).glass(CircleShape, highlight = LocalGlassStyle.current.smallHighlight).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun CapsuleAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp, 44.dp)
            .pressScale(interaction, 0.85f)
            .clip(CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** A capsule button; [primary] fills it with the accent and refracts the backdrop at its rim. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    destructive: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val style = LocalGlassStyle.current
    val fill = when {
        destructive -> MaterialTheme.colorScheme.error.copy(alpha = if (style.dark) 0.2f else 0.12f)
        primary -> accent.copy(alpha = 0.88f)
        else -> style.tint
    }
    val content = when {
        destructive -> MaterialTheme.colorScheme.error
        primary -> if (accent.luminance() > 0.5f) Color(0xFF10151C) else Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.96f)
            .glass(CircleShape, tint = if (enabled) fill else fill.copy(alpha = fill.alpha * 0.4f), lens = primary && enabled, shadow = primary && enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (enabled) content else content.copy(alpha = 0.4f)
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(20.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A segmented control: a glass track with a sliding raised thumb, COUI style. */
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalGlassStyle.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (style.dark) 0.08f else 0.06f))
            .padding(3.dp),
    ) {
        val width = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(width * selected, spring(dampingRatio = 0.7f, stiffness = 500f), label = "thumb")
        Box(
            Modifier
                .offset(x = offset)
                .width(width)
                .fillMaxHeight()
                .glass(
                    CircleShape,
                    tint = if (style.dark) Color(0x5CFFFFFF) else Color(0xF2FFFFFF),
                    highlight = style.smallHighlight,
                    shadow = !style.dark,
                ),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                val color by animateColorAsState(
                    if (index == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segment",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(index) }
                        .testTag("segment:$label"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = color,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** A rounded glass group of rows, the container every settings-like list uses. */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glass(GroupShape),
        content = content,
    )
}

/** A hairline between the rows of a [GlassGroup], inset past their leading content. */
@Composable
fun GlassDivider(start: Dp = 16.dp) {
    HorizontalDivider(
        Modifier.padding(start = start, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    )
}

/** A COUI-style section caption above a group. */
@Composable
fun GlassSectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A small rounded tile with an icon, leading a row as system settings do. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun glassFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val style = LocalGlassStyle.current
    val fill = scheme.onSurface.copy(alpha = if (style.dark) 0.06f else 0.035f)
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = fill,
        errorContainerColor = scheme.error.copy(alpha = 0.06f),
        unfocusedBorderColor = scheme.onSurface.copy(alpha = 0.1f),
        focusedBorderColor = scheme.primary,
    )
}

/** [OutlinedTextField] in the glass style: a soft filled well with a rounded outline. */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = FieldShape,
        colors = glassFieldColors(),
    )
}

val MenuShape = RoundedCornerShape(22.dp)

/** Popups, drawers and dialogs live in their own windows with nothing to blur, so they stay opaque for legibility. */
@Composable
fun menuColor(): Color = if (LocalGlassStyle.current.dark) Color(0xFF222833) else Color(0xFFFFFFFF)

/** A dropdown menu in the glass style: a large radius and a dense frosted fill. */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(horizontal = 6.dp),
        offset = offset,
        shape = MenuShape,
        containerColor = menuColor(),
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        content = content,
    )
}

/** A small tinted label: a route, a certificate, a status. */
@Composable
fun GlassPill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = false) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (LocalGlassStyle.current.dark) 0.18f else 0.13f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Space that keeps the last item of a list clear of a floating bar. */
@Composable
fun BottomSpace(padding: PaddingValues) = Spacer(Modifier.height(padding.calculateBottomPadding()))

/** [AlertDialog] on a dense frosted card with COUI's large radius. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        // A dialog is its own window, outside the root that exposes test tags.
        text = text?.let { { Box(Modifier.semantics { testTagsAsResourceId = true }) { it() } } },
        shape = RoundedCornerShape(30.dp),
        containerColor = menuColor(),
        tonalElevation = 0.dp,
    )
}
