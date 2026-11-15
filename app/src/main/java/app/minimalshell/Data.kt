package app.minimalshell

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MAX_PINNED = 5

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val icon: Drawable,
) {
    /** Rendered once, then reused by every list row. */
    val bitmap: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { icon.toBitmap(128, 128).asImageBitmap() }

    /** Section letter for the drawer; anything that isn't A-Z goes under '#'. */
    fun letter(): Char {
        val c = label.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }
}

// ---------- apps ----------

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            val ai = it.activityInfo
            AppEntry(
                label = it.loadLabel(pm).toString(),
                pkg = ai.packageName,
                component = ComponentName(ai.packageName, ai.name),
                icon = it.loadIcon(pm),
            )
        }
        .sortedBy { it.label.lowercase() }
        .also { list -> list.forEach { it.bitmap } } // pre-render icons off the main thread
}

/** App list that reloads itself when packages are installed, removed or updated. */
@Composable
fun rememberApps(): State<List<AppEntry>> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(emptyList<AppEntry>()) }
    DisposableEffect(Unit) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val reload = { scope.launch { state.value = loadApps(ctx) }; Unit }
        reload()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { reload() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            ctx.unregisterReceiver(receiver)
            scope.cancel()
        }
    }
    return state
}

private fun newTask(i: Intent): Intent = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun launchApp(ctx: Context, app: AppEntry) {
    ctx.startActivity(
        newTask(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    )
}

fun openAppInfo(ctx: Context, pkg: String) {
    ctx.startActivity(
        newTask(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    )
}

fun uninstallApp(ctx: Context, pkg: String) {
    ctx.startActivity(newTask(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))))
}

fun webSearch(ctx: Context, q: String) {
    ctx.startActivity(
        newTask(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))))
    )
}

fun pickWallpaper(ctx: Context) {
    try {
        ctx.startActivity(newTask(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")))
    } catch (_: Exception) {
    }
}

fun openSystemSettings(ctx: Context) {
    ctx.startActivity(newTask(Intent(Settings.ACTION_SETTINGS)))
}

fun copyToClipboard(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("result", text))
}

/** Pulls the notification shade down. Uses a hidden API, so it quietly does nothing if the OS blocks it. */
@SuppressLint("WrongConstant")
fun expandShade(ctx: Context) {
    try {
        val sbm = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
    } catch (_: Throwable) {
    }
}

// ---------- persisted settings ----------

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("shell", Context.MODE_PRIVATE)

    fun pinned(c: Context): List<String> =
        sp(c).getString("pinned", "").orEmpty().split(",").filter { it.isNotBlank() }

    fun setPinned(c: Context, l: List<String>) {
        sp(c).edit().putString("pinned", l.joinToString(",")).apply()
    }

    fun hidden(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet()).orEmpty().toSet()

    fun setHidden(c: Context, s: Set<String>) {
        sp(c).edit().putStringSet("hidden", s).apply()
    }

    fun counts(c: Context): Map<String, Int> =
        sp(c).getString("counts", "").orEmpty().split(",")
            .mapNotNull { e ->
                val p = e.split("=")
                if (p.size == 2) p[1].toIntOrNull()?.let { p[0] to it } else null
            }
            .toMap()

    fun setCounts(c: Context, m: Map<String, Int>) {
        sp(c).edit().putString("counts", m.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }

    /** 0 means "use the wallpaper". */
    fun accent(c: Context): Int = sp(c).getInt("accent", 0)

    fun setAccent(c: Context, v: Int) {
        sp(c).edit().putInt("accent", v).apply()
    }

    fun autoKeyboard(c: Context): Boolean = sp(c).getBoolean("autoKeyboard", false)

    fun setAutoKeyboard(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("autoKeyboard", on).apply()
    }

    fun showFrequent(c: Context): Boolean = sp(c).getBoolean("frequent", true)

    fun setShowFrequent(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("frequent", on).apply()
    }
}

/** Observable launcher state: pins, hidden apps, launch counts and settings. */
class ShellState(private val ctx: Context) {
    var pinned by mutableStateOf(Prefs.pinned(ctx))
        private set
    var hidden by mutableStateOf(Prefs.hidden(ctx))
        private set
    var counts by mutableStateOf(Prefs.counts(ctx))
        private set
    var accentOverride by mutableIntStateOf(Prefs.accent(ctx))
        private set
    var showFrequent by mutableStateOf(Prefs.showFrequent(ctx))
        private set
    var autoKeyboard by mutableStateOf(Prefs.autoKeyboard(ctx))
        private set

    fun togglePin(pkg: String) {
        pinned = if (pkg in pinned) pinned - pkg else (pinned + pkg).takeLast(MAX_PINNED)
        Prefs.setPinned(ctx, pinned)
    }

    fun toggleHidden(pkg: String) {
        val nowHidden = pkg !in hidden
        hidden = if (nowHidden) hidden + pkg else hidden - pkg
        Prefs.setHidden(ctx, hidden)
        if (nowHidden && pkg in pinned) {
            pinned = pinned - pkg
            Prefs.setPinned(ctx, pinned)
        }
    }

    fun launch(app: AppEntry) {
        launchApp(ctx, app)
        counts = counts + (app.pkg to ((counts[app.pkg] ?: 0) + 1))
        Prefs.setCounts(ctx, counts)
    }

    fun setAccent(argb: Int) {
        accentOverride = argb
        Prefs.setAccent(ctx, argb)
    }

    fun updateShowFrequent(on: Boolean) {
        showFrequent = on
        Prefs.setShowFrequent(ctx, on)
    }

    fun updateAutoKeyboard(on: Boolean) {
        autoKeyboard = on
        Prefs.setAutoKeyboard(ctx, on)
    }
}

fun frequentApps(visible: List<AppEntry>, s: ShellState, n: Int = 4): List<AppEntry> =
    visible.filter { (s.counts[it.pkg] ?: 0) > 0 }
        .sortedByDescending { s.counts[it.pkg] ?: 0 }
        .take(n)

// ---------- wallpaper accent ----------

private val FallbackAccent = Color(0xFF8AB4F8)

/** Picks the most vivid of the wallpaper's primary/secondary/tertiary colors (saturation + brightness score). */
fun pickAccent(ctx: Context): Color {
    val wc = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?: return FallbackAccent
    val hsv = FloatArray(3)
    val best = listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor)
        .map { c ->
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            Triple(hsv[0], hsv[1], hsv[2])
        }
        .maxByOrNull { (_, s, v) -> s * 0.6f + v * 0.4f }
        ?: return FallbackAccent
    if (best.second < 0.15f) return Color.hsv(best.first, 0f, 0.9f) // grayscale wallpaper
    return Color.hsv(best.first, best.second.coerceIn(0.35f, 0.9f), best.third.coerceIn(0.75f, 1f))
}

@Composable
fun rememberAccent(): State<Color> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(pickAccent(ctx)) }
    DisposableEffect(Unit) {
        val wm = WallpaperManager.getInstance(ctx)
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> state.value = pickAccent(ctx) }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return state
}

// ---------- clock, battery, next alarm ----------

data class Tick(
    val time: String,
    val date: String,
    val battery: Int,
    val charging: Boolean,
    val alarm: String?,
    val dayFraction: Float, // 0..1, how far through today we are (drives the horizon line on Home)
)

fun readTick(ctx: Context): Tick {
    val now = LocalDateTime.now()
    val is24 = DateFormat.is24HourFormat(ctx)
    val timePattern = if (is24) "HH:mm" else "h:mm"
    val alarmPattern = if (is24) "HH:mm" else "h:mm a"

    val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = (b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

    val alarm = ctx.getSystemService(AlarmManager::class.java)
        ?.nextAlarmClock?.triggerTime
        ?.let { t ->
            val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
            val sameDay = z.toLocalDate() == LocalDate.now()
            val pattern = if (sameDay) alarmPattern else "EEE $alarmPattern"
            "alarm " + z.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).lowercase()
        }

    return Tick(
        time = now.format(DateTimeFormatter.ofPattern(timePattern)),
        date = now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())).lowercase(),
        battery = (level * 100 / scale).coerceIn(0, 100),
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL,
        alarm = alarm,
        dayFraction = now.toLocalTime().toSecondOfDay() / 86_400f,
    )
}

@Composable
fun rememberTick(): State<Tick> {
    val ctx = LocalContext.current
    return produceState(initialValue = readTick(ctx)) {
        while (true) {
            delay(10_000)
            value = readTick(ctx)
        }
    }
}
