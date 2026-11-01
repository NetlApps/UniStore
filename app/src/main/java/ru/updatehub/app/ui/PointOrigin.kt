package ru.updatehub.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize

/**
 * Keeps the screen-space point that the next navigation transition should
 * grow from. Every clickable element records its own centre, so a transition
 * can start at the gear, the add button or any other control without a
 * hard-coded list of screen fractions.
 */
@Stable
class PointOriginState {

    private var rootSize = IntSize.Zero
    private var lastCenter: Offset? = null

    var current: Offset = Offset(0.5f, 0.2f)
        private set

    fun updateRootSize(size: IntSize) {
        if (size.width > 0 && size.height > 0) rootSize = size
    }

    /**
     * The root is not measured when children first report their position, so
     * absolute pixels are stored and converted only when navigation starts.
     */
    fun activate(center: Offset) {
        lastCenter = center
        if (rootSize.width <= 0 || rootSize.height <= 0) return
        current = Offset(
            (center.x / rootSize.width).coerceIn(0f, 1f),
            (center.y / rootSize.height).coerceIn(0f, 1f)
        )
    }

    fun activateLast() {
        lastCenter?.let(::activate)
    }

    fun transformOrigin(): TransformOrigin = TransformOrigin(current.x, current.y)
}

val LocalPointOrigin = staticCompositionLocalOf { PointOriginState() }

/**
 * A modifier plus the click lambda that activates the recorded point. Apply
 * the modifier to the exact clickable element; the lambda performs the same
 * click it would have done before.
 */
@Stable
class PointOriginTrigger(
    val modifier: Modifier,
    val onClick: () -> Unit
)

@Composable
fun rememberPointOriginTrigger(onClick: () -> Unit): PointOriginTrigger {
    val origin = LocalPointOrigin.current
    val latestClick by rememberUpdatedState(onClick)
    var center by remember { mutableStateOf<Offset?>(null) }

    val modifier = remember(origin) {
        Modifier.onGloballyPositioned { coordinates ->
            center = coordinates.positionInRoot() +
                Offset(
                    coordinates.size.width / 2f,
                    coordinates.size.height / 2f
                )
        }
    }

    val trigger = remember(origin) {
        {
            center?.let(origin::activate)
            latestClick()
        }
    }

    return remember(modifier, trigger) { PointOriginTrigger(modifier, trigger) }
}
