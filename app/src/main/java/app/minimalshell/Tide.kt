package app.minimalshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// "Pearls": the clock is the hero on the left; pinned apps are strung like pearls on a wavy thread down the right
// edge, where the thumb rests. The thread is also the day: it fills with colour from the top (midnight) down to
// the current time, with a small bead where you are. Everything is drawn once and then sits still. The only motion
// is a short entrance each time Home comes back on screen, so it costs no battery while you look at it.

// ---------- entrance ----------

private const val ENTRANCE_MS = 1000

/**
 * One short staggered entrance for the whole Home screen. A single animation drives every element; each element
 * picks its own slice of it with [phase], so adding an element costs nothing extra.
 */
@Stable
class Entrance(private val scope: CoroutineScope) {
    private val progress = Animatable(0f)
    private var job: Job? = null

    /** Plays the entrance from the start. Call it whenever Home becomes visible. */
    fun play() {
        job?.cancel()
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing))
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Safety net: if [play] never ran, show everything instead of leaving the screen blank. */
    fun showNow() {
        if (job == null) scope.launch { progress.snapTo(1f) }
    }

    /** 0..1 for an element that starts [delayMs] into the entrance and takes [durationMs] to arrive. */
    fun phase(delayMs: Int, durationMs: Int = 380): Float =
        FastOutSlowInEasing.transform(((progress.value * ENTRANCE_MS - delayMs) / durationMs).coerceIn(0f, 1f))
}

/** Fades the element in while it rises a few dp into place. The animation is read while drawing, so nothing recomposes. */
fun Modifier.entrance(entrance: Entrance, delayMs: Int, rise: Dp = 14.dp): Modifier =
    graphicsLayer {
        val p = entrance.phase(delayMs)
        alpha = p
        translationY = (1f - p) * rise.toPx()
    }

// ---------- the clock ----------

/**
 * The time as a pair of huge serif numbers: the hour in sand on top, the minutes below in the accent colour, in italic.
 * The hour is always two digits ("09") so the pair keeps the same shape all day.
 */
@Composable
fun StackedClock(time: String, accent: Color, modifier: Modifier = Modifier) {
    val hours = time.substringBefore(':').padStart(2, '0')
    val minutes = time.substringAfter(':')
    val style = ink(120.sp, weight = FontWeight.Light, serif = true, tracking = (-4).sp).copy(
        lineHeight = 100.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Column(modifier) {
        Text(hours, style = style, maxLines = 1, softWrap = false)
        Text(minutes, style = style.copy(color = accent, fontStyle = FontStyle.Italic), maxLines = 1, softWrap = false)
    }
}

// ---------- the thread dock ----------

/** Outer size of a pinned app's pearl (the icon inside is 14dp smaller). Change this one number to resize. */
val PEARL = 48.dp

private val PEARL_PITCH = 96.dp // distance between pearls; shrinks on short screens so they always fit
private val THREAD_AMP = 9.dp // how far the thread swings left and right
private val THREAD_WAVELENGTH = 114.dp

private fun threadX(cx: Float, amp: Float, wavelength: Float, y: Float): Float =
    cx + amp * sin(2.0 * PI * y / wavelength).toFloat()

/**
 * A wavy vertical thread that doubles as the day, with the pinned apps strung on it like pearls.
 *
 * Top of the thread = midnight, bottom = the next midnight. The part of the thread that has "passed" is drawn in the
 * accent colour and a bead marks now. [slots] pearls always fit; slots without an app are drawn as faint dashed
 * rings, which shows that more can be pinned. [keys] identifies the pinned apps (one per filled slot) and [pearl]
 * draws the one at index i.
 */
@Composable
fun ThreadDock(
    fraction: Float,
    accent: Color,
    entrance: Entrance,
    slots: Int,
    keys: List<String>,
    modifier: Modifier = Modifier,
    pearl: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val h = maxHeight
        val w = maxWidth
        val pitch = minOf(PEARL_PITCH, (h - PEARL) / (slots - 1))
        val top = (h - (pitch * (slots - 1) + PEARL)) / 2
        fun centerY(i: Int): Dp = top + PEARL / 2 + pitch * i
        fun swing(y: Dp): Dp = THREAD_AMP * sin(2.0 * PI * (y / THREAD_WAVELENGTH)).toFloat()

        // the thread itself, the hour ticks beside it, and the empty slots
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val amp = THREAD_AMP.toPx()
            val wl = THREAD_WAVELENGTH.toPx()
            val step = 4.dp.toPx()
            fun thread(y0: Float, y1: Float): Path {
                val p = Path()
                p.moveTo(threadX(cx, amp, wl, y0), y0)
                var y = y0 + step
                while (y < y1) {
                    p.lineTo(threadX(cx, amp, wl, y), y)
                    y += step
                }
                p.lineTo(threadX(cx, amp, wl, y1), y1)
                return p
            }

            val yNow = fraction.coerceIn(0f, 1f) * size.height
            val yFilled = yNow * entrance.phase(120, 620) // the colour runs down the thread as Home appears
            drawPath(
                thread(0f, size.height), Sand.copy(alpha = 0.3f),
                style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                thread(0f, yFilled), accent,
                style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // one tick per hour just left of the thread, longer at 0 / 6 / 12 / 18 / 24
            for (i in 0..24) {
                val y = i / 24f * size.height
                val long = i % 6 == 0
                val len = (if (long) 8.dp else 4.dp).toPx()
                val x1 = threadX(cx, amp, wl, y) - 6.dp.toPx()
                drawLine(
                    Sand.copy(alpha = if (y <= yNow) 0.5f else 0.22f),
                    Offset(x1 - len, y), Offset(x1, y),
                    strokeWidth = (if (long) 1.4f else 1f).dp.toPx(),
                )
            }

            // slots that nothing is pinned to yet
            for (i in keys.size until slots) {
                val y = centerY(i).toPx()
                drawCircle(
                    Sand.copy(alpha = 0.16f),
                    radius = (PEARL / 2).toPx(),
                    center = Offset(threadX(cx, amp, wl, y), y),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 6.dp.toPx()))),
                )
            }
        }

        // the pearls
        keys.forEachIndexed { i, k ->
            key(k) {
                val cy = centerY(i)
                val cx = w / 2 + swing(cy)
                Box(
                    Modifier
                        .offset(x = cx - PEARL / 2, y = cy - PEARL / 2)
                        .entrance(entrance, 240 + 70 * i),
                ) { pearl(i) }
            }
        }

        // the bead for "now", drawn last so a pearl can't hide it
        Canvas(Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val amp = THREAD_AMP.toPx()
            val wl = THREAD_WAVELENGTH.toPx()
            val p = entrance.phase(120, 620)
            val y = fraction.coerceIn(0f, 1f) * size.height * p
            val center = Offset(threadX(cx, amp, wl, y), y)
            drawCircle(accent.copy(alpha = 0.28f * p), radius = 8.dp.toPx(), center = center)
            drawCircle(accent.copy(alpha = p), radius = 3.6.dp.toPx(), center = center)
        }
    }
}

/**
 * A pinned app inside a thin ring, like a pearl. With [solid] the ring is filled with the deep-sea colour, so a
 * thread passing behind it is hidden, as if the pearl were strung on it.
 */
@Composable
fun Porthole(
    app: AppEntry,
    ring: Dp,
    modifier: Modifier = Modifier,
    label: String? = app.label,
    solid: Boolean = false,
) {
    Box(
        modifier
            .size(ring)
            .then(if (solid) Modifier.background(Sea, CircleShape) else Modifier)
            .background(Sand.copy(alpha = 0.06f), CircleShape)
            .border(1.dp, Sand.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(app, ring - 14.dp, Modifier.clip(CircleShape), label = label)
    }
}

// ---------- small marks ----------

/** The "open apps" handle: two small swells. */
@Composable
fun SwellHandle(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val amp = h * 0.22f
        for (k in 0..1) {
            val base = h * (0.3f + 0.4f * k)
            val p = Path()
            p.moveTo(0f, base)
            var x = 0f
            while (x <= w) {
                p.lineTo(x, base + amp * sin(2.0 * PI * 2.0 * x / w + k * 1.4).toFloat())
                x += 2f
            }
            drawPath(
                p, if (k == 0) accent else Sand.copy(alpha = 0.45f),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** A hairline that ripples gently, with a short accent stretch at the start. Used as the drawer's horizon. */
@Composable
fun TideLine(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val mid = size.height / 2f
        val amp = 3.dp.toPx()
        val wavelength = 44.dp.toPx()
        val step = 3.dp.toPx()
        fun y(x: Float): Float = mid + amp * sin(2.0 * PI * x / wavelength).toFloat()
        fun wave(x1: Float): Path {
            val p = Path()
            p.moveTo(0f, y(0f))
            var x = step
            while (x < x1) {
                p.lineTo(x, y(x))
                x += step
            }
            p.lineTo(x1, y(x1))
            return p
        }
        drawPath(wave(size.width), Sand.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()))
        drawPath(wave(30.dp.toPx()), accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
