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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

/** Space the long-press menu leaves for the dock so the two never overlap. */
private val DOCK_CLEARANCE = 80.dp

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
    tick: Tick,
    appsLoaded: Boolean,
    drawer: DrawerState,
    onLongPress: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val shadeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    val netDrag = remember { FloatArray(1) } // finger travel of the current swipe; positive = down
    val dock = state.pinned.mapNotNull { k -> apps.firstOrNull { it.key == k } }.take(MAX_PINNED)

    // a short staggered entrance every time Home comes back on screen
    val scope = rememberCoroutineScope()
    val entrance = remember { Entrance(scope) }
    LifecycleResumeEffect(Unit) {
        entrance.play()
        onPauseOrDispose { entrance.cancel() }
    }
    LaunchedEffect(Unit) {
        delay(2500)
        entrance.showNow() // only does anything if the entrance somehow never started
    }

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

        // the pinned apps: on a growth line fanning up from the bottom-right corner, where the thumb rests
        ShellDock(keys = dock.map { it.key }, slots = MAX_PINNED, entrance = entrance) { i ->
            val app = dock[i]
            var menu by remember { mutableStateOf(false) }
            Box {
                ShellIcon(
                    app, SHELL_TILE,
                    Modifier.pressable(onClick = { state.launch(app) }, onLongClick = { menu = true }),
                )
                AppMenu(app, state, menu) { menu = false }
            }
        }

        // everything else sits at the top left: the clock is the hero
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().entrance(entrance, 0),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tick.date.uppercase(), style = ink(11.sp, mono = true, tracking = 1.5.sp))
                Text(
                    (if (tick.charging) "+" else "") + "${tick.battery}%",
                    style = ink(12.sp, accent, mono = true, tracking = 1.sp),
                )
            }

            StackedClock(
                tick.time, accent,
                Modifier.padding(top = 18.dp).entrance(entrance, 100).pressable(onClick = { openClock(ctx, apps) }),
            )
            Text(
                tick.dayName, style = ink(17.sp),
                modifier = Modifier.padding(top = 8.dp, start = 4.dp).entrance(entrance, 180),
            )
            tick.alarm?.let {
                Text(
                    it.uppercase(), style = ink(11.sp, accent, mono = true, tracking = 1.sp),
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp).entrance(entrance, 220).pressable(onClick = { openClock(ctx, apps) }),
                )
            }
            Text(
                "${tick.timeLeft} left today".uppercase(),
                style = ink(11.sp, Dim, mono = true, tracking = 1.sp),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp).entrance(entrance, 260),
            )

            // only suggest pinning once we know there really are no pins, not while apps are still loading
            if (appsLoaded && state.pinned.isEmpty()) {
                Text(
                    "long-press an app in the drawer to pin it",
                    style = ink(11.sp, Dim, mono = true),
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 120.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // handle: tap it (or swipe up anywhere) to open the drawer. Kept short, so it stays clear of the first pinned app.
            Row(
                Modifier
                    .height(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open apps",
                    ) { drawer.open() }
                    .entrance(entrance, 320),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(accent, ScallopShape()))
                Spacer(Modifier.width(10.dp))
                Text("APPS", style = ink(11.sp, Dim, mono = true, tracking = 2.sp))
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
        Text(label, style = ink(21.sp))
    }
}
