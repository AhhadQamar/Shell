package app.minimalshell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxWidth

// ---------------------------------------------------------------------------
// Design tokens. See DESIGN.md: the wallpaper is never dimmed globally, so
// legibility comes from a soft text shadow (Lift) instead of dark panels.
// ---------------------------------------------------------------------------

val Dim = Color.White.copy(alpha = 0.64f)
val Hair = Color.White.copy(alpha = 0.18f)
val Mono = FontFamily.Monospace

/** Soft dark halo under text: keeps type readable on any wallpaper without a backing panel. */
val Lift = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 2f), blurRadius = 14f)

/** The one text-style factory: every piece of text in Shell goes through this. */
fun ink(
    size: TextUnit,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    tracking: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = if (mono) Mono else FontFamily.Default,
    letterSpacing = tracking,
    shadow = Lift,
)

/** A 1dp line, the basic drawing primitive of the design. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Hair) {
    Box(modifier.height(1.dp).background(color))
}

/** Fades the top [height] of a scrolling list into the wallpaper instead of cutting it off. */
fun Modifier.fadeTop(height: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black,
                startY = 0f,
                endY = height.toPx(),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

// ---------------------------------------------------------------------------
// Interaction
// ---------------------------------------------------------------------------

/** Icons: click + long-click with a small press-scale and a haptic on long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    val haptic = LocalHapticFeedback.current
    return this
        // read inside the lambda = the animation only redraws; it no longer recomposes the row every frame
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick?.let { long ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
        )
}

/** Text rows: instead of scaling, the row slides 6dp toward the spine while the finger is down. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressableRow(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shift by animateDpAsState(if (pressed) 6.dp else 0.dp, label = "shift")
    val haptic = LocalHapticFeedback.current
    return this
        .offset { IntOffset(shift.roundToPx(), 0) }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick?.let { long ->
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    long()
                }
            },
        )
}

// ---------------------------------------------------------------------------
// Shared composables
// ---------------------------------------------------------------------------

/** [label] is null where visible text next to the icon already names the app (avoids TalkBack saying it twice). */
@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier, label: String? = app.label) {
    Image(app.bitmap, contentDescription = label, modifier = modifier.size(size))
}

@Composable
fun AppMenu(app: AppEntry, state: ShellViewModel, expanded: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val drawer = LocalDrawerState.current
    val pinned = app.key in state.pinned
    val hidden = app.key in state.hidden
    // the app's own quick actions; loaded in the background while the menu is open, so opening it is never delayed
    val shortcuts by produceState(emptyList<AppShortcut>(), app.key, expanded) {
        value = if (expanded) state.shortcutsFor(app) else emptyList()
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(6.dp),
        containerColor = Color(0xF2121416),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, Hair),
    ) {
        shortcuts.forEach { s ->
            MenuItem(s.label) { state.startShortcut(s); drawer?.snapClosed(); onDismiss() }
        }
        if (shortcuts.isNotEmpty()) Hairline(Modifier.fillMaxWidth())
        if (!hidden) {
            MenuItem(if (pinned) "unpin" else "pin to dock") { state.togglePin(app.key); onDismiss() }
        }
        MenuItem(if (hidden) "unhide" else "hide") { state.toggleHidden(app.key); onDismiss() }
        MenuItem("app info") { openAppInfo(ctx, app.pkg); onDismiss() }
        if (!app.system) MenuItem("uninstall") { uninstallApp(ctx, app.pkg); onDismiss() }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = Mono)) },
        onClick = onClick,
    )
}
