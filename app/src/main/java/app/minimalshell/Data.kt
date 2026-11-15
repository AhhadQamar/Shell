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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MAX_PINNED = 5

data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val icon: Drawable,
    val system: Boolean,
) {
    /**
     * Identity for pins, hidden apps and launch counts. It is the component ("pkg/Class"), not the package,
     * because one package can expose several launcher entries and each must be pinned/hidden on its own.
     */
    val key: String = component.flattenToString()

    /** Rendered once, then reused by every list row. A broken icon becomes a placeholder, never a crash. */
    val bitmap: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { renderIcon(icon) }

    /** Section letter for the drawer; anything that isn't A-Z goes under '#'. */
    fun letter(): Char {
        // NFD splits "É" into "E" + a combining accent, so accented Latin labels land under their base letter
        val c = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }
}

private fun renderIcon(d: Drawable): ImageBitmap =
    try {
        d.toBitmap(128, 128).asImageBitmap()
    } catch (_: Throwable) {
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(64f, 64f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF })
        }.asImageBitmap()
    }

// ---------- text helpers ----------

/** Lowercase and strip accents, so "cafe" finds "Café". */
fun String.fold(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

// ---------- apps ----------

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(query, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        // one misbehaving app (bad label/icon resource) must not take the whole launcher down
        .mapNotNull { ri ->
            runCatching {
                val ai = ri.activityInfo
                AppEntry(
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: ai.packageName,
                    pkg = ai.packageName,
                    component = ComponentName(ai.packageName, ai.name),
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon,
                    system = (ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }.onFailure { Log.w("Shell", "skipping ${ri.activityInfo?.name}", it) }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
        .also { list -> list.forEach { it.bitmap } } // pre-render icons off the main thread
}

/**
 * App list that reloads itself when packages are installed, removed or updated.
 * Null means "first load not finished yet", which is different from "no apps".
 */
@Composable
fun rememberApps(): State<List<AppEntry>?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<List<AppEntry>?>(null) }
    DisposableEffect(Unit) {
        val scope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() +
                CoroutineExceptionHandler { _, e -> Log.e("Shell", "app load failed", e) },
        )
        // One job slot: a newer request cancels an older one, so a slow stale load can't overwrite a fresh one.
        // Package events are debounced because ACTION_PACKAGE_CHANGED fires in bursts and each reload re-renders icons.
        var job: Job? = null
        val reload = { wait: Long ->
            job?.cancel()
            job = scope.launch {
                if (wait > 0) delay(wait)
                val result = runCatching { loadApps(ctx) }
                ensureActive() // cancelled while loading: drop the result
                result.onSuccess { state.value = it }
                    .onFailure {
                        Log.e("Shell", "loadApps failed", it)
                        if (state.value == null) state.value = emptyList()
                    }
            }
            Unit
        }
        reload(0)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // an update sends REMOVED(replacing) then ADDED; reacting to the first would briefly "uninstall" the app
                if (i.action == Intent.ACTION_PACKAGE_REMOVED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                reload(400)
            }
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

/**
 * startActivity throws (ActivityNotFoundException, SecurityException, ...) when nothing can handle the intent:
 * a disabled app, no browser installed, an app removed a moment ago. A launcher must never crash on that.
 * Returns true if the activity started.
 */
private fun safeStart(ctx: Context, intent: Intent, failMessage: String = "Couldn't open that"): Boolean =
    try {
        ctx.startActivity(newTask(intent))
        true
    } catch (_: Exception) {
        Toast.makeText(ctx, failMessage, Toast.LENGTH_SHORT).show()
        false
    }

fun launchApp(ctx: Context, app: AppEntry): Boolean =
    safeStart(
        ctx,
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        "Couldn't open ${app.label}",
    )

fun openAppInfo(ctx: Context, pkg: String) {
    safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
}

fun uninstallApp(ctx: Context, pkg: String) {
    safeStart(ctx, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
}

fun webSearch(ctx: Context, q: String) {
    safeStart(
        ctx,
        Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(q))),
        "No browser found",
    )
}

fun pickWallpaper(ctx: Context) {
    safeStart(ctx, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
}

fun openSystemSettings(ctx: Context) {
    safeStart(ctx, Intent(Settings.ACTION_SETTINGS))
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

    /** Keys of the apps installed right now; empty until the first successful load. */
    private var liveKeys: Set<String> = emptySet()

    // pinned / hidden / counts are all keyed by AppEntry.key

    fun togglePin(key: String) {
        // entries for apps that no longer exist must not eat dock slots
        val used = if (liveKeys.isEmpty()) pinned.size else pinned.count { it in liveKeys }
        if (key !in pinned && used >= MAX_PINNED) {
            Toast.makeText(ctx, "Dock is full ($MAX_PINNED). Unpin one first.", Toast.LENGTH_SHORT).show()
            return
        }
        pinned = if (key in pinned) pinned - key else pinned + key
        Prefs.setPinned(ctx, pinned)
    }

    fun toggleHidden(key: String) {
        val nowHidden = key !in hidden
        hidden = if (nowHidden) hidden + key else hidden - key
        Prefs.setHidden(ctx, hidden)
        if (nowHidden && key in pinned) {
            pinned = pinned - key
            Prefs.setPinned(ctx, pinned)
        }
    }

    fun launch(app: AppEntry) {
        if (!launchApp(ctx, app)) return
        counts = counts + (app.key to ((counts[app.key] ?: 0) + 1))
        Prefs.setCounts(ctx, counts)
    }

    private fun isInstalled(pkg: String): Boolean =
        try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Call after every app load. Does two things:
     *  1. migrates old package-only entries ("com.foo") to component keys ("com.foo/.Main"),
     *  2. drops entries for apps that were uninstalled, so they stop taking dock slots and storage.
     * An empty list (failed load) is ignored so a bad load can never wipe the user's pins.
     */
    fun sync(apps: List<AppEntry>) {
        if (apps.isEmpty()) return
        liveKeys = apps.mapTo(HashSet()) { it.key }
        val byPkg = apps.groupBy { it.pkg }
        // a key is kept if the app is live, or its package is still installed (e.g. launcher entry temporarily disabled)
        fun keep(k: String) = k in liveKeys || isInstalled(k.substringBefore('/'))
        fun legacyFirst(k: String) = if ('/' in k) k else byPkg[k]?.first()?.key ?: k

        val newPinned = pinned.map(::legacyFirst).distinct().filter(::keep)
        val newHidden = hidden
            .flatMap { k -> if ('/' in k) listOf(k) else byPkg[k]?.map { it.key } ?: listOf(k) }
            .filter(::keep)
            .toSet()
        val newCounts = HashMap<String, Int>()
        counts.forEach { (k, n) ->
            val nk = legacyFirst(k)
            if (keep(nk)) newCounts[nk] = (newCounts[nk] ?: 0) + n
        }

        if (newPinned != pinned) { pinned = newPinned; Prefs.setPinned(ctx, newPinned) }
        if (newHidden != hidden) { hidden = newHidden; Prefs.setHidden(ctx, newHidden) }
        if (newCounts != counts) { counts = newCounts; Prefs.setCounts(ctx, newCounts) }
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
    visible.filter { (s.counts[it.key] ?: 0) > 0 }
        .sortedByDescending { s.counts[it.key] ?: 0 }
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

/**
 * Refreshes on the system's own minute tick (and on time/zone/battery/screen-on changes) instead of polling.
 * ACTION_TIME_TICK can only be received by a receiver registered at runtime, which is what happens here.
 */
@Composable
fun rememberTick(): State<Tick> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(readTick(ctx)) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { state.value = readTick(ctx) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        state.value = readTick(ctx)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return state
}
