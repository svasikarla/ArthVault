package com.arthvault.ui.components

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * One motion language for the app.
 *
 * There was previously no motion at all — a grep for `animate*`, `Crossfade`,
 * `updateTransition`, `spring` and `tween` across the whole `ui/` package returned a
 * single unused import. Screens snapped between tabs, list rows popped in and out,
 * and the three cash figures substituted new values with nothing to say they'd
 * changed.
 *
 * Durations follow the reference: microfeedback ~100ms, UI transitions 150–400ms,
 * nothing beyond 500ms.
 */
object Motion {
    const val QUICK = 120
    const val STANDARD = 260
    const val EMPHASISED = 420

    /** For values that should feel physical — bars filling, arcs sweeping. */
    fun <T> gentleSpring(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * Whether the user has asked the system to stop animating.
 *
 * The "Remove animations" accessibility toggle writes `ANIMATOR_DURATION_SCALE = 0`.
 * Honouring it is not optional: motion that a user has explicitly declined is motion
 * that costs them, and the reference is unambiguous about respecting it.
 */
@Composable
@ReadOnlyComposable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}

/**
 * Animates a value unless motion is reduced, in which case it snaps.
 *
 * Used for the figures that change under the user — the spend bar, the donut sweep,
 * the safe-to-spend number after a period change. Seeing the delta is the point; a
 * substituted number reads as a different screen rather than a changed one.
 */
@Composable
fun animateMetric(
    target: Float,
    durationMillis: Int = Motion.EMPHASISED,
    label: String = "metric",
): State<Float> {
    val reduced = reduceMotion()
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) tween(0) else tween(durationMillis),
        label = label,
    )
}
