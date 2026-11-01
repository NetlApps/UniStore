package ru.updatehub.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.updatehub.app.R
import ru.updatehub.app.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Plays one short branded launch sequence and opens the real interface from
 * the point where the logo arrow lands. The whole animation stays around one
 * second so it never feels like an extra loading screen.
 *
 * The content is composed underneath the overlay from the first frame. This
 * pre-warms the heavy update list while only the logo is visible, which makes
 * the circular reveal itself smooth even on a cold start.
 */
@Composable
fun LaunchRevealHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reduceMotion = LocalReduceMotion.current
    var finished by rememberSaveable { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    val progress = remember {
        Animatable(if (finished || reduceMotion) 1f else 0f)
    }

    LaunchedEffect(reduceMotion, finished) {
        if (finished) return@LaunchedEffect
        if (reduceMotion) {
            progress.snapTo(1f)
            finished = true
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = LaunchDurationMs,
                    easing = LinearEasing
                )
            )
        }
        // A tap can snap the timeline to the end. Watching the value instead
        // of the animation job means the host still finishes in that case.
        snapshotFlow { progress.value }.first { it >= 1f }
        finished = true
    }

    LaunchedEffect(skip) {
        if (skip) progress.snapTo(1f)
    }

    val t = progress.value
    val reveal = smoothstep(0.42f, 1f, t)
    val background = MaterialTheme.colorScheme.background
    val skipInteraction = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxSize()) {
        // The overlay sits below the interface: as the reveal circle grows,
        // the real screen covers the logo instead of cross-fading with it.
        if (!finished) {
            LaunchOverlay(progress = t)
        }

        // The content is composed once and stays alive across the whole
        // launch, so finishing the reveal never recreates the update list.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (finished) {
                        drawContent()
                        return@drawWithContent
                    }

                    val radius = hypot(
                        size.width * 0.5f,
                        size.height * 0.82f
                    ) * reveal
                    val center = Offset(
                        size.width * 0.5f,
                        size.height * LandingFraction
                    )
                    val circle = Path().apply {
                        addOval(Rect(center, radius))
                    }

                    clipPath(circle) {
                        this@drawWithContent.drawContent()
                    }

                    // A thin ring marks the edge of the reveal while the
                    // interface grows out of the arrow landing point.
                    val ring = (1f - reveal) * (1f - reveal) * 0.55f
                    if (ring > 0.01f) {
                        drawCircle(
                            color = Color(0xFF67E8F9).copy(alpha = ring),
                            radius = radius,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
            ) {
                content()
            }
        }

        // Shards fly above the interface that is already opening underneath,
        // so the U visibly breaks apart all the way to the screen edges.
        if (!finished) {
            ShatterBurst(progress = t)
        }

        // Touch is blocked until the reveal finishes, and a tap skips the
        // intro instead of activating an invisible card underneath it.
        if (!finished) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = skipInteraction,
                        indication = null,
                        onClick = { skip = true }
                    )
            )
        }
    }
}

@Composable
private fun LaunchOverlay(progress: Float) {
    val density = LocalDensity.current

    val appear = smoothstep(0f, 0.20f, progress)
    val shatterPulse = smoothstep(0.40f, 0.44f, progress) *
        (1f - smoothstep(0.44f, 0.58f, progress))
    val logoScale = (0.82f + 0.18f * easeOutBack(appear)) * (1f + 0.05f * shatterPulse)
    val logoAlpha = appear * (1f - smoothstep(0.40f, 0.46f, progress))
    val uAlpha = 1f - smoothstep(0.40f, 0.44f, progress)

    val fly = smoothstep(0.14f, 0.44f, progress)
    val arrowAlpha = smoothstep(0.02f, 0.08f, progress) *
        (1f - smoothstep(0.86f, 1f, fly))
    val arrowScale = 1f - 0.34f * fly
    val arrowRotation = -7f * fly

    val flip = smoothstep(0.18f, 0.38f, progress)
    val uRotation = 360f * easeInOutCubic(flip)

    val flash = smoothstep(0.38f, 0.64f, progress)
    val flashAlpha = (sin(flash * PI).toFloat()).coerceIn(0f, 1f) * 0.85f
    val flashScale = 0.25f + 2.1f * flash

    val wordAlpha = smoothstep(0.16f, 0.34f, progress) *
        (1f - smoothstep(0.42f, 0.50f, progress))

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF060A19),
                        Color(0xFF0B1230),
                        Color(0xFF071A26)
                    )
                )
            )
    ) {
        val landingY = maxHeight * LandingFraction
        val travelPx = with(density) { (maxHeight * (0.5f - LandingFraction)).toPx() }
        val tileSize = LaunchTileSize

        // Soft halo behind the mark.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(330.dp)
                .graphicsLayer { alpha = logoAlpha }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x334C7DFF),
                            Color(0x141F2A6B),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    scaleX = logoScale
                    scaleY = logoScale
                    alpha = logoAlpha
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(tileSize)
                    .clip(RoundedCornerShape(tileSize * 0.23f))
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                // Only the U turns. Keeping the tile still avoids the whole
                // icon disappearing edge-on in the middle of the spin.
                Image(
                    painter = painterResource(R.drawable.ic_mark_u),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = uAlpha
                            rotationY = uRotation
                            cameraDistance = 96f * density.density
                        },
                    contentScale = ContentScale.FillBounds
                )
            }

            // The arrow is a sibling of the tile, so it can leave the icon
            // without being clipped by the rounded corners.
            Image(
                painter = painterResource(R.drawable.ic_mark_arrow),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(tileSize)
                    .graphicsLayer {
                        alpha = arrowAlpha
                        scaleX = arrowScale
                        scaleY = arrowScale
                        rotationZ = arrowRotation
                        translationY = -travelPx * easeInCubic(fly)
                    },
                contentScale = ContentScale.FillBounds
            )
        }

        Text(
            text = "UniStore",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = Color(0xFFE7ECFF),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 104.dp)
                .graphicsLayer { alpha = wordAlpha }
        )

        // A tiny point at the landing spot, then a quick ring that hands the
        // reveal over to the interface growing from the same origin.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = landingY - 44.dp)
                .size(88.dp)
                .graphicsLayer {
                    alpha = flashAlpha
                    scaleX = flashScale
                    scaleY = flashScale
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFE0F7FF),
                            Color(0xCC67E8F9),
                            Color(0x336366F1),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )
    }
}

@Composable
private fun ShatterBurst(progress: Float) {
    val density = LocalDensity.current
    val tilePx = with(density) { LaunchTileSize.toPx() }
    val shards = remember(tilePx) { createShatterShards(tilePx) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val span = ShatterEnd - ShatterStart
        if (span <= 0f) return@Canvas
        val global = ((progress - ShatterStart) / span).coerceIn(0f, 1f)
        if (global <= 0f || global >= 1f) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val burst = smoothstep(0.015f, 0.09f, global) *
            (1f - smoothstep(0.10f, 0.48f, global))
        if (burst > 0.01f) {
            val coreRadius = tilePx * (0.30f + 1.05f * global)
            // A soft light core instead of a flat disc: the middle burns
            // white, the edges fall off into cyan and indigo.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF2FDFF).copy(alpha = 0.75f * burst),
                        Color(0xFF9BEAFF).copy(alpha = 0.45f * burst),
                        Color(0xFF4F7DFF).copy(alpha = 0.16f * burst),
                        Color.Transparent
                    ),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )
            drawCircle(
                color = Color(0xFFA5F3FC).copy(alpha = 0.70f * burst),
                radius = tilePx * (0.46f + 1.5f * global),
                center = center,
                style = Stroke(width = 2.2.dp.toPx())
            )
            drawCircle(
                color = Color(0xFFE0F7FF).copy(alpha = 0.55f * burst),
                radius = tilePx * (0.34f + 0.7f * global),
                center = center,
                style = Stroke(width = 1.1.dp.toPx())
            )
        }

        shards.forEach { shard ->
            val local = ((global - shard.delay) / (1f - shard.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach

            val start = center + Offset(
                shard.offset.x * tilePx,
                shard.offset.y * tilePx
            )
            val edgeDistance = distanceToEdge(start, shard.direction, size)
            val travel = edgeDistance * shard.reach
            val position = start + shard.direction * (travel * easeOutCubic(local))
            val alpha = smoothstep(0f, 0.05f, local) *
                (1f - smoothstep(0.72f, 1f, local)) * shard.alpha
            if (alpha <= 0.01f) return@forEach

            val scale = 1f - 0.24f * local
            val color = shard.color.copy(alpha = alpha)

            if (shard.shape != ShardShape.SPARK) {
                drawCircle(
                    color = shard.color.copy(alpha = alpha * 0.14f),
                    radius = shard.width * 1.7f,
                    center = position
                )
            }

            drawLine(
                color = color.copy(alpha = alpha * 0.38f),
                start = position - shard.direction * (travel * 0.11f),
                end = position,
                strokeWidth = shard.width * scale * 0.55f,
                cap = StrokeCap.Round
            )

            withTransform({
                translate(position.x, position.y)
                rotate(shard.rotation + shard.spin * local, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                when (shard.shape) {
                    ShardShape.TRIANGLE -> shard.triangle?.let { drawPath(it, color) }
                    ShardShape.SPARK -> drawCircle(color, radius = shard.width * 0.7f)
                    ShardShape.SHARD -> drawRoundRect(
                        color = color,
                        topLeft = Offset(-shard.width / 2f, -shard.height / 2f),
                        size = Size(shard.width, shard.height),
                        cornerRadius = CornerRadius(shard.width * 0.24f)
                    )
                }
            }
        }
    }
}

private enum class ShardShape {
    SHARD,
    TRIANGLE,
    SPARK
}

private data class ShatterShard(
    val offset: Offset,
    val direction: Offset,
    val reach: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val spin: Float,
    val color: Color,
    val alpha: Float,
    val delay: Float,
    val shape: ShardShape,
    val triangle: Path? = null
)

/**
 * Builds a new random shatter pattern for every launch. Directions are spread
 * around the full circle with a random jitter, so every start looks different
 * while the shards still cover the entire screen instead of clustering.
 */
private fun createShatterShards(tilePx: Float): List<ShatterShard> {
    val random = Random.Default
    val palette = listOf(
        Color(0xFFF7FEFF),
        Color(0xFFE0F7FF),
        Color(0xFFC7D2FE),
        Color(0xFFA5B4FC),
        Color(0xFF818CF8),
        Color(0xFF6366F1),
        Color(0xFF22D3EE),
        Color(0xFF67E8F9),
        Color(0xFFC084FC)
    )
    val count = 108

    return List(count) { index ->
        // Big crystal pieces keep the full circle evenly covered, while the
        // smaller dust picks unconstrained random angles. The result still
        // reaches every edge but no two launches form the same starburst.
        val big = index < 32
        val angle = if (big) {
            ((index + random.nextFloat()) / 32f) * (2f * PI.toFloat())
        } else {
            random.nextFloat() * (2f * PI.toFloat())
        }
        val direction = Offset(cos(angle), sin(angle))
        val width = tilePx * if (big) {
            0.042f + random.nextFloat() * 0.043f
        } else {
            0.014f + random.nextFloat() * 0.014f
        }
        val height = width * if (big) {
            1.2f + random.nextFloat() * 1.0f
        } else {
            1.4f + random.nextFloat() * 1.6f
        }
        val shape = when {
            big && random.nextFloat() < 0.65f -> ShardShape.TRIANGLE
            random.nextFloat() < 0.20f -> ShardShape.SPARK
            else -> ShardShape.SHARD
        }

        ShatterShard(
            offset = sampleUPoint(random),
            direction = direction,
            reach = 0.88f + random.nextFloat() * 0.22f,
            width = width,
            height = height,
            rotation = random.nextFloat() * 360f,
            spin = (random.nextFloat() * 2f - 1f) * 520f,
            color = palette[random.nextInt(palette.size)],
            alpha = if (big) 1f else 0.78f + random.nextFloat() * 0.22f,
            delay = random.nextFloat() * 0.18f,
            shape = shape,
            triangle = if (shape == ShardShape.TRIANGLE) {
                Path().apply {
                    moveTo(0f, -height / 2f)
                    lineTo(width / 2f, height / 2f)
                    lineTo(-width / 2f, height / 2f)
                    close()
                }
            } else {
                null
            }
        )
    }
}

/** Samples a point inside the U band so the shards start on the letter. */
private fun sampleUPoint(random: Random): Offset {
    val zone = random.nextFloat()
    return when {
        zone < 0.35f -> Offset(
            -0.20f + random.nextFloat() * 0.075f,
            -0.20f + random.nextFloat() * 0.22f
        )

        zone < 0.70f -> Offset(
            0.125f + random.nextFloat() * 0.075f,
            -0.20f + random.nextFloat() * 0.22f
        )

        else -> {
            val angle = (0.12f + random.nextFloat() * 0.76f) * PI.toFloat()
            val radius = 0.13f + random.nextFloat() * 0.07f
            Offset(cos(angle) * radius, sin(angle) * radius)
        }
    }
}

private fun distanceToEdge(start: Offset, direction: Offset, size: Size): Float {
    val tx = when {
        direction.x > 0.0001f -> (size.width - start.x) / direction.x
        direction.x < -0.0001f -> -start.x / direction.x
        else -> Float.MAX_VALUE
    }
    val ty = when {
        direction.y > 0.0001f -> (size.height - start.y) / direction.y
        direction.y < -0.0001f -> -start.y / direction.y
        else -> Float.MAX_VALUE
    }
    return min(tx, ty)
}

private fun easeOutCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val p = 1f - t
    return 1f - p * p * p
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge0 == edge1) return if (value < edge0) 0f else 1f
    val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun easeInCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t
}

private fun easeInOutCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return if (t < 0.5f) {
        4f * t * t * t
    } else {
        1f - (-2f * t + 2f).let { v -> v * v * v } / 2f
    }
}

private fun easeOutBack(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val p = t - 1f
    return 1f + c3 * p * p * p + c1 * p * p
}

private const val LaunchDurationMs = 1050
private const val LandingFraction = 0.18f
private const val ShatterStart = 0.40f
private const val ShatterEnd = 0.94f
private val LaunchTileSize = 112.dp
