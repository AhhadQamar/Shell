package app.minimalshell

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import java.text.Collator
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the list of installed launcher apps comes from. [load] builds the list (labels + ready-to-draw icons, sorted),
 * [changes] says when it is out of date. Nothing here knows about the UI.
 *
 * Uses the system's LauncherApps service, the API made for launchers: it reports exactly what a launcher should show,
 * tells us about installs, removals, updates and suspended apps without hand-rolled broadcast filtering, and is the
 * foundation for app shortcuts and work/private profiles later.
 */
class AppRepository(private val app: Application) {
    private val launcherApps: LauncherApps = app.getSystemService(LauncherApps::class.java)
    private val me: UserHandle = Process.myUserHandle() // main profile only, for now

    /** Loads every app in parallel (label + icon work is CPU-bound and was running one app at a time). */
    suspend fun load(): List<AppEntry> = coroutineScope {
        val pm = app.packageManager
        val metrics = app.resources.displayMetrics
        val px = (LARGEST_ICON_DP * metrics.density).roundToInt().coerceIn(96, 256)
        // a Collator sorts by the user's language rules (accents interleave with their base letter, case is ignored)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        launcherApps.getActivityList(null, me)
            .filter { it.componentName.packageName != app.packageName }
            .map { info -> async { toEntry(info, pm, px, metrics.densityDpi) } }
            .awaitAll()
            .filterNotNull()
            .sortedWith(Comparator<AppEntry> { a, b -> collator.compare(a.label, b.label) })
    }

    /**
     * The app's own quick actions ("new chat", "selfie"...), as shown by the long-press menu. The system only
     * hands these to the default home app; for anyone else the call is refused and this returns an empty list.
     */
    suspend fun shortcuts(app: AppEntry): List<AppShortcut> = withContext(Dispatchers.Default) {
        try {
            if (!launcherApps.hasShortcutHost()) return@withContext emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.pkg)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            launcherApps.getShortcuts(query, me).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(MAX_SHORTCUTS)
                .map { AppShortcut(it.id, it.getPackage(), it.shortLabel?.toString() ?: it.id) }
        } catch (e: Exception) {
            Log.w("Shell", "no shortcuts for ${app.pkg}", e)
            emptyList()
        }
    }

    fun startShortcut(s: AppShortcut): Boolean =
        try {
            launcherApps.startShortcut(s.pkg, s.id, null, null, me)
            true
        } catch (e: Exception) {
            Log.w("Shell", "couldn't start shortcut ${s.id}", e)
            false
        }

    /**
     * Emits whenever an app was installed, removed, updated, enabled/disabled or (un)suspended.
     * Collectors should debounce: events come in bursts. During an update the system reports "changed",
     * not "removed" + "added", so an updating app never briefly disappears.
     */
    fun changes(): Flow<Unit> = callbackFlow<Unit> {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(Unit) }
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
                trySend(Unit)
            }
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
                trySend(Unit)
            }
            override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) { trySend(Unit) }
            override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) { trySend(Unit) }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(callback) }
    }.conflate()
}

private fun toEntry(info: LauncherActivityInfo, pm: PackageManager, px: Int, densityDpi: Int): AppEntry? =
    // one misbehaving app (bad label/icon resource) must not take the whole launcher down
    runCatching {
        val cn = info.componentName
        AppEntry(
            label = runCatching { info.label.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: cn.packageName,
            pkg = cn.packageName,
            component = cn,
            bitmap = renderIcon(runCatching { info.getBadgedIcon(densityDpi) }.getOrNull() ?: pm.defaultActivityIcon, px),
            system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }.onFailure { Log.w("Shell", "skipping ${info.componentName}", it) }.getOrNull()

private const val MAX_SHORTCUTS = 4

/** The biggest an icon is ever drawn (the frequent-apps row), so bitmaps are rendered once at that size. */
private const val LARGEST_ICON_DP = 48

/** One quick action of an app, e.g. "New message". Only the text is shown, in keeping with the rest of the UI. */
data class AppShortcut(val id: String, val pkg: String, val label: String)
