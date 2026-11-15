package app.minimalshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Space the long-press menu leaves for the dock so the two never overlap. */
private val DOCK_CLEARANCE = 148.dp

/**
 * Home is built from two axes that meet in an L-shaped corner above the dock:
 *   - the HORIZON (x axis) is today: the dot is where you are between 00:00 and 24:00
 *   - the SPINE (y axis) is the battery, filling from the horizon upward
 * Everything above the clock is bare wallpaper.
 */
@Composable
fun Home(
    apps: List<AppEntry>,
    state: ShellState,
    accent: Color,
    onOpenDrawer: () -> Unit,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val tick by rememberTick()
    var drag by remember { mutableFloatStateOf(0f) }
    val dock = state.pinned.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.take(MAX_PINNED)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        when {
                            drag < -80.dp.toPx() -> onOpenDrawer() // swipe up: drawer
                            drag > 80.dp.toPx() -> expandShade(ctx) // swipe down: notifications
                        }
                    },
                    onVerticalDrag = { _, dy -> drag += dy },
                )
            }
    ) {
        // The only scrim: a soft fade over the bottom third, behind the clock and dock.
        // The top two thirds of the wallpaper are untouched.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.5f))))
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tick.date, style = ink(13.sp, Dim, mono = true))
                Text(
                    (if (tick.charging) "+" else "") + "${tick.battery}%",
                    style = ink(13.sp, accent, mono = true),
                )
            }

            Spacer(Modifier.weight(1f))

            // clock (left) and battery spine (right edge); the spine is as tall as the clock block
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(tick.time, style = ink(76.sp, weight = FontWeight.Light))
                    tick.alarm?.let {
                        Text(it, style = ink(12.sp, Dim, mono = true), modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
                    }
                }
                BatterySpine(tick.battery, accent)
            }

            DayHorizon(tick.dayFraction, accent)

            // dock: bare icons sitting on the horizon, no backing panel
            Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = if (dock.isEmpty()) Arrangement.Start else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dock.isEmpty()) {
                    Text("long-press an app in the drawer to pin it", style = ink(12.sp, Dim, mono = true))
                }
                dock.forEach { app ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        AppIcon(
                            app, 52.dp,
                            Modifier.pressable(onClick = { state.launch(app) }, onLongClick = { menu = true }),
                        )
                        AppMenu(app, state, menu) { menu = false }
                    }
                }
            }

            // drawer handle: tiny, but a real 36dp-tall touch target with a screen-reader label
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open apps",
                    ) { onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(22.dp).height(2.dp).background(accent.copy(0.7f), RoundedCornerShape(1.dp)))
            }
        }
    }
}

/** Vertical gauge: 2dp track, accent fill growing up from the bottom (where it meets the horizon). */
@Composable
private fun BatterySpine(percent: Int, accent: Color) {
    Box(Modifier.width(2.dp).fillMaxHeight().background(Hair)) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight((percent / 100f).coerceIn(0.02f, 1f))
                .background(accent)
        )
    }
}

/** Horizontal line across the day: accent up to now, with a dot at the current moment. */
@Composable
private fun DayHorizon(fraction: Float, accent: Color) {
    val f = fraction.coerceIn(0.02f, 1f)
    Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hair))
        Box(Modifier.fillMaxWidth(f).height(1.dp).background(accent.copy(0.9f)))
        Box(Modifier.fillMaxWidth(f), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
        }
    }
}

/** Long-press on empty home space: a bare text list, no card. */
@Composable
fun HomeMenu(accent: Color, onShellSettings: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.7f))))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.padding(start = 24.dp, bottom = DOCK_CLEARANCE)) {
            MenuRow("wallpaper", accent) { pickWallpaper(ctx); onDismiss() }
            MenuRow("shell settings", accent) { onShellSettings() }
            MenuRow("system settings", accent) { openSystemSettings(ctx); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(label: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.pressableRow(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("›", style = ink(18.sp, accent, mono = true))
        Spacer(Modifier.width(12.dp))
        Text(label, style = ink(17.sp, mono = true))
    }
}
