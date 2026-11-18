package app.minimalshell

import android.provider.Settings
import java.util.Locale

// Everything the drawer's search box can do besides finding apps: jump to a system settings screen ("wifi"),
// run a bang ("y cats" = search YouTube), and the choice of web search engine.

// ---------- system settings screens ----------

/** A system settings screen that search can jump to. */
class SettingsEntry(val label: String, keywords: String, val action: String) {
    // folded once, here, instead of on every keystroke
    internal val foldedLabel = label.fold()
    internal val labelWords = foldedLabel.split(' ', '-').filter { it.isNotEmpty() }
    internal val keywordWords = keywords.fold().split(' ').filter { it.isNotEmpty() }
}

object SystemSettings {
    // Only screens that exist on every Android from 8.1 up. A phone maker that lacks one just shows a short message.
    private val all = listOf(
        SettingsEntry("Wi-Fi", "wifi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
        SettingsEntry("Bluetooth", "bluetooth pair devices headphones", Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingsEntry("Display", "display screen brightness dark theme font size", Settings.ACTION_DISPLAY_SETTINGS),
        SettingsEntry("Sound", "sound volume ringtone vibration", Settings.ACTION_SOUND_SETTINGS),
        SettingsEntry("Battery", "battery saver power", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        SettingsEntry("Location", "location gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        SettingsEntry("Airplane mode", "airplane flight", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        SettingsEntry("Network", "network mobile data cellular sim hotspot roaming", Settings.ACTION_WIRELESS_SETTINGS),
        SettingsEntry("Storage", "storage space memory", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        SettingsEntry("Apps", "apps applications manage uninstall", Settings.ACTION_APPLICATION_SETTINGS),
        SettingsEntry("Default apps", "default apps home browser", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        SettingsEntry("Accessibility", "accessibility talkback", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        SettingsEntry("Date and time", "date time clock timezone", Settings.ACTION_DATE_SETTINGS),
        SettingsEntry("Language", "language keyboard locale", Settings.ACTION_LOCALE_SETTINGS),
        SettingsEntry("Security", "security lock screen password fingerprint", Settings.ACTION_SECURITY_SETTINGS),
        SettingsEntry("Privacy", "privacy permissions", Settings.ACTION_PRIVACY_SETTINGS),
        SettingsEntry("NFC", "nfc tap pay", "android.settings.NFC_SETTINGS"),
        SettingsEntry("Developer options", "developer options debugging usb", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    )

    /** Up to three screens matching [folded] (the query, already lowercased and accent-stripped), best match first. */
    fun match(folded: String): List<SettingsEntry> {
        if (folded.length < 2) return emptyList() // one letter would match half the list
        return all
            .mapNotNull { e ->
                val rank = when {
                    e.foldedLabel.startsWith(folded) -> 0
                    e.labelWords.any { it.startsWith(folded) } -> 1
                    e.keywordWords.any { it.startsWith(folded) } -> 2
                    e.foldedLabel.contains(folded) -> 3
                    else -> -1
                }
                if (rank >= 0) e to rank else null
            }
            .sortedBy { it.second }
            .take(3)
            .map { it.first }
    }
}

// ---------- bangs ----------

/** A search prefix: "y cats" searches YouTube for "cats". [url] has %s where the search text goes. */
class SearchBang(val key: String, val name: String, val url: String)

object SearchBangs {
    private val all = listOf(
        SearchBang("y", "YouTube", "https://www.youtube.com/results?search_query=%s"),
        SearchBang("g", "Google", "https://www.google.com/search?q=%s"),
        SearchBang("d", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        SearchBang("w", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s"),
        SearchBang("m", "Maps", "https://www.google.com/maps/search/%s"),
        SearchBang("gh", "GitHub", "https://github.com/search?q=%s"),
        SearchBang("r", "Reddit", "https://www.reddit.com/search/?q=%s"),
        SearchBang("t", "Translate", "https://translate.google.com/?text=%s"),
        SearchBang("a", "Amazon", "https://www.amazon.com/s?k=%s"),
    )

    /** "y cats" -> (YouTube, "cats"). Needs a known prefix, a space, and something after it; otherwise null. */
    fun parse(q: String): Pair<SearchBang, String>? {
        val t = q.trimStart()
        val space = t.indexOf(' ')
        if (space <= 0) return null
        val rest = t.substring(space + 1).trim()
        if (rest.isEmpty()) return null
        val key = t.substring(0, space).lowercase(Locale.ROOT)
        return all.firstOrNull { it.key == key }?.let { it to rest }
    }
}

// ---------- web search engines ----------

class SearchEngine(val id: String, val name: String, val url: String)

object SearchEngines {
    val all = listOf(
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
        SearchEngine("brave", "Brave", "https://search.brave.com/search?q=%s"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s"),
    )

    /** An unknown id (say, from a newer backup) falls back to the first engine instead of failing. */
    fun byId(id: String): SearchEngine = all.firstOrNull { it.id == id } ?: all.first()

    fun next(id: String): SearchEngine {
        val i = all.indexOfFirst { it.id == id }
        return all[(i + 1) % all.size]
    }
}
