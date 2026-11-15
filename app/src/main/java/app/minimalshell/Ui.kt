package app.minimalshell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp

val Dim = Color.White.copy(alpha = 0.55f)
val Mono = FontFamily.Monospace

/** Click + long-click with a small press-scale animation and haptic on long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val haptic = LocalHapticFeedback.current
    return this
        .scale(scale)
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

@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    Image(app.bitmap, contentDescription = app.label, modifier = modifier.size(size))
}

@Composable
fun AppMenu(app: AppEntry, state: ShellState, expanded: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pinned = app.pkg in state.pinned
    val hidden = app.pkg in state.hidden
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!hidden) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin" else "Pin to dock") },
                onClick = { state.togglePin(app.pkg); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (hidden) "Unhide" else "Hide") },
            onClick = { state.toggleHidden(app.pkg); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("App info") },
            onClick = { openAppInfo(ctx, app.pkg); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Uninstall") },
            onClick = { uninstallApp(ctx, app.pkg); onDismiss() },
        )
    }
}
