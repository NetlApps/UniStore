package ru.updatehub.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.updatehub.app.R
import ru.updatehub.app.data.StoreConnection
import ru.updatehub.app.data.StoreHealth
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.ui.AppViewModel
import ru.updatehub.app.ui.theme.LocalReduceMotion
import ru.updatehub.app.ui.theme.pressScale
import ru.updatehub.app.ui.theme.rememberCappedLoop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> {
    error("AppViewModel is not provided")
}

private val placeholderPalettes = listOf(
    Color(0xFF5B5BD6) to Color(0xFF00A6A6),
    Color(0xFF0077B6) to Color(0xFF48CAE4),
    Color(0xFF2D6A4F) to Color(0xFF74C69D),
    Color(0xFFD95D39) to Color(0xFFE9C46A),
    Color(0xFF7B61FF) to Color(0xFFFF8FAB),
    Color(0xFFC9184A) to Color(0xFFFF8FA3)
)

@Composable
fun AppIcon(
    packageName: String,
    label: String,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier
) {
    val viewModel = LocalAppViewModel.current
    // Start from the in-memory cache: after navigation the icon is already
    // decoded, and waiting for another IO round trip used to flash the letter
    // placeholder on every card.
    var icon by remember(packageName) { mutableStateOf(viewModel.peekIcon(packageName)) }
    LaunchedEffect(packageName) {
        if (icon == null) icon = viewModel.loadIcon(packageName)
    }
    val shape = RoundedCornerShape(size * 0.29f)
    val palette = remember(packageName) {
        placeholderPalettes[Math.floorMod(packageName.hashCode(), placeholderPalettes.size)]
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(palette.first, palette.second)))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = shape
            ),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp
            )
        }
    }
}

@Composable
fun SourceLogo(
    source: UpdateSource,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(size * 0.31f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color(source.brandColor)),
        contentAlignment = Alignment.Center
    ) {
        if (source == UpdateSource.GOOGLE_PLAY) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = source.title,
                tint = Color.White,
                modifier = Modifier.size(size * 0.68f)
            )
        } else {
            Text(
                text = when (source) {
                    UpdateSource.GALAXY_STORE -> "S"
                    else -> source.title.firstOrNull()?.uppercase() ?: "?"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.5f).sp
            )
        }
    }
}

@Composable
fun SourceBadge(
    source: UpdateSource,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SourceLogo(source = source, size = 16.dp)
        if (showTitle) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp)
) {
    if (LocalReduceMotion.current) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

@Composable
fun AnimatedGlowBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    // A 22-second triangle: eleven seconds to one side, eleven back. Published
    // at 30 fps instead of every vsync; the shift is far too slow to notice.
    val loop = if (reduceMotion) null else rememberCappedLoop(durationMillis = 22_000)

    Box(modifier = modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val shift = loop?.let { state ->
                val phase = state.value
                if (phase <= 0.5f) phase * 2f else (1f - phase) * 2f
            } ?: 0.5f

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(scheme.background, scheme.surfaceContainerLowest)
                )
            )

            val radius = size.width * 0.95f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.15f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(
                        x = size.width * (0.78f + shift * 0.16f),
                        y = size.height * 0.05f
                    ),
                    radius = radius
                ),
                radius = radius,
                center = Offset(
                    x = size.width * (0.78f + shift * 0.16f),
                    y = size.height * 0.05f
                )
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        scheme.tertiary.copy(alpha = 0.09f * intensity),
                        Color.Transparent
                    ),
                    center = Offset(
                        x = size.width * (0.16f - shift * 0.1f),
                        y = size.height * 0.42f
                    ),
                    radius = radius * 0.75f
                ),
                radius = radius * 0.75f,
                center = Offset(
                    x = size.width * (0.16f - shift * 0.1f),
                    y = size.height * 0.42f
                )
            )
        }

        SeasonalEffectOverlay(modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun BrandMark(
    markSize: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(markSize)
            .clip(RoundedCornerShape(markSize * 0.23f))
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val interaction = remember { MutableInteractionSource() }
    val animatedContainer by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(220),
        label = "primaryButtonContainer"
    )
    val animatedContent by animateColorAsState(
        targetValue = contentColor,
        animationSpec = tween(220),
        label = "primaryButtonContent"
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .pressScale(interaction),
        enabled = enabled,
        shape = RoundedCornerShape(17.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedContainer,
            contentColor = animatedContent
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        interactionSource = interaction
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primary,
                        lerp(scheme.primary, scheme.secondary, 0.62f)
                    )
                )
            )
            .clickable(
                interactionSource = interaction,
                indication = indication,
                enabled = enabled,
                onClick = onClick
            )
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(content = content, modifier = Modifier.padding(contentPadding))
    }
}

/**
 * While a lazy list is being flung or dragged, newly composed rows should not
 * start their own delayed entrance animation. That was the reason fast
 * scrolling in Settings felt like the interface was lagging behind the finger.
 */
val LocalSkipAppearAnimations = compositionLocalOf { false }

@Composable
fun StaggeredAppear(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val reduceMotion = LocalReduceMotion.current
    val scrolling = LocalSkipAppearAnimations.current
    val instant = reduceMotion || scrolling
    var visible by remember { mutableStateOf(instant) }

    LaunchedEffect(instant) {
        if (instant) {
            visible = true
        } else if (!visible) {
            // Short stagger only for the first, calm screen paint.
            delay(index.coerceAtMost(8) * 22L)
            visible = true
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (instant) {
            EnterTransition.None
        } else {
            fadeIn(tween(200)) + scaleIn(
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                initialScale = 0.94f,
                transformOrigin = TransformOrigin(0.5f, 0f)
            )
        }
    ) {
        content()
    }
}

fun pluralRu(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "$count $many"
        mod10 == 1 -> "$count $one"
        mod10 in 2..4 -> "$count $few"
        else -> "$count $many"
    }
}

fun formatLastCheck(timestamp: Long): String {
    if (timestamp <= 0L) return "ещё не проверяли"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "только что"
        diff < 3_600_000 -> "${diff / 60_000} мин назад"
        diff < 86_400_000 -> "${diff / 3_600_000} ч назад"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("ru", "RU")).format(Date(timestamp))
    }
}

/**
 * Small traffic-light dot for a store connection: green for a fast response,
 * yellow for a weak link, red when the probe failed or timed out.
 */
@Composable
fun StoreHealthDot(health: StoreHealth?, size: Dp = 8.dp) {
    val color = when (health?.connection) {
        StoreConnection.ONLINE -> Color(0xFF22A06B)
        StoreConnection.SLOW -> Color(0xFFE0A800)
        StoreConnection.OFFLINE -> Color(0xFFD93B3B)
        StoreConnection.CHECKING, null -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
