package app.minimalshell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Presets = listOf(
    0xFFFF9E6B, 0xFF8AB4F8, 0xFF7BD88F, 0xFFE58AC0, 0xFFC9A7FF, 0xFFFFD166,
).map { it.toInt() }

@Composable
fun SettingsSheet(state: ShellState, accent: Color, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.5f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF14161A), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .pointerInput(Unit) { detectTapGestures { } } // taps inside the sheet don't dismiss it
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(24.dp)
        ) {
            Text("shell settings", color = Dim, fontFamily = Mono, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))

            Text("accent", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val auto = state.accentOverride == 0
                Box(
                    Modifier
                        .size(36.dp)
                        .border(1.5.dp, if (auto) Color.White else Dim, CircleShape)
                        .clip(CircleShape)
                        .clickable { state.setAccent(0) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", color = if (auto) Color.White else Dim, fontFamily = Mono, fontSize = 13.sp) }
                Presets.forEach { argb ->
                    val selected = state.accentOverride == argb
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                            .clickable { state.setAccent(argb) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("A follows your wallpaper", color = Dim, fontFamily = Mono, fontSize = 11.sp)

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("frequent apps", color = Color.White, fontSize = 16.sp)
                    Text("most-used apps at the top of the drawer", color = Dim, fontSize = 12.sp)
                }
                Switch(
                    checked = state.showFrequent,
                    onCheckedChange = { state.setShowFrequent(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF101214),
                        checkedTrackColor = accent,
                        uncheckedThumbColor = Dim,
                        uncheckedTrackColor = Color.White.copy(0.1f),
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "${state.hidden.size} hidden · hidden apps still show up when you search",
                color = Dim, fontFamily = Mono, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
