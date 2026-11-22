package app.minimalshell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast

private fun newTask(i: Intent): Intent =
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * startActivity throws (ActivityNotFoundException, SecurityException, ...) when
 * nothing can handle the intent. A launcher must never crash on that.
 * Returns true if the activity started.
 */
private fun safeStart(
    ctx: Context,
    intent: Intent,
    failMessage: String = "Couldn't open that"
): Boolean =
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
        "Couldn't open ${app.label}"
    )

fun openAppInfo(ctx: Context, pkg: String) {
    safeStart(
        ctx,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null)
        )
    )
}

fun uninstallApp(ctx: Context, pkg: String) {
    safeStart(
        ctx,
        Intent(
            Intent.ACTION_DELETE,
            Uri.fromParts("package", pkg, null)
        )
    )
}

fun webSearch(
    ctx: Context,
    q: String,
    engine: SearchEngine = SearchEngines.all.first()
) {
    searchUrl(ctx, engine.url, q)
}

/**
 * Opens template (a URL with %s where the search text goes) in the browser,
 * or in the app that handles it.
 */
fun searchUrl(ctx: Context, template: String, query: String) {
    safeStart(
        ctx,
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse(template.replace("%s", Uri.encode(query)))
        ),
        "No browser found"
    )
}

/** Opens one of the system settings screens by its intent action. */
fun openSettingsPage(ctx: Context, action: String) {
    safeStart(
        ctx,
        Intent(action),
        "That settings page isn't available"
    )
}

fun pickWallpaper(ctx: Context) {
    safeStart(
        ctx,
        Intent.createChooser(
            Intent(Intent.ACTION_SET_WALLPAPER),
            "Wallpaper"
        )
    )
}

fun openSystemSettings(ctx: Context) {
    safeStart(ctx, Intent(Settings.ACTION_SETTINGS))
}

fun copyToClipboard(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(
            ClipData.newPlainText("result", text)
        )
}

/** Pulls the notification shade down. */
@SuppressLint("WrongConstant")
fun expandShade(ctx: Context) {
    // The accessibility service performs this properly.
    if (ShellAccessibilityService.openShade()) {
        return
    }

    // Reflection fallback for when the accessibility service is off.
    try {
        val statusBar =
            ctx.getSystemService("statusbar")

        Class.forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(statusBar)
    } catch (_: Throwable) {
        // The hidden API is unavailable or was blocked by the OS.
    }
}

/** True when Shell is what the Home button opens. */
fun isDefaultHome(ctx: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)

    val info = ctx.packageManager.resolveActivity(
        home,
        PackageManager.MATCH_DEFAULT_ONLY
    )

    return info?.activityInfo?.packageName == ctx.packageName
}

/** The system screen where the default home app is chosen. */
fun openDefaultHomeSettings(ctx: Context) {
    safeStart(ctx, Intent(Settings.ACTION_HOME_SETTINGS))
}

fun openAccessibilitySettings(ctx: Context) {
    safeStart(
        ctx,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    )
}

// Clock apps of the common phone makers. Many of them ignore Android's generic "show alarms" request, so it is
// more reliable to find the installed app in the launcher's own list and open it like any other app.
private val KnownClockPackages = listOf(
    "com.google.android.deskclock", // Google Clock (Pixel and many others)
    "com.sec.android.app.clockpackage", // Samsung
    "com.android.deskclock", // AOSP, Xiaomi, Realme and others
    "com.oneplus.deskclock",
    "com.coloros.alarmclock", // Oppo and Realme
    "com.oplus.alarmclock",
    "com.huawei.deskclock",
    "com.transsion.deskclock", // Infinix, Tecno, itel
    "com.vivo.alarmclock",
    "com.motorola.blur.alarmclock",
    "com.asus.deskclock",
    "com.lge.clock",
)

/**
 * Opens the phone's clock app. Looks in [apps] for a well-known clock package, then for an app called "Clock", then
 * for anything with "clock" or "alarm" in its name. Only if none is found does it fall back to Android's generic
 * "show alarms" request, and then to a short message.
 */
fun openClock(ctx: Context, apps: List<AppEntry>) {
    val app = KnownClockPackages.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
        ?: apps.firstOrNull { it.label.equals("Clock", ignoreCase = true) }
        ?: apps.firstOrNull { it.label.contains("clock", ignoreCase = true) || it.label.contains("alarm", ignoreCase = true) }
    if (app != null && launchApp(ctx, app)) return
    safeStart(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS), "No clock app found")
}
