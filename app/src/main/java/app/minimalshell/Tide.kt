package app.minimalshell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

// "Tide": the day drawn as a tide. The water fills up as the day goes by, a marker rides the wave at the current
// time, and a scale of hour ticks runs underneath, like a tide table. Everything here is drawn once and then sits
// still; the only motion is a single rise when Home comes back on screen, so it costs no battery while you look.

/**
 * The day as a wave. [fraction] (0..1) is how far through today we are: the water is filled up to that point and
 * the marker sits on the curve there. The wave rises into place each time Home appears.
 */
@Composable
fun TideWave(fraction: Float, accent: Color, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            settle.snapTo(0f)
            settle.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        }
        onPauseOrDispose { job.cancel() }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val rise = settle.value // read here, in the draw phase, so the animation only redraws
        val base = h * 0.40f
        val amp = h * 0.24f * rise
        val waterBottom = h * 0.72f
        // two tides a day, with a faint ripple on top so it doesn't look like a perfect sine
        fun y(x: Float): Float {
            val t = x / w
            val swell = cos(2.0 * PI * 2.0 * t - 0.6) + 0.25 * sin(2.0 * PI * 3.0 * t + 1.3)
            return base - amp * swell.toFloat()
        }

        val xNow = fraction.coerceIn(0f, 1f) * w
        val step = 3.dp.toPx()
        fun curve(x0: Float, x1: Float): Path {
            val p = Path()
            p.moveTo(x0, y(x0))
            var x = x0 + step
            while (x < x1) {
                p.lineTo(x, y(x))
                x += step
            }
            p.lineTo(x1, y(x1))
            return p
        }

        // the water that has "come in" so far today
        val water = Path()
        water.moveTo(0f, waterBottom)
        water.lineTo(0f, y(0f))
        var wx = step
        while (wx < xNow) {
            water.lineTo(wx, y(wx))
            wx += step
        }
        water.lineTo(xNow, y(xNow))
        water.lineTo(xNow, waterBottom)
        water.close()
        drawPath(water, accent.copy(alpha = 0.18f))

        drawPath(curve(xNow, w), Sand.copy(alpha = 0.4f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(
            curve(0f, xNow), accent,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // now
        val yNow = y(xNow)
        drawLine(Sand.copy(alpha = 0.25f), Offset(xNow, yNow), Offset(xNow, waterBottom), strokeWidth = 1.dp.toPx())
        drawCircle(accent.copy(alpha = 0.28f), radius = 10.dp.toPx(), center = Offset(xNow, yNow))
        drawCircle(accent, radius = 4.5.dp.toPx(), center = Offset(xNow, yNow))

        // the scale: one tick per hour, longer at 0 / 6 / 12 / 18 / 24, the current hour in the accent colour
        val hourNow = (fraction * 24f).toInt().coerceIn(0, 23)
        val tickTop = h * 0.82f
        for (i in 0..24) {
            val x = i / 24f * w
            val long = i % 6 == 0
            val color = when {
                i == hourNow -> accent
                x <= xNow -> Sand.copy(alpha = 0.6f)
                else -> Sand.copy(alpha = 0.25f)
            }
            drawLine(
                color,
                Offset(x, tickTop),
                Offset(x, tickTop + (if (long) 9.dp else 5.dp).toPx()),
                strokeWidth = (if (long) 1.5f else 1f).dp.toPx(),
            )
        }
    }
}

/** The numerals that sit under the wave's tick scale: 00, 06, 12, 18, 24. */
@Composable
fun TideNumerals(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        listOf(0, 6, 12, 18, 24).forEach { hr ->
            Text(
                "%02d".format(hr),
                style = ink(10.sp, Dim, mono = true),
                // bias runs -1 (left edge) .. 1 (right edge), so these land under their ticks
                modifier = Modifier.align(BiasAlignment(hr / 12f - 1f, 0f)),
            )
        }
    }
}

/** A pinned app inside a thin ring, like a porthole. */
@Composable
fun Porthole(app: AppEntry, ring: Dp, modifier: Modifier = Modifier, label: String? = app.label) {
    Box(
        modifier.size(ring).border(1.dp, Sand.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(app, ring - 14.dp, Modifier.clip(CircleShape), label = label)
    }
}

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
