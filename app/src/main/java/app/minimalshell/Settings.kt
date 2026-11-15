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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Presets = listOf(
    0xFFFF9E6B, 0xFF8AB4F8, 0xFF7BD88F, 0xFFE58AC0, 0xFFC9A7FF, 0xFFFFD166,
).map { it.toInt() }

/** Same language as the drawer: wallpaper stays visible on top, text sits on a bottom fade, hairlines divide. */
@Composable
fun SettingsSheet(apps: List<AppEntry>, state: ShellState, accent: Color, onClose: () -> Unit) {
    val hiddenApps = apps.filter { it.key in state.hidden }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Black.copy(0.75f),
                    1f to Color.Black.copy(0.92f),
                )
            )
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } } // taps inside don't dismiss
                .windowInsetsPadding(WindowInsets.systemBars)
                // scrolls when the content is taller than the screen (landscape, small phones, large fonts)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text("shell / settings", style = ink(12.sp, accent, mono = true, tracking = 1.sp))
            Spacer(Modifier.height(10.dp))
            Hairline(Modifier.fillMaxWidth())

            // accent
            Spacer(Modifier.height(18.dp))
            Text("accent", style = ink(16.sp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val auto = state.accentOverride == 0
                Box(
                    Modifier
                        .size(32.dp)
                        .border(1.5.dp, if (auto) Color.White else Dim, RoundedCornerShape(5.dp))
                        .clickable { state.setAccent(0) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", style = ink(13.sp, if (auto) Color.White else Dim, mono = true)) }
                Presets.forEach { argb ->
                    val selected = state.accentOverride == argb
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(Color(argb), RoundedCornerShape(5.dp))
                            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                            .clickable { state.setAccent(argb) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("A follows your wallpaper", style = ink(11.sp, Dim, mono = true))

            SettingToggle("frequent apps", "most-used apps at the bottom of the drawer", state.showFrequent, accent) {
                state.updateShowFrequent(it)
            }
            SettingToggle("type to search", "open the keyboard as soon as the drawer opens", state.autoKeyboard, accent) {
                state.updateAutoKeyboard(it)
            }

            // hidden apps: finally manageable without having to search for them
            Spacer(Modifier.height(22.dp))
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text(
                if (hiddenApps.isEmpty()) "no hidden apps" else "${hiddenApps.size} hidden · tap to unhide",
                style = ink(12.sp, Dim, mono = true),
            )
            if (hiddenApps.isNotEmpty()) {
                Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    hiddenApps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { state.toggleHidden(app.key) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app, 28.dp, label = null)
                            Spacer(Modifier.size(12.dp))
                            Text(app.label, style = ink(15.sp), modifier = Modifier.weight(1f), maxLines = 1)
                            Text("unhide", style = ink(11.sp, accent, mono = true))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("hidden apps still show up when you search", style = ink(11.sp, Dim, mono = true))
        }
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ink(16.sp))
            Text(subtitle, style = ink(12.sp, Dim))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF101214),
                checkedTrackColor = accent,
                uncheckedThumbColor = Dim,
                uncheckedTrackColor = Color.White.copy(0.1f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
