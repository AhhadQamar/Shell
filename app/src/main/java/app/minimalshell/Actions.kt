package app.minimalshell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager

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

fun webSearch(ctx: Context, q: String, engine: SearchEngine = SearchEngines.all.first()) {
    searchUrl(ctx, engine.url, q)
}

/** Opens [template] (a URL with %s where the search text goes) in the browser, or in the app that handles it. */
fun searchUrl(ctx: Context, template: String, query: String) {
    safeStart(
        ctx,
        Intent(Intent.ACTION_VIEW, Uri.parse(template.replace("%s", Uri.encode(query)))),
        "No browser found",
    )
}

/** Opens one of the system settings screens by its intent action. */
fun openSettingsPage(ctx: Context, action: String) {
    safeStart(ctx, Intent(action), "That settings page isn't available")
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
    // the accessibility service does this properly; the reflection below is only a fallback for when it is off
    if (ShellAccessibilityService.openShade()) return
    try {
        val sbm = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
    } catch (_: Throwable) {
    }
}

/** True when Shell is what the Home button opens (and so is allowed to read other apps' shortcuts). */
fun isDefaultHome(ctx: Context): Boolean {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val info = ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
    return info?.activityInfo?.packageName == ctx.packageName
}

/** The system screen where the default home app is chosen. */
fun openDefaultHomeSettings(ctx: Context) {
    safeStart(ctx, Intent(Settings.ACTION_HOME_SETTINGS))
}

fun openAccessibilitySettings(ctx: Context) {
    safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
