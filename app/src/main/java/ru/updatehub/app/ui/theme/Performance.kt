package ru.updatehub.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
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
