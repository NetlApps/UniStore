package ru.updatehub.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color

/**
 * True when the app should avoid continuous or expensive animations. It is
 * controlled by the "Слабое устройство" switch in Settings.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Color transitions that become instant in weak-device mode, so switching a
 * chip, filter or theme does not animate the whole screen.
 */
@Composable
fun rememberAnimatedColor(
    targetValue: Color,
    animationSpec: AnimationSpec<Color> = tween(260),
    label: String
): State<Color> {
    val reduceMotion = LocalReduceMotion.current
    return animateColorAsState(
        targetValue = targetValue,
        animationSpec = if (reduceMotion) snap() else animationSpec,
        label = label
    )
}

/**
 * A linear 0..1 loop advanced by the frame clock but published at [fps]
 * instead of every display frame.
 *
 * The decorative backgrounds drift slowly: their shift over eleven seconds is
 * a soft gradient, so a lower frame rate is invisible. Skipping frames saves a
 * full-screen redraw on every other vsync (on a 60 Hz screen) for as long as a
 * screen stays open. The value is a [State] on purpose: the canvas reads it
 * inside its draw lambda, which invalidates only the draw phase and does not
 * recompose the surrounding layout.
 *
 * The frame clock is paused while the app is in the background, so no work is
 * done off-screen.
 */
@Composable
fun rememberCappedLoop(durationMillis: Int, fps: Int = 30): State<Float> {
    val progress = remember(durationMillis) { mutableFloatStateOf(0f) }
    LaunchedEffect(durationMillis, fps) {
        val stepNanos = 1_000_000_000L / fps.coerceAtLeast(1)
        var startedAt = -1L
        var publishedAt = 0L
        while (true) {
            withFrameNanos { now ->
                if (startedAt < 0L) {
                    startedAt = now
                    publishedAt = now
                }
                if (now - publishedAt >= stepNanos) {
                    publishedAt = now
                    val elapsedMs = (now - startedAt) / 1_000_000L
                    progress.floatValue =
                        (elapsedMs % durationMillis).toFloat() / durationMillis
                }
            }
        }
    }
    return progress
}
