package app.minimalshell

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private var homeSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent { LauncherRoot(homeSignal) }
    }

    // Home button pressed while we're already running: close everything and show home.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal++
    }
}

@Composable
fun LauncherRoot(homeSignal: Int) {
    val ctx = LocalContext.current
    val appsOrNull by rememberApps()
    val apps = appsOrNull ?: emptyList()
    val wallpaperAccent by rememberAccent()
    val state = remember { ShellState(ctx) }
    // migrate old keys and drop uninstalled apps from pins / hidden / counts after every load
    LaunchedEffect(appsOrNull) { appsOrNull?.let { state.sync(it) } }
    val accent = if (state.accentOverride != 0) Color(state.accentOverride) else wallpaperAccent

    var drawerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(homeSignal) {
        drawerOpen = false
        menuOpen = false
        settingsOpen = false
    }
    BackHandler {
        when {
            settingsOpen -> { settingsOpen = false }
            menuOpen -> { menuOpen = false }
            else -> { drawerOpen = false }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            Home(
                apps, state, accent,
                appsLoaded = appsOrNull != null,
                onOpenDrawer = { drawerOpen = true },
                onLongPress = { menuOpen = true },
            )
            AnimatedVisibility(
                visible = drawerOpen,
                enter = slideInVertically(tween(260)) { it / 4 } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(200)) { it / 4 } + fadeOut(tween(160)),
            ) {
                Drawer(apps, state, accent) { drawerOpen = false }
            }
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
