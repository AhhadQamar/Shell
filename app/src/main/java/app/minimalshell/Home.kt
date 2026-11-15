package app.minimalshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(0.5f), Color.Black.copy(0.15f), Color.Black.copy(0.6f))
                )
            )
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
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // status strip: date left, battery right, thin battery line beneath
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tick.date, color = Dim, fontFamily = Mono, fontSize = 13.sp)
            Text(
                (if (tick.charging) "+" else "") + "${tick.battery}%",
                color = accent, fontFamily = Mono, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(0.12f))) {
            Box(
                Modifier
                    .fillMaxWidth(tick.battery / 100f)
                    .fillMaxHeight()
                    .background(accent)
            )
        }

        Spacer(Modifier.weight(1f))
        Text(tick.time, color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Light)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
            tick.alarm?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, color = Dim, fontFamily = Mono, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1.4f))

        // dock
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(0.08f), RoundedCornerShape(24.dp))
                .padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalArrangement = if (dock.isEmpty()) Arrangement.Center else Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dock.isEmpty()) {
                Text("long-press an app in the drawer to pin it", color = Dim, fontFamily = Mono, fontSize = 12.sp)
            }
            dock.forEach { app ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    AppIcon(
                        app, 48.dp,
                        Modifier.pressable(
                            onClick = { state.launch(app) },
                            onLongClick = { menu = true },
                        ),
                    )
                    AppMenu(app, state, menu) { menu = false }
                }
            }
        }

        // drawer handle
        Box(
            Modifier.fillMaxWidth().height(36.dp).clickable { onOpenDrawer() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(accent.copy(0.8f), RoundedCornerShape(2.dp)))
        }
    }
}

/** Long-press on empty home space. */
@Composable
fun HomeMenu(onShellSettings: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.4f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(bottom = 110.dp)
                .width(220.dp)
                .background(Color(0xF2181A1D), RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp)
        ) {
            MenuRow("Wallpaper") { pickWallpaper(ctx); onDismiss() }
            MenuRow("Shell settings") { onShellSettings() }
            MenuRow("System settings") { openSystemSettings(ctx); onDismiss() }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
