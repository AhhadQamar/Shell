package app.minimalshell

import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

class AppRepository(private val app: Context) {
    private val launcherApps: LauncherApps = app.getSystemService(LauncherApps::class.java)
    private val me: UserHandle = Process.myUserHandle()

    suspend fun load(): List<AppEntry> = coroutineScope {
        val pm = app.packageManager
        val metrics = app.resources.displayMetrics
        val px = (LARGEST_ICON_DP * metrics.density).roundToInt().coerceIn(96, 256)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

        launcherApps.getActivityList(null, me)
            .filter { it.componentName.packageName != app.packageName }
            .map { info -> async { toEntry(info, pm, px, metrics.densityDpi) } }
            .awaitAll()
            .filterNotNull()
            .sortedWith(Comparator<AppEntry> { a, b -> collator.compare(a.label, b.label) })
    }

    suspend fun shortcuts(app: AppEntry): List<AppShortcut> = withContext(Dispatchers.Default) {
        try {
            if (!hasShortcutHostPermission()) return@withContext emptyList()

            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.pkg)
                .setActivity(app.component)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )

            launcherApps.getShortcuts(query, me)
                .orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
                .take(MAX_SHORTCUTS)
                .map {
                    AppShortcut(
                        id = it.id,
                        pkg = it.getPackage(),
                        label = it.shortLabel?.toString() ?: it.id,
                    )
                }
        } catch (e: Exception) {
            Log.w("Shell", "no shortcuts for ${app.pkg}", e)
            emptyList()
        }
    }

    private fun hasShortcutHostPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching {
                LauncherApps::class.java
                    .getMethod("hasShortcutHostPermission")
                    .invoke(launcherApps) as Boolean
            }.getOrElse { false }

    fun startShortcut(shortcut: AppShortcut): Boolean =
        try {
            launcherApps.startShortcut(
                shortcut.pkg,
                shortcut.id,
                null,
                null,
                me,
            )
            true
        } catch (e: Exception) {
            Log.w("Shell", "couldn't start shortcut ${shortcut.id}", e)
            false
        }

    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
                trySend(Unit)
            }

            override fun onPackageAdded(packageName: String?, user: UserHandle?) {
                trySend(Unit)
            }

            override fun onPackageChanged(packageName: String?, user: UserHandle?) {
                trySend(Unit)
            }

            override fun onPackagesAvailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) {
                trySend(Unit)
            }

            override fun onPackagesUnavailable(
                packageNames: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) {
                trySend(Unit)
            }

            override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
                trySend(Unit)
            }

            override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
                trySend(Unit)
            }
        }

        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(callback) }
    }.conflate()
}

private fun toEntry(
    info: android.content.pm.LauncherActivityInfo,
    pm: android.content.pm.PackageManager,
    px: Int,
    densityDpi: Int,
): AppEntry? =
    runCatching {
        val cn = info.componentName
        AppEntry(
            label = runCatching { info.label.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: cn.packageName,
            pkg = cn.packageName,
            component = cn,
            bitmap = renderIcon(
                runCatching { info.getBadgedIcon(densityDpi) }
                    .getOrNull()
                    ?: pm.defaultActivityIcon,
                px,
            ),
            system = (info.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }.onFailure {
        Log.w("Shell", "skipping ${info.componentName}", it)
    }.getOrNull()

private const val MAX_SHORTCUTS = 4
private const val LARGEST_ICON_DP = 48

data class AppShortcut(
    val id: String,
    val pkg: String,
    val label: String,
)
