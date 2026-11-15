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

/** Reads and writes the launcher's persisted settings. Both directions run off the main thread. */
class SettingsStore(context: Context) {
    private val ds = context.applicationContext.shellStore

    data class Snapshot(
        val pinned: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val counts: Map<String, Int> = emptyMap(),
        /** 0 means "use the wallpaper". */
        val accent: Int = 0,
        val autoKeyboard: Boolean = false,
        val showFrequent: Boolean = true,
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
            accent = p[ACCENT] ?: 0,
            autoKeyboard = p[AUTO_KEYBOARD] ?: false,
            showFrequent = p[FREQUENT] ?: true,
        )
    }

    suspend fun savePinned(l: List<String>) = write { it[PINNED] = l.joinToString(",") }
    suspend fun saveHidden(s: Set<String>) = write { it[HIDDEN] = s }
    suspend fun saveCounts(m: Map<String, Int>) =
        write { it[COUNTS] = m.entries.joinToString(",") { e -> "${e.key}=${e.value}" } }
    suspend fun saveAccent(v: Int) = write { it[ACCENT] = v }
    suspend fun saveAutoKeyboard(on: Boolean) = write { it[AUTO_KEYBOARD] = on }
    suspend fun saveShowFrequent(on: Boolean) = write { it[FREQUENT] = on }

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
