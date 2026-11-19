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
 * The time as a pair of huge, thin numbers: the hour in the foreground colour on top, the minutes below in the accent.
 * The hour is always two digits ("09") so the pair keeps the same shape all day.
 */
@Composable
fun StackedClock(time: String, accent: Color, modifier: Modifier = Modifier) {
    val hours = time.substringBefore(':').padStart(2, '0')
    val minutes = time.substringAfter(':')
    val style = ink(120.sp, weight = FontWeight.Light, tracking = (-5).sp).copy(
        lineHeight = 100.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Column(modifier) {
        Text(hours, style = style, maxLines = 1, softWrap = false)
        Text(minutes, style = style.copy(color = accent), maxLines = 1, softWrap = false)
    }
}
