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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.key
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity

/** Outer size of a pinned app's ring on the dock (the icon inside is 14dp smaller). Change this one number to resize. */
private val DOCK_RING = 48.dp

/** Space the long-press menu leaves for the dock so the two never overlap. */
private val DOCK_CLEARANCE = 112.dp

/**
 * Home is built from two axes that meet in an L-shaped corner above the dock:
 *   - the HORIZON (x axis) is today: the dot is where you are between 00:00 and 24:00
 *   - the SPINE (y axis) is the battery, filling from the horizon upward
 * Everything above the clock is bare wallpaper.
 */
@Composable
fun Home(
    apps: List<AppEntry>,
    state: ShellViewModel,
    accent: Color,
    appsLoaded: Boolean,
    drawer: DrawerState,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val tick by rememberTick()
    val shadeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    val netDrag = remember { FloatArray(1) } // finger travel of the current swipe; positive = down
    val dock = state.pinned.mapNotNull { k -> apps.firstOrNull { it.key == k } }.take(MAX_PINNED)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    // does nothing unless the optional lock service is switched on in Shell's settings
                    onDoubleTap = { ShellAccessibilityService.lockScreen() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dy ->
                    netDrag[0] += dy
                    // swiping up pulls the drawer up under the finger; a net downward pull is left alone (shade)
                    if (netDrag[0] < 0f || drawer.progress > 0f) drawer.dragBy(dy)
                },
                onDragStarted = { netDrag[0] = 0f },
                onDragStopped = { velocity ->
                    val pulledDrawer = drawer.progress > 0f
                    drawer.settle(velocity)
                    if (!pulledDrawer && netDrag[0] > shadeThreshold) expandShade(ctx) // swipe down: notifications
                },
            )
    ) {
        // The only scrim: a soft fade over the bottom third, behind the clock and dock.
        // The top two thirds of the wallpaper are untouched.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Sea.copy(0.6f))))
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

            // an editorial stack, left-aligned: the day in italic serif, a big serif clock, then the day as a tide
            Row(verticalAlignment = Alignment.Bottom) {
                Text(tick.dayName, style = ink(24.sp, accent, serif = true, italic = true))
                Spacer(Modifier.width(12.dp))
                Text(tick.longDate, style = ink(12.sp, Dim, mono = true), modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                tick.time,
                style = ink(96.sp, weight = FontWeight.Light, serif = true, tracking = (-3).sp),
                maxLines = 1,
                softWrap = false,
            )
            tick.alarm?.let {
                Text(it, style = ink(12.sp, accent, mono = true), modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(18.dp))
            TideWave(tick.dayFraction, accent, Modifier.fillMaxWidth().height(84.dp))
            TideNumerals(Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${tick.timeLeft} left today", style = ink(11.sp, Dim, mono = true))
                Text("week ${tick.weekNumber}", style = ink(11.sp, Dim, mono = true))
            }
            Spacer(Modifier.height(22.dp))

            // dock: pinned apps in thin rings
            Row(
                // min height = ring size, so the row doesn't jump when icons appear after the first load
                Modifier.fillMaxWidth().heightIn(min = DOCK_RING),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // only suggest pinning once we know there really are no pins, not while apps are still loading
                if (appsLoaded && state.pinned.isEmpty()) {
                    Text("long-press an app in the drawer to pin it", style = ink(12.sp, Dim, mono = true))
                }
                dock.forEach { app ->
                    key(app.key) { // keeps each icon's menu state with its own app when the dock changes
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Porthole(
                                app, DOCK_RING,
                                Modifier.pressable(onClick = { state.launch(app) }, onLongClick = { menu = true }),
                            )
                            AppMenu(app, state, menu) { menu = false }
                        }
                    }
                }
            }

            // handle: two small swells; tap it (or swipe up) to open the drawer
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open apps",
                    ) { drawer.open() },
                contentAlignment = Alignment.Center,
            ) {
                SwellHandle(accent, Modifier.size(34.dp, 10.dp))
            }
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
            .background(Brush.verticalGradient(listOf(Color.Transparent, Sea.copy(0.78f))))
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
        Text(label, style = ink(24.sp, serif = true, italic = true))
    }
}
