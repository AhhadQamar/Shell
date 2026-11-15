package app.minimalshell

import android.app.AlarmManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    // Registered only between onStart and onStop. While another app is in front, Shell no longer wakes
    // every minute and on every battery change to refresh a screen nobody can see; it re-reads on return.
    LifecycleStartEffect(Unit) {
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
        onStopOrDispose { ctx.unregisterReceiver(receiver) }
    }
    return state
}
