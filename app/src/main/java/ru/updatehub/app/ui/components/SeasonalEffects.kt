package ru.updatehub.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.updatehub.app.R
import ru.updatehub.app.data.SeasonalEffect
import ru.updatehub.app.ui.theme.LocalReduceMotion
import ru.updatehub.app.ui.theme.rememberCappedLoop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The seasonal effect chosen for the current date or manually in Settings. */
val LocalSeasonalEffect = staticCompositionLocalOf { SeasonalEffect.NONE }

private val autumnLeafColors = listOf(
    Color(0xFFE07A2F),
    Color(0xFFC25E2E),
    Color(0xFFE9B44C),
    Color(0xFF9C5A2E)
)

private val garlandBulbColors = listOf(
    Color(0xFFFF5A5F),
    Color(0xFFFFC857),
    Color(0xFF4CD964),
    Color(0xFF5AA9FF),
    Color(0xFFFF7AD9)
)

private data class SeasonalParticle(
    val x: Float,
    val phase: Float,
    val speed: Float,
    /** Size in dp for snowflakes, in raw pixels for the autumn leaves. */
    val size: Float,
    val drift: Float,
    val spin: Float,
    val tone: Int,
    val type: Int
)

private fun particlesFor(effect: SeasonalEffect, scale: Float = 1f): List<SeasonalParticle> {
    if (effect == SeasonalEffect.NONE || effect == SeasonalEffect.HALLOWEEN) {
        return emptyList()
    }
    val random = Random(effect.ordinal * 104729 + 31)
    // Rarer but noticeably larger snowflakes look crisp thanks to the PNG art.
    val count = (when (effect) {
        SeasonalEffect.WINTER -> 17
        SeasonalEffect.NEW_YEAR -> 20
        SeasonalEffect.AUTUMN -> 30
        else -> 24
    } * scale).roundToInt().coerceAtLeast(4)
    return List(count) {
        SeasonalParticle(
            x = random.nextFloat(),
            phase = random.nextFloat(),
            speed = when (effect) {
                SeasonalEffect.WINTER -> 0.32f + random.nextFloat() * 0.5f
                SeasonalEffect.NEW_YEAR -> 0.34f + random.nextFloat() * 0.45f
                else -> 0.55f + random.nextFloat() * 0.75f
            },
            size = when (effect) {
                SeasonalEffect.WINTER -> 18f + random.nextFloat() * 30f
                SeasonalEffect.NEW_YEAR -> 11f + random.nextFloat() * 19f
                else -> 2.5f + random.nextFloat() * 5.5f
            },
            drift = random.nextFloat() * 2f - 1f,
            spin = random.nextFloat() * 360f,
            tone = random.nextInt(5),
            type = random.nextInt(5)
        )
    }
}

/**
 * Lightweight seasonal decoration drawn over [AnimatedGlowBackground]. Snow
 * uses five tiny PNG snowflakes, Halloween decorates the screen edges and New
 * Year adds garlands plus a Santa fly-by every 15-30 seconds.
 */
@Composable
fun SeasonalEffectOverlay(modifier: Modifier = Modifier) {
    val effect = LocalSeasonalEffect.current
    if (effect == SeasonalEffect.NONE) return

    val reduceMotion = LocalReduceMotion.current
    val particles = remember(effect, reduceMotion) {
        particlesFor(effect, if (reduceMotion) 0.45f else 1f)
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // On weak devices the decoration stays visible, but it is drawn once at a
    // fixed moment instead of repainting the whole canvas every frame. On
    // regular devices the slow drift is published at 30 fps: the movement is
    // far too gentle for the skipped frames to be visible, while every other
    // vsync no longer repaints a full-screen canvas.
    val loop = if (reduceMotion) {
        null
    } else {
        rememberCappedLoop(
            durationMillis = when (effect) {
                SeasonalEffect.AUTUMN -> 16_000
                SeasonalEffect.WINTER -> 15_000
                SeasonalEffect.NEW_YEAR -> 12_000
                SeasonalEffect.HALLOWEEN -> 9_000
                SeasonalEffect.NONE -> 12_000
            }
        )
    }

    // Snowflake bitmaps are only needed by the winter scenes; decoding five
    // PNGs for autumn or Halloween delayed the first frame for nothing.
    val snowflakes = if (
        effect == SeasonalEffect.WINTER || effect == SeasonalEffect.NEW_YEAR
    ) {
        listOf(
            ImageBitmap.imageResource(R.drawable.snowflake_1),
            ImageBitmap.imageResource(R.drawable.snowflake_2),
            ImageBitmap.imageResource(R.drawable.snowflake_3),
            ImageBitmap.imageResource(R.drawable.snowflake_4),
            ImageBitmap.imageResource(R.drawable.snowflake_5)
        )
    } else {
        emptyList()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val progress = loop?.value ?: 0.4f
        when (effect) {
            SeasonalEffect.WINTER -> drawWinter(particles, progress, dark, snowflakes)
            SeasonalEffect.AUTUMN -> drawAutumn(particles, progress)
            SeasonalEffect.HALLOWEEN -> drawHalloween(progress, dark)
            SeasonalEffect.NEW_YEAR -> drawNewYear(
                particles = particles,
                progress = progress,
                dark = dark,
                snowflakes = snowflakes
            )

            SeasonalEffect.NONE -> Unit
        }
    }
}

/**
 * Santa is drawn above the app content so the short fly-by is actually
 * visible instead of hiding behind cards. The canvas never consumes touches.
 */
@Composable
fun SeasonalForegroundOverlay(modifier: Modifier = Modifier) {
    val effect = LocalSeasonalEffect.current
    if (effect != SeasonalEffect.NEW_YEAR || LocalReduceMotion.current) return

    val santa = ImageBitmap.imageResource(R.drawable.santa_sleigh)
    val santaProgress = remember { Animatable(-0.4f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(15_000L, 30_000L))
            santaProgress.snapTo(-0.4f)
            santaProgress.animateTo(
                targetValue = 1.4f,
                animationSpec = tween(durationMillis = 6200, easing = LinearEasing)
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val value = santaProgress.value
        if (value <= -0.35f) return@Canvas

        val width = 168.dp.toPx()
        val height = width * santa.height / santa.width
        val x = value * (size.width + width) - width
        val bob = sin(value * PI.toFloat() * 2f) * 7.dp.toPx()
        val y = size.height * 0.20f + bob

        // A light trail makes the direction of the flight readable.
        val trail = Color(0xFFFFE9A8).copy(alpha = 0.18f)
        repeat(3) { index ->
            drawCircle(
                color = trail,
                radius = (7f - index * 1.6f).dp.toPx(),
                center = Offset(
                    x - (18 + index * 14).dp.toPx(),
                    y + height * 0.62f + index * 3.dp.toPx()
                )
            )
        }

        rotate(degrees = -4f, pivot = Offset(x + width / 2f, y + height / 2f)) {
            drawImage(
                image = santa,
                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                alpha = 0.98f,
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

/* ---------------------------------- Winter --------------------------------- */

private fun DrawScope.drawWinter(
    particles: List<SeasonalParticle>,
    progress: Float,
    dark: Boolean,
    snowflakes: List<ImageBitmap>
) {
    val drift = if (dark) Color(0xFFDCEBFF) else Color(0xFFAFCBEA)

    // Two soft snowdrifts along the bottom edge.
    drawPath(snowDrift(size.height * 0.935f, size.height * 0.020f), drift.copy(alpha = 0.10f))
    drawPath(snowDrift(size.height * 0.965f, size.height * 0.015f), drift.copy(alpha = 0.15f))

    drawSnowfall(
        particles = particles,
        progress = progress,
        snowflakes = snowflakes,
        tint = if (dark) Color.White else Color(0xFF86AED8),
        alphaMin = 0.35f,
        alphaMax = 0.92f
    )
}

private fun DrawScope.drawSnowfall(
    particles: List<SeasonalParticle>,
    progress: Float,
    snowflakes: List<ImageBitmap>,
    tint: Color,
    alphaMin: Float,
    alphaMax: Float
) {
    particles.forEach { particle ->
        val t = (particle.phase + progress * particle.speed) % 1f
        val x = (
            particle.x +
                sin(t * PI.toFloat() * 4f + particle.phase * PI.toFloat() * 2f) *
                particle.drift * 0.04f
            ) * size.width
        val y = (-0.10f + t * 1.20f) * size.height
        val side = particle.size.dp.toPx()
        val alpha = (alphaMin + (1f - t) * (alphaMax - alphaMin)).coerceIn(0.2f, alphaMax)
        val image = snowflakes[particle.type % snowflakes.size]
        rotate(
            degrees = particle.spin + t * (if (particle.type % 2 == 0) 150f else -120f),
            pivot = Offset(x, y)
        ) {
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (x - side / 2f).roundToInt(),
                    (y - side / 2f).roundToInt()
                ),
                dstSize = IntSize(side.roundToInt(), side.roundToInt()),
                alpha = alpha,
                colorFilter = ColorFilter.tint(tint),
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

private fun DrawScope.snowDrift(baseY: Float, amplitude: Float): Path = Path().apply {
    moveTo(0f, baseY)
    cubicTo(
        size.width * 0.18f, baseY - amplitude,
        size.width * 0.34f, baseY + amplitude,
        size.width * 0.52f, baseY
    )
    cubicTo(
        size.width * 0.68f, baseY - amplitude,
        size.width * 0.84f, baseY + amplitude,
        size.width, baseY - amplitude * 0.3f
    )
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/* ---------------------------------- Autumn --------------------------------- */

private fun DrawScope.drawAutumn(
    particles: List<SeasonalParticle>,
    progress: Float
) {
    particles.forEach { particle ->
        val t = (particle.phase + progress * particle.speed) % 1f
        val x = (
            particle.x +
                sin(t * PI.toFloat() * 3f + particle.phase * PI.toFloat() * 2f) *
                particle.drift * 0.045f
            ) * size.width
        val y = (-0.08f + t * 1.16f) * size.height
        val width = particle.size * 1.7f
        val height = width * 0.55f
        val color = autumnLeafColors[particle.tone % autumnLeafColors.size].copy(alpha = 0.78f)
        rotate(
            degrees = particle.spin + t * 420f,
            pivot = Offset(x, y)
        ) {
            drawOval(
                color = color,
                topLeft = Offset(x - width, y - height),
                size = Size(width * 2f, height * 2f)
            )
            drawLine(
                color = Color(0xFF6B3A1F).copy(alpha = 0.45f),
                start = Offset(x - width, y),
                end = Offset(x + width, y),
                strokeWidth = 1f
            )
        }
    }
}

/* --------------------------------- Halloween -------------------------------- */

private fun DrawScope.drawHalloween(progress: Float, dark: Boolean) {
    val webColor = if (dark) {
        Color(0xFFE9D5FF).copy(alpha = 0.20f)
    } else {
        Color(0xFF4C1D95).copy(alpha = 0.20f)
    }
    val webRadius = size.minDimension * 0.34f

    // Cobwebs hug the top corners instead of falling through the content.
    drawWeb(corner = Offset.Zero, radius = webRadius, flip = false, color = webColor)
    drawWeb(
        corner = Offset(size.width, 0f),
        radius = webRadius * 0.82f,
        flip = true,
        color = webColor
    )

    // A small spider sways on a thread under the left web.
    drawSpider(
        anchorX = size.width * 0.085f,
        topY = 0f,
        bodyY = size.height * 0.155f,
        radius = 4.2.dp.toPx(),
        color = if (dark) Color(0xFFE9D5FF).copy(alpha = 0.55f) else Color(0xFF3B0764),
        sway = sin(progress * 2f * PI.toFloat()) * 3.dp.toPx()
    )

    // Bats hover near the side edges (no falling particles).
    drawBat(
        center = Offset(
            size.width * 0.055f,
            size.height * 0.44f + sin(progress * 2f * PI.toFloat()) * 3.dp.toPx()
        ),
        span = 15.dp.toPx(),
        color = if (dark) Color(0xFFC4B5FD).copy(alpha = 0.45f) else Color(0xFF4C1D95).copy(alpha = 0.55f)
    )
    drawBat(
        center = Offset(
            size.width * 0.945f,
            size.height * 0.69f + sin(progress * 2f * PI.toFloat() + 1.8f) * 3.dp.toPx()
        ),
        span = 11.dp.toPx(),
        color = if (dark) Color(0xFFC4B5FD).copy(alpha = 0.38f) else Color(0xFF4C1D95).copy(alpha = 0.48f)
    )

    // Pumpkins sit in the bottom corners.
    drawPumpkin(
        center = Offset(size.width * 0.115f, size.height * 0.925f),
        radius = 27.dp.toPx(),
        rotateDegrees = -8f + sin(progress * 2f * PI.toFloat()) * 1.5f,
        glow = true
    )
    drawPumpkin(
        center = Offset(size.width * 0.885f, size.height * 0.955f),
        radius = 17.dp.toPx(),
        rotateDegrees = 7f,
        glow = false
    )
}

private fun DrawScope.drawWeb(
    corner: Offset,
    radius: Float,
    flip: Boolean,
    color: Color
) {
    val lineWidth = 1.1.dp.toPx()
    repeat(7) { index ->
        val angle = (index / 6f) * (PI.toFloat() / 2f)
        val dx = cos(angle) * radius
        val dy = sin(angle) * radius
        drawLine(
            color = color,
            start = corner,
            end = Offset(
                x = corner.x + if (flip) -dx else dx,
                y = corner.y + dy
            ),
            strokeWidth = lineWidth,
            cap = StrokeCap.Round
        )
    }

    repeat(3) { ring ->
        val r = radius * (0.38f + ring * 0.29f)
        drawArc(
            color = color,
            startAngle = if (flip) 90f else 0f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(corner.x - r, corner.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = lineWidth)
        )
    }
}

private fun DrawScope.drawSpider(
    anchorX: Float,
    topY: Float,
    bodyY: Float,
    radius: Float,
    color: Color,
    sway: Float
) {
    val x = anchorX + sway
    drawLine(
        color = color.copy(alpha = color.alpha * 0.8f),
        start = Offset(anchorX, topY),
        end = Offset(x, bodyY - radius * 1.4f),
        strokeWidth = 0.9.dp.toPx()
    )
    repeat(4) { leg ->
        val offset = (leg - 1.5f) * radius * 1.4f
        val reach = radius * 2.2f
        val y = bodyY - radius * 0.25f + kotlin.math.abs(leg - 1.5f) * radius * 0.5f
        drawLine(
            color = color,
            start = Offset(x + offset * 0.35f, y),
            end = Offset(x + offset + reach * if (leg < 2) -0.45f else 0.45f, y + radius * 1.1f),
            strokeWidth = 1.1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(x, bodyY)
    )
    drawCircle(
        color = color,
        radius = radius * 0.62f,
        center = Offset(x, bodyY - radius * 1.25f)
    )
}

private fun DrawScope.drawBat(center: Offset, span: Float, color: Color) {
    val wing = Path().apply {
        moveTo(center.x, center.y)
        quadraticTo(
            center.x - span * 0.65f, center.y - span * 0.55f,
            center.x - span, center.y - span * 0.05f
        )
        quadraticTo(
            center.x - span * 0.55f, center.y - span * 0.15f,
            center.x, center.y + span * 0.18f
        )
        quadraticTo(
            center.x + span * 0.55f, center.y - span * 0.15f,
            center.x + span, center.y - span * 0.05f
        )
        quadraticTo(
            center.x + span * 0.65f, center.y - span * 0.55f,
            center.x, center.y
        )
        close()
    }
    drawPath(wing, color)
    drawCircle(
        color = color,
        radius = span * 0.22f,
        center = Offset(center.x, center.y + span * 0.02f)
    )
}

private fun DrawScope.drawPumpkin(
    center: Offset,
    radius: Float,
    rotateDegrees: Float,
    glow: Boolean
) {
    val outline = Color(0xFF54210C).copy(alpha = 0.85f)
    val body = Color(0xFFF08A24)
    val bodyDark = Color(0xFFD9721A)
    val bodyLight = Color(0xFFFFB55C)

    rotate(rotateDegrees, pivot = center) {
        if (glow) {
            drawCircle(
                color = body.copy(alpha = 0.10f),
                radius = radius * 2.1f,
                center = center
            )
        }

        // Stem and leaf.
        drawLine(
            color = Color(0xFF3E6B3A),
            start = Offset(center.x, center.y - radius * 0.72f),
            end = Offset(center.x + radius * 0.12f, center.y - radius * 1.18f),
            strokeWidth = radius * 0.18f,
            cap = StrokeCap.Round
        )
        drawOval(
            color = Color(0xFF4C8B44).copy(alpha = 0.9f),
            topLeft = Offset(center.x + radius * 0.10f, center.y - radius * 1.10f),
            size = Size(radius * 0.55f, radius * 0.26f)
        )

        // Three lobes give the classic pumpkin silhouette.
        drawOval(
            color = bodyDark,
            topLeft = Offset(center.x - radius * 0.95f, center.y - radius * 0.66f),
            size = Size(radius * 0.78f, radius * 1.32f)
        )
        drawOval(
            color = bodyDark,
            topLeft = Offset(center.x + radius * 0.17f, center.y - radius * 0.66f),
            size = Size(radius * 0.78f, radius * 1.32f)
        )
        drawOval(
            color = body,
            topLeft = Offset(center.x - radius * 0.62f, center.y - radius * 0.78f),
            size = Size(radius * 1.24f, radius * 1.56f)
        )
        drawOval(
            color = bodyLight.copy(alpha = 0.35f),
            topLeft = Offset(center.x - radius * 0.42f, center.y - radius * 0.58f),
            size = Size(radius * 0.42f, radius * 0.75f)
        )

        // Thin dark ribs.
        val rib = Color(0xFFB95712).copy(alpha = 0.55f)
        drawOval(
            color = rib,
            topLeft = Offset(center.x - radius * 0.30f, center.y - radius * 0.72f),
            size = Size(radius * 0.60f, radius * 1.44f),
            style = Stroke(width = 1.1.dp.toPx())
        )

        // Friendly jack-o'-lantern face.
        val face = Color(0xFF3A1308).copy(alpha = 0.9f)
        val eye = Path().apply {
            moveTo(center.x - radius * 0.42f, center.y - radius * 0.16f)
            lineTo(center.x - radius * 0.18f, center.y - radius * 0.16f)
            lineTo(center.x - radius * 0.30f, center.y - radius * 0.42f)
            close()
        }
        drawPath(eye, face)
        val eye2 = Path().apply {
            moveTo(center.x + radius * 0.18f, center.y - radius * 0.16f)
            lineTo(center.x + radius * 0.42f, center.y - radius * 0.16f)
            lineTo(center.x + radius * 0.30f, center.y - radius * 0.42f)
            close()
        }
        drawPath(eye2, face)
        val smile = Path().apply {
            moveTo(center.x - radius * 0.34f, center.y + radius * 0.08f)
            quadraticTo(
                center.x, center.y + radius * 0.36f,
                center.x + radius * 0.34f, center.y + radius * 0.08f
            )
        }
        drawPath(smile, face, style = Stroke(width = radius * 0.12f, cap = StrokeCap.Round))
    }
}

/* ---------------------------------- New Year -------------------------------- */

private fun DrawScope.drawNewYear(
    particles: List<SeasonalParticle>,
    progress: Float,
    dark: Boolean,
    snowflakes: List<ImageBitmap>
) {
    drawGarland(progress = progress, dark = dark)

    drawSnowfall(
        particles = particles,
        progress = progress,
        snowflakes = snowflakes,
        tint = if (dark) Color.White else Color(0xFF9CC0E5),
        alphaMin = 0.30f,
        alphaMax = 0.80f
    )
}

private fun DrawScope.drawGarland(progress: Float, dark: Boolean) {
    val segments = 4
    val baseY = 7.dp.toPx()
    val sag = 13.dp.toPx()
    val stringColor = if (dark) {
        Color(0xFFDCEBFF).copy(alpha = 0.45f)
    } else {
        Color(0xFF4A5A70).copy(alpha = 0.45f)
    }

    val path = Path().apply {
        moveTo(0f, baseY)
        for (segment in 0 until segments) {
            val startX = size.width * segment / segments
            val endX = size.width * (segment + 1) / segments
            quadraticTo(
                (startX + endX) / 2f, baseY + sag * 2f,
                endX, baseY
            )
        }
    }
    drawPath(path, stringColor, style = Stroke(width = 1.2.dp.toPx()))

    val bulbColors = garlandBulbColors
    val bulbGap = 21.dp.toPx()
    var x = bulbGap * 0.35f
    var index = 0
    while (x < size.width) {
        val t = (x / size.width * segments) % 1f
        val y = baseY + sag * 4f * t * (1f - t)
        val color = bulbColors[index % bulbColors.size]
        val twinkle = 0.55f + 0.45f * ((sin(progress * 2f * PI.toFloat() + index * 1.1f) + 1f) / 2f)

        drawCircle(
            color = color.copy(alpha = 0.12f * twinkle),
            radius = 7.dp.toPx(),
            center = Offset(x, y + 2.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.35f + 0.55f * twinkle),
            radius = 3.2.dp.toPx(),
            center = Offset(x, y + 2.4.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.45f * twinkle),
            radius = 1.1.dp.toPx(),
            center = Offset(x - 0.9.dp.toPx(), y + 1.5.dp.toPx())
        )
        x += bulbGap
        index++
    }

    // A few golden sparks under the garland.
    repeat(7) { star ->
        val sx = size.width * (0.08f + star * 0.135f)
        val sy = baseY + sag * 1.15f + sin(progress * 2f * PI.toFloat() + star) * 2.dp.toPx()
        val alpha = 0.25f + 0.35f * ((sin(progress * 3f * PI.toFloat() + star * 1.7f) + 1f) / 2f)
        drawCircle(
            color = Color(0xFFFFE9A8).copy(alpha = alpha),
            radius = 1.3.dp.toPx(),
            center = Offset(sx, sy)
        )
    }
}
