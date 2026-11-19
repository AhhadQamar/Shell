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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.os.Build
import androidx.compose.ui.text.font.FontWeight

private val Presets = listOf(
    0xFFFFB48A, 0xFFFF9E6B, 0xFF8AB4F8, 0xFF7BD88F, 0xFFE58AC0, 0xFFC9A7FF, 0xFFFFD166,
).map { it.toInt() }

/** Same language as the drawer: wallpaper stays visible on top, text sits on a bottom fade, hairlines divide. */
@Composable
fun SettingsSheet(apps: List<AppEntry>, state: ShellViewModel, accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hiddenApps = apps.filter { it.key in state.hidden }
    // re-check when coming back from the system screen where the default home app is chosen
    var isDefault by remember { mutableStateOf(isDefaultHome(ctx)) }
    LifecycleResumeEffect(Unit) {
        isDefault = isDefaultHome(ctx)
        onPauseOrDispose { }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to Sea.copy(0.8f),
                    1f to Sea.copy(0.94f),
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
            Text("Settings", style = ink(30.sp, accent, FontWeight.Light))
            Spacer(Modifier.height(8.dp))
            Hairline(Modifier.fillMaxWidth())

            if (!isDefault) {
                Row(
                    Modifier.fillMaxWidth().clickable { openDefaultHomeSettings(ctx) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("make shell your home", style = ink(18.sp))
                        Text("the home button and app shortcuts need it", style = ink(11.sp, Dim, mono = true))
                    }
                    Text("set ›", style = ink(13.sp, accent, mono = true))
                }
                Hairline(Modifier.fillMaxWidth())
            }

            // accent
            Spacer(Modifier.height(18.dp))
            Text("accent", style = ink(18.sp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val mood = state.accentOverride == ACCENT_MOOD
                Box(
                    Modifier
                        .size(32.dp)
                        .background(ShellMood.palette.accent, ScallopShape())
                        .border(if (mood) 2.dp else 0.dp, if (mood) Sand else Color.Transparent, ScallopShape())
                        .clickable { state.setAccent(ACCENT_MOOD) }
                )
                val auto = state.accentOverride == 0
                Box(
                    Modifier
                        .size(32.dp)
                        .border(1.5.dp, if (auto) Sand else Dim, RoundedCornerShape(5.dp))
                        .clickable { state.setAccent(0) },
                    contentAlignment = Alignment.Center,
                ) { Text("A", style = ink(13.sp, if (auto) Sand else Dim, mono = true)) }
                Presets.forEach { argb ->
                    val selected = state.accentOverride == argb
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(Color(argb), RoundedCornerShape(5.dp))
                            .border(if (selected) 2.dp else 0.dp, if (selected) Sand else Color.Transparent, RoundedCornerShape(5.dp))
                            .clickable { state.setAccent(argb) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("the shell follows the mood; A follows your wallpaper", style = ink(11.sp, Dim, mono = true))

            SettingToggle("colours follow the day", "dawn, day, dusk and night; off keeps it dark", state.moodFollowsDay, accent) {
                state.updateMoodFollowsDay(it)
            }
            SettingToggle("shell background", "the shell's own backdrop instead of your wallpaper", state.shellBackdrop, accent) {
                state.updateShellBackdrop(it)
            }
            SettingToggle("frequent apps", "most-used apps at the bottom of the drawer", state.showFrequent, accent) {
                state.updateShowFrequent(it)
            }
            SettingToggle("type to search", "open the keyboard as soon as the drawer opens", state.autoKeyboard, accent) {
                state.updateAutoKeyboard(it)
            }
            SettingToggle("instant launch", "open an app by itself once it is the only match", state.instantLaunch, accent) {
                state.updateInstantLaunch(it)
            }
            SettingToggle("keep hidden apps out of search", "otherwise they still show up there", state.hideHiddenInSearch, accent) {
                state.updateHideHiddenInSearch(it)
            }
            // tap to switch to the next engine; it is the one used for the web search at the end of a query
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth().clickable { state.cycleSearchEngine() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("search engine", style = ink(18.sp))
                    Text("for the web search at the end of the drawer", style = ink(12.sp, Dim))
                }
                Text(SearchEngines.byId(state.searchEngine).name + " \u203A", style = ink(13.sp, accent, mono = true))
            }

            // optional accessibility service: double-tap to lock (needs Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lockOn = ShellAccessibilityService.running
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { openAccessibilitySettings(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("double-tap to lock", style = ink(18.sp))
                        Text(
                            if (lockOn) "double-tap the home screen to lock; also opens the shade reliably"
                            else "needs shell's lock service, switched on in accessibility settings",
                            style = ink(11.sp, Dim, mono = true),
                        )
                    }
                    Text(if (lockOn) "on" else "set ›", style = ink(13.sp, accent, mono = true))
                }
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
            Text(
                if (state.hideHiddenInSearch) "hidden apps never show up in search" else "hidden apps still show up when you search",
                style = ink(11.sp, Dim, mono = true),
            )
        }
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ink(18.sp))
            Text(subtitle, style = ink(12.sp, Dim))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Sea,
                checkedTrackColor = accent,
                uncheckedThumbColor = Dim,
                uncheckedTrackColor = Sand.copy(0.1f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
