package app.minimalshell

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

// The first time this opens, everything in the old SharedPreferences file "shell" (same key names and types)
// is copied in and that file is removed, so existing pins, hidden apps and settings carry over.
private val Context.shellStore: DataStore<Preferences> by preferencesDataStore(
    name = "shell",
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, "shell")) },
)

private val PINNED = stringPreferencesKey("pinned")
private val HIDDEN = stringSetPreferencesKey("hidden")
private val COUNTS = stringPreferencesKey("counts")
private val ACCENT = intPreferencesKey("accent")
private val AUTO_KEYBOARD = booleanPreferencesKey("autoKeyboard")
private val FREQUENT = booleanPreferencesKey("frequent")
private val SEARCH_ENGINE = stringPreferencesKey("searchEngine")
private val MOOD_AUTO = booleanPreferencesKey("moodAuto")
private val SHELL_BACKDROP = booleanPreferencesKey("shellBackdrop")
private val INSTANT_LAUNCH = booleanPreferencesKey("instantLaunch")
private val HIDE_HIDDEN_IN_SEARCH = booleanPreferencesKey("hiddenInSearch")

/** Reads and writes the launcher's persisted settings. Both directions run off the main thread. */
class SettingsStore(context: Context) {
    private val ds = context.applicationContext.shellStore

    data class Snapshot(
        val pinned: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val counts: Map<String, Int> = emptyMap(),
        /** ACCENT_MOOD = the current mood's accent (default), 0 = the wallpaper's, anything else = a chosen colour. */
        val accent: Int = ACCENT_MOOD,
        val autoKeyboard: Boolean = false,
        val showFrequent: Boolean = true,
        val searchEngine: String = "duckduckgo",
        val instantLaunch: Boolean = false,
        val hideHiddenInSearch: Boolean = false,
        /** False (default): always the dark night palette. True: colours drift through dawn, day, dusk and night. */
        val moodFollowsDay: Boolean = false,
        /** True (default): the shell's own backdrop. False: the user's wallpaper. */
        val shellBackdrop: Boolean = true,
    )

    /** Never throws (except cancellation): unreadable storage just means defaults. */
    suspend fun load(): Snapshot {
        val p = try {
            ds.data.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Shell", "settings unreadable, using defaults", e)
            emptyPreferences()
        }
        return Snapshot(
            pinned = p[PINNED].orEmpty().split(",").filter { it.isNotBlank() },
            hidden = p[HIDDEN].orEmpty(),
            counts = parseCounts(p[COUNTS].orEmpty()),
            accent = p[ACCENT] ?: ACCENT_MOOD,
            autoKeyboard = p[AUTO_KEYBOARD] ?: false,
            showFrequent = p[FREQUENT] ?: true,
            searchEngine = p[SEARCH_ENGINE] ?: "duckduckgo",
            instantLaunch = p[INSTANT_LAUNCH] ?: false,
            hideHiddenInSearch = p[HIDE_HIDDEN_IN_SEARCH] ?: false,
            moodFollowsDay = p[MOOD_AUTO] ?: false,
            shellBackdrop = p[SHELL_BACKDROP] ?: true,
        )
    }

    suspend fun savePinned(l: List<String>) = write { it[PINNED] = l.joinToString(",") }
    suspend fun saveHidden(s: Set<String>) = write { it[HIDDEN] = s }
    suspend fun saveCounts(m: Map<String, Int>) =
        write { it[COUNTS] = m.entries.joinToString(",") { e -> "${e.key}=${e.value}" } }
    suspend fun saveAccent(v: Int) = write { it[ACCENT] = v }
    suspend fun saveAutoKeyboard(on: Boolean) = write { it[AUTO_KEYBOARD] = on }
    suspend fun saveShowFrequent(on: Boolean) = write { it[FREQUENT] = on }
    suspend fun saveSearchEngine(id: String) = write { it[SEARCH_ENGINE] = id }
    suspend fun saveInstantLaunch(on: Boolean) = write { it[INSTANT_LAUNCH] = on }
    suspend fun saveHideHiddenInSearch(on: Boolean) = write { it[HIDE_HIDDEN_IN_SEARCH] = on }
    suspend fun saveMoodFollowsDay(on: Boolean) = write { it[MOOD_AUTO] = on }
    suspend fun saveShellBackdrop(on: Boolean) = write { it[SHELL_BACKDROP] = on }

    private suspend fun write(change: (MutablePreferences) -> Unit) {
        try {
            ds.edit { change(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Shell", "couldn't save settings", e)
        }
    }

    private fun parseCounts(raw: String): Map<String, Int> =
        raw.split(",")
            .mapNotNull { e ->
                val p = e.split("=")
                if (p.size == 2) p[1].toIntOrNull()?.let { p[0] to it } else null
            }
            .toMap()
}
