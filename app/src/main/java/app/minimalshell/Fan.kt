package app.minimalshell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// The shell is Shell's one recurring shape: seven ridges fanning out from a single point, as in the app icon.
// It shows the week on Home, marks the "open apps" handle, and the dock rests on the same curve.

private const val RIDGES = 7

/**
 * Seven ridges fanning upward from a point at the bottom centre.
 * [today] (0..6) is drawn in the accent colour; with [showPast] the days before it are drawn brighter than the days
 * still to come, so the fan also reads as how far through the week you are.
 */
@Composable
fun ShellFan(
    today: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    showPast: Boolean = true,
    ridgeWidth: Dp = 3.dp,
) {
    Canvas(modifier) {
        val stroke = ridgeWidth.toPx()
        val cx = size.width / 2f
        val cy = size.height - stroke / 2f
        val outer = size.height - stroke
        val inner = outer * 0.3f
        for (i in 0 until RIDGES) {
            // 200..340 degrees: screen y points down, so these angles open upward
            val a = Math.toRadians(200.0 + i * (140.0 / (RIDGES - 1)))
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            val color = when {
                i == today -> accent
                showPast && i < today -> Sand.copy(alpha = 0.6f)
                else -> Sand.copy(alpha = 0.25f)
            }
            drawLine(
                color = color,
                start = Offset(cx + inner * dx, cy + inner * dy),
                end = Offset(cx + outer * dx, cy + outer * dy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val ARC_RADIUS = 150.dp
private const val ARC_STEP_DEG = 24.0

/**
 * Lays its children out along an upward arc: the middle one highest, the outer ones lower, like the ridges of the shell.
 * Spacing between neighbours stays the same whatever the number of children, so a dock with three icons is just
 * a shorter arc, not stretched.
 */
@Composable
fun ArcDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val n = items.size
        val radius = ARC_RADIUS.toPx()
        val step = Math.toRadians(ARC_STEP_DEG)
        val outermost = step * (n - 1) / 2.0
        val depth = if (n > 1) radius * (1 - cos(outermost)).toFloat() else 0f
        val itemHeight = items.maxOfOrNull { it.height } ?: 0
        val width = constraints.maxWidth
        layout(width, (itemHeight + depth).roundToInt()) {
            items.forEachIndexed { i, p ->
                val a = (i - (n - 1) / 2.0) * step
                val centerX = width / 2f + (radius * sin(a)).toFloat()
                val top = radius * (1 - cos(a)).toFloat()
                p.place((centerX - p.width / 2f).roundToInt(), top.roundToInt())
            }
        }
    }
}
