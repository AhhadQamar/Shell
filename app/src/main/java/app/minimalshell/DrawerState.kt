package app.minimalshell

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How open the app drawer is, as ONE number: 0 = closed, 1 = open.
 *
 * The finger drives it directly while dragging, so the panel follows 1:1 and can be caught mid-way.
 * On release a spring takes over, starting from the finger's own speed, so a flick feels continuous.
 * Because the drawer is just translated by [progress], animating it never recomposes anything.
 */
@Stable
class DrawerState(private val scope: CoroutineScope) {

    /** 0..1. Read it inside graphicsLayer / draw lambdas only, so animating doesn't trigger recomposition. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Where the drawer is heading: true while it is open or opening. */
    var wantsOpen by mutableStateOf(false)
        private set

    /** True whenever any part of the drawer is on screen. Flips only at the two ends, so reading it is cheap. */
    val showing: Boolean by derivedStateOf { progress > 0f }

    /** Distance in px the panel travels between closed and open; the Drawer reports it once it is measured. */
    var travelPx: Float = 0f

    private var job: Job? = null
    private var dragging = false
    private var startedOpen = false

    fun open() = animateTo(1f, 0f)

    fun close() = animateTo(0f, 0f)

    /** Closes with no animation. Used when an app is launching, so nothing animates underneath the launch. */
    fun snapClosed() {
        job?.cancel()
        dragging = false
        wantsOpen = false
        progress = 0f
    }

    /**
     * The finger moved by [dyPx] (positive = down = closing, negative = up = opening).
     * Returns how much of that movement the drawer used, in the same sign, so callers can pass the rest on.
     */
    fun dragBy(dyPx: Float): Float {
        if (travelPx <= 0f) return 0f
        job?.cancel()
        if (!dragging) {
            dragging = true
            startedOpen = wantsOpen
        }
        val before = progress
        progress = (before - dyPx / travelPx).coerceIn(0f, 1f)
        return (before - progress) * travelPx
    }

    /** The finger lifted at [velocityPx] px/s (positive = moving down). Decides open or closed and animates there. */
    fun settle(velocityPx: Float) {
        if (!dragging) return
        dragging = false
        val v = -velocityPx / travelPx // panel-heights per second; positive = opening
        val open = when {
            v > FLICK -> true
            v < -FLICK -> false
            // a slow release goes whichever way you were already pulling: a little is enough to commit
            startedOpen -> progress > 0.7f
            else -> progress > 0.3f
        }
        animateTo(if (open) 1f else 0f, v)
    }

    private fun animateTo(target: Float, velocity: Float) {
        wantsOpen = target == 1f
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f),
            ) { value, _ -> progress = value.coerceIn(0f, 1f) }
        }
    }

    private companion object {
        const val FLICK = 0.4f
    }
}

/** Lets a menu somewhere inside the drawer (or on Home) close it after launching something. Null outside Shell's UI. */
val LocalDrawerState = staticCompositionLocalOf<DrawerState?> { null }
