package app.minimalshell

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * One launcher entry. Everything the UI and search need is computed once, here, off the main thread,
 * so scrolling and typing never redo that work. The icon is a ready-to-draw bitmap; the source Drawable is not kept.
 */
data class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val bitmap: ImageBitmap,
    val system: Boolean,
) {
    /**
     * Identity for pins, hidden apps and launch counts. It is the component ("pkg/Class"), not the package,
     * because one package can expose several launcher entries and each must be pinned/hidden on its own.
     */
    val key: String = component.flattenToString()

    // search data, precomputed once per load instead of on every keystroke
    val folded: String = label.fold()
    val words: List<String> = folded.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
    val initials: String = words.joinToString("") { it.take(1) }

    private val section: Char = run {
        // NFD splits "É" into "E" + a combining accent, so accented Latin labels land under their base letter
        val c = Normalizer.normalize(label.trim(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: '#'
        if (c in 'A'..'Z') c else '#'
    }

    /** Section letter for the drawer; anything that isn't A-Z goes under '#'. */
    fun letter(): Char = section
}

/** Shown instead of an icon that can't be rendered. */
private fun placeholderIcon(px: Int): Bitmap =
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawCircle(px / 2f, px / 2f, px * 0.34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF })
    }

/**
 * Draws the icon once at the size it is displayed (largest use is the 52dp dock icon at this screen's density),
 * then moves it to a GPU-only bitmap: it costs no Java heap and is already uploaded when the list first scrolls.
 * Every step falls back to something that works, so one odd icon can't break loading.
 */
fun renderIcon(d: Drawable, px: Int): ImageBitmap {
    val soft = try { d.toBitmap(px, px) } catch (_: Throwable) { placeholderIcon(px) }
    val gpu = try { soft.copy(Bitmap.Config.HARDWARE, false) } catch (_: Throwable) { null }
    // Don't recycle `soft`: toBitmap() may hand back the app's own cached bitmap, which isn't ours to free.
    return (gpu ?: soft).asImageBitmap()
}

// ---------- text helpers ----------

private val Marks = Regex("\\p{M}+")

/** Lowercase and strip accents, so "cafe" finds "Café". */
fun String.fold(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Marks, "").lowercase(Locale.ROOT)
