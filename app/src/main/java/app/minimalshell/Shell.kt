package app.minimalshell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.asin

// The look of Shell comes from how a scallop shell is built: growth lines (arcs) and ridges (rays) fanning out from
// one corner, a scalloped edge, and the soft mother-of-pearl colours inside. This file holds those pieces.

// ---------- mood: the colours of the shell ----------

/** One soft patch of colour in the backdrop. [x] and [y] are fractions of the screen, [radius] a fraction of its width. */
class Blob(val x: Float, val y: Float, val radius: Float, val color: Color)

/**
 * A complete set of colours. [ink] is the foreground (text, lines), [bg] the surface everything sits on.
 * [light] says whether the surface is light, so the status bar icons can be flipped to stay readable.
 */
class Palette(val bg: Color, val ink: Color, val accent: Color, val light: Boolean, val blobs: List<Blob>)

object Palettes {
    // Night is the default: deep sea with the shell's nacre glowing faintly through it.
    val Night = Palette(
        bg = Color(0xFF0C1424), ink = Color(0xFFF1ECF6), accent = Color(0xFFFF8F9C), light = false,
        blobs = listOf(
            Blob(0.10f, 0.06f, 0.85f, Color(0x661E6F7A)),
            Blob(0.02f, 0.94f, 0.90f, Color(0x615B4B9A)),
            Blob(1.00f, 0.46f, 0.75f, Color(0x4D8A4A6A)),
            Blob(0.96f, 0.98f, 0.60f, Color(0x592C4C8C)),
        ),
    )
    val Dusk = Palette(
        bg = Color(0xFF231A33), ink = Color(0xFFF6E8EE), accent = Color(0xFFFF9A8B), light = false,
        blobs = listOf(
            Blob(0.10f, 0.06f, 0.85f, Color(0x666B4FA3)),
            Blob(0.02f, 0.94f, 0.90f, Color(0x59A04A6A)),
            Blob(1.00f, 0.46f, 0.75f, Color(0x47C46A5A)),
            Blob(0.96f, 0.98f, 0.60f, Color(0x593A3F8F)),
        ),
    )
    val Dawn = Palette(
        bg = Color(0xFFF4E4DE), ink = Color(0xFF2B2F4A), accent = Color(0xFFE4636B), light = true,
        blobs = listOf(
            Blob(0.10f, 0.06f, 0.85f, Color(0x99F6B7A8)),
            Blob(0.02f, 0.94f, 0.90f, Color(0x8CF9D9A8)),
            Blob(1.00f, 0.46f, 0.75f, Color(0x80BFE3E2)),
            Blob(0.96f, 0.98f, 0.60f, Color(0x73CFC4EE)),
        ),
    )
    val Day = Palette(
        bg = Color(0xFFEAF1F1), ink = Color(0xFF28324A), accent = Color(0xFFE4636B), light = true,
        blobs = listOf(
            Blob(0.10f, 0.06f, 0.85f, Color(0x99A9DCD6)),
            Blob(0.02f, 0.94f, 0.90f, Color(0x80F6C9CF)),
            Blob(1.00f, 0.46f, 0.75f, Color(0x80CFC4EE)),
            Blob(0.96f, 0.98f, 0.60f, Color(0x73F9DCC4)),
        ),
    )

    // minute of the day -> palette it eases towards
    private val keys = listOf(300 to Night, 390 to Dawn, 720 to Day, 1110 to Dusk, 1320 to Night)

    /** The palette for a time of day: night until 05:00, then easing through dawn, day and dusk, back to night by 22:00. */
    fun at(minuteOfDay: Int): Palette {
        val m = minuteOfDay.coerceIn(0, 1439)
        if (m < keys.first().first || m >= keys.last().first) return Night
        val i = keys.indexOfLast { it.first <= m }
        val (t0, a) = keys[i]
        val (t1, b) = keys[i + 1]
        return blend(a, b, (m - t0).toFloat() / (t1 - t0))
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun blend(a: Palette, b: Palette, f: Float) = Palette(
        bg = lerp(a.bg, b.bg, f),
        ink = lerp(a.ink, b.ink, f),
        accent = lerp(a.accent, b.accent, f),
        light = if (f < 0.5f) a.light else b.light,
        blobs = a.blobs.zip(b.blobs) { x, y ->
            Blob(mix(x.x, y.x, f), mix(x.y, y.y, f), mix(x.radius, y.radius, f), lerp(x.color, y.color, f))
        },
    )
}

/** The palette in use right now. Everything that reads Sand / Sea / Dim / Hair re-tints when it changes. */
object ShellMood {
    var palette by mutableStateOf(Palettes.Night)
        private set

    fun use(p: Palette) {
        palette = p
    }
}

// ---------- shapes ----------

/** A circle with a softly scalloped edge, like the rim of a shell. */
class ScallopShape(private val lobes: Int = 10, private val depth: Float = 0.06f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(size.width, size.height) / 2f
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val a = 2.0 * PI * i / steps
            // radius swings between r and r * (1 - 2 * depth), lobes times around the circle
            val rr = r * (1f - depth + depth * cos(lobes * a).toFloat())
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** An app icon on a frosted scalloped tile. [tile] is the outer size; the icon inside is about 60% of it. */
@Composable
fun ShellIcon(app: AppEntry, tile: Dp, modifier: Modifier = Modifier, label: String? = app.label) {
    Box(
        modifier
            .size(tile)
            .background(Sand.copy(alpha = 0.10f), ScallopShape())
            .border(1.dp, Sand.copy(alpha = 0.22f), ScallopShape()),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(app, tile * 0.62f, Modifier.clip(CircleShape), label = label)
    }
}

// ---------- backdrop ----------

/** Distance between the growth-line arcs. Home puts the pinned apps on the third one. */
val GROWTH_STEP = 80.dp

/**
 * The shell's own background: the mood's base colour with soft patches of nacre, and over it the growth lines,
 * arcs and ridges fanning out from the bottom-right corner. With [solid] off (the user's own wallpaper shows)
 * only the faint lines are drawn. It is drawn once and only redrawn when the mood changes.
 */
@Composable
fun ShellBackdrop(solid: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val p = ShellMood.palette
        val w = size.width
        val h = size.height
        if (solid) {
            drawRect(p.bg)
            p.blobs.forEach { drawCircle(it.color, radius = it.radius * w, center = Offset(it.x * w, it.y * h)) }
        }
        val lines = if (solid) Color.White.copy(alpha = if (p.light) 0.55f else 0.07f) else p.ink.copy(alpha = 0.12f)
        val corner = Offset(w, h)
        val stroke = Stroke(1.dp.toPx())
        for (k in 1..9) drawCircle(lines, radius = k * GROWTH_STEP.toPx(), center = corner, style = stroke)
        val reach = 2000.dp.toPx()
        for (deg in 15..75 step 15) {
            val t = Math.toRadians(deg.toDouble())
            drawLine(
                lines, corner,
                Offset(w - reach * cos(t).toFloat(), h - reach * sin(t).toFloat()),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

// ---------- the dock ----------

/** Outer size of a pinned app's scalloped tile. The icon inside is about 60% of it. */
val SHELL_TILE = 52.dp

/**
 * Lays the pinned apps out along the third growth line, fanning up from the bottom-right corner of the screen:
 * the bottom-left of the arc first, then curving up towards the right edge, which is where a right thumb sweeps.
 * [slots] places always exist; slots without an app are drawn as faint dashed rings, a hint that more can be pinned.
 * This must fill the whole screen (the arc is centred on its bottom-right corner, like the backdrop's lines).
 */
@Composable
fun ShellDock(
    keys: List<String>,
    slots: Int,
    entrance: Entrance,
    modifier: Modifier = Modifier,
    pin: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        val radius = GROWTH_STEP * 3
        // start high enough to stay clear of the navigation bar; neighbours sit one tile plus a little apart
        val start = asin(((bottomInset + 42.dp) / radius).coerceIn(0f, 0.9f).toDouble())
        val step = 2.0 * asin(((SHELL_TILE + 8.dp) / (2 * radius)).toDouble())
        fun cx(i: Int): Dp = w - radius * cos(start + step * i).toFloat()
        fun cy(i: Int): Dp = h - radius * sin(start + step * i).toFloat()

        Canvas(Modifier.matchParentSize()) {
            for (i in keys.size until slots) {
                drawCircle(
                    Sand.copy(alpha = 0.16f),
                    radius = (SHELL_TILE / 2).toPx(),
                    center = Offset(cx(i).toPx(), cy(i).toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 6.dp.toPx()))),
                )
            }
        }

        keys.forEachIndexed { i, k ->
            key(k) {
                Box(
                    Modifier
                        .offset(x = cx(i) - SHELL_TILE / 2, y = cy(i) - SHELL_TILE / 2)
                        .entrance(entrance, 240 + 70 * i),
                ) { pin(i) }
            }
        }
    }
}
