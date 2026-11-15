package app.minimalshell

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

const val MAX_PINNED = 5

/**
 * Everything the UI observes: the app list, pins, hidden apps, launch counts and settings.
 * It outlives the Activity, so a recreated Activity doesn't reload apps or flash an empty dock.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ShellViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx: Application = application
    private val repo = AppRepository(application)

    /** The installed apps. Null until the first load finishes, which is different from "no apps". */
    var apps by mutableStateOf<List<AppEntry>?>(null)
        private set

    private val store = SettingsStore(application)

    /** False until the saved settings have been read; the UI waits for it so nothing flashes default values. */
    var ready by mutableStateOf(false)
        private set

    var pinned by mutableStateOf<List<String>>(emptyList())
        private set
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var accentOverride by mutableIntStateOf(0)
        private set
    var showFrequent by mutableStateOf(true)
        private set
    var autoKeyboard by mutableStateOf(false)
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
        persist(pinned) { savePinned(it) }
    }

    fun toggleHidden(key: String) {
        val nowHidden = key !in hidden
        hidden = if (nowHidden) hidden + key else hidden - key
        persist(hidden) { saveHidden(it) }
        if (nowHidden && key in pinned) {
            pinned = pinned - key
            persist(pinned) { savePinned(it) }
        }
    }

    fun launch(app: AppEntry) {
        if (!launchApp(ctx, app)) return
        counts = counts + (app.key to ((counts[app.key] ?: 0) + 1))
        persist(counts) { saveCounts(it) }
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

        if (newPinned != pinned) { pinned = newPinned; persist(newPinned) { savePinned(it) } }
        if (newHidden != hidden) { hidden = newHidden; persist(newHidden) { saveHidden(it) } }
        if (newCounts != counts) { counts = newCounts; persist(newCounts) { saveCounts(it) } }
    }

    fun setAccent(argb: Int) {
        accentOverride = argb
        persist(argb) { saveAccent(it) }
    }

    fun updateShowFrequent(on: Boolean) {
        showFrequent = on
        persist(on) { saveShowFrequent(it) }
    }

    fun updateAutoKeyboard(on: Boolean) {
        autoKeyboard = on
        persist(on) { saveAutoKeyboard(it) }
    }

    /** Captures [value] now and saves it in the background. Writes are applied in the order they were made. */
    private fun <T> persist(value: T, action: suspend SettingsStore.(T) -> Unit) {
        viewModelScope.launch { store.action(value) }
    }

    private fun applySettings(s: SettingsStore.Snapshot) {
        pinned = s.pinned
        hidden = s.hidden
        counts = s.counts
        accentOverride = s.accent
        autoKeyboard = s.autoKeyboard
        showFrequent = s.showFrequent
    }

    private fun applyApps(list: List<AppEntry>) {
        apps = list
        sync(list) // migrate old keys, drop uninstalled apps from pins / hidden / counts
    }

    init {
        val settingsLoaded = viewModelScope.async {
            applySettings(store.load())
            ready = true
        }
        viewModelScope.launch {
            // first load immediately, then again after each burst of install/remove/update events
            merge(flowOf(Unit), repo.changes().debounce(400))
                .mapLatest { // a newer event cancels a load that is still running, so a stale list can't win
                    try {
                        repo.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("Shell", "app load failed", e)
                        null
                    }
                }
                .flowOn(Dispatchers.Default)
                .collect { list ->
                    settingsLoaded.await() // sync() prunes pins, so they must be loaded first
                    if (list != null) {
                        applyApps(list)
                    } else if (apps == null) {
                        apps = emptyList()
                    }
                }
        }
    }
}

fun frequentApps(visible: List<AppEntry>, s: ShellViewModel, n: Int = 4): List<AppEntry> =
    visible.filter { (s.counts[it.key] ?: 0) > 0 }
        .sortedByDescending { s.counts[it.key] ?: 0 }
        .take(n)
