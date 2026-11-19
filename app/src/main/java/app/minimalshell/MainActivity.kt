package app.minimalshell

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val vm: ShellViewModel by viewModels()
    private var homeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent { LauncherRoot(vm, homeSignal) }
    }

    // Home button pressed while we're already running: close everything and show home.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
    }
}

@Composable
fun LauncherRoot(state: ShellViewModel, homeSignal: Int) {
    val apps = state.apps ?: emptyList()
    val wallpaperAccent by rememberAccent()
    val tick by rememberTick()

    // The mood: always the dark night palette, or (if the user asks) drifting with the time of day.
    LaunchedEffect(tick.dayFraction, state.moodFollowsDay) {
        ShellMood.use(if (state.moodFollowsDay) Palettes.at((tick.dayFraction * 1440f).toInt()) else Palettes.Night)
    }
    val accent = when (state.accentOverride) {
        ACCENT_MOOD -> ShellMood.palette.accent
        0 -> wallpaperAccent
        else -> Color(state.accentOverride)
    }
    // light moods need dark status and navigation bar icons
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = ShellMood.palette.light
            c.isAppearanceLightNavigationBars = ShellMood.palette.light
        }
    }

    val scope = rememberCoroutineScope()
    val drawer = remember { DrawerState(scope) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(homeSignal) {
        drawer.close()
        menuOpen = false
        settingsOpen = false
    }
    BackHandler {
        when {
            settingsOpen -> { settingsOpen = false }
            menuOpen -> { menuOpen = false }
            else -> { drawer.close() }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        // wait for the saved settings (usually a few ms) so the accent color and dock never flash defaults
        if (state.ready) {
            CompositionLocalProvider(LocalDrawerState provides drawer) {
                Box(Modifier.fillMaxSize()) {
                    ShellBackdrop(solid = state.shellBackdrop)
                    Home(
                        apps, state, accent,
                        tick = tick,
                        appsLoaded = state.apps != null,
                        drawer = drawer,
                        onLongPress = { menuOpen = true },
                    )
                    // soft dim behind the drawer, growing with it; no pointer handling, so it never blocks touches
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = drawer.progress * 0.4f }.background(Sea))
                    // always composed and parked just off-screen: opening never has to build it from scratch
                    Drawer(apps, state, accent, drawer)
                    AnimatedVisibility(visible = menuOpen, enter = fadeIn(), exit = fadeOut()) {
                        HomeMenu(
                            accent,
                            onShellSettings = { menuOpen = false; settingsOpen = true },
                            onDismiss = { menuOpen = false },
                        )
                    }
                    AnimatedVisibility(
                        visible = settingsOpen,
                        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
                        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
                    ) {
                        SettingsSheet(apps, state, accent) { settingsOpen = false }
                    }
                }
            }
        }
    }
}
