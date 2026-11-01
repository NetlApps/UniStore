package ru.updatehub.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.BrandMark
import ru.updatehub.app.ui.components.GradientButton
import ru.updatehub.app.ui.components.SourceLogo
import ru.updatehub.app.ui.components.StaggeredAppear
import ru.updatehub.app.ui.components.pluralRu
import ru.updatehub.app.ui.theme.LocalReduceMotion
import ru.updatehub.app.ui.theme.rememberAnimatedColor
import ru.updatehub.app.ui.theme.pressScale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(
    state: UiState,
    onToggleSource: (UpdateSource) -> Unit,
    onStart: () -> Unit
) {
    val startTrigger = rememberPointOriginTrigger(onStart)
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 1.15f)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(top = 10.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(markSize = 38.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "UniStore",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "v1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(32.dp))

            StaggeredAppear(index = 0) {
                PulsingLogo()
            }

            Spacer(Modifier.height(28.dp))

            StaggeredAppear(index = 1) {
                Text(
                    text = buildAnnotatedString {
                        append("Все обновления\n")
                        withStyle(
                            SpanStyle(
                                brush = Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                )
                            )
                        ) {
                            append("в одном месте")
                        }
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(12.dp))

            StaggeredAppear(index = 2) {
                Text(
                    text = "Следите за версиями приложений из Google Play, RuStore, GetApps и других магазинов — без ручной проверки.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(26.dp))

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StaggeredAppear(index = 3) {
                    FeatureRow(
                        icon = Icons.Rounded.Storefront,
                        title = pluralRu(
                            UpdateSource.entries.size,
                            "источник",
                            "источника",
                            "источников"
                        ),
                        subtitle = "Google Play, RuStore, APKPure, Aptoide, F-Droid и магазины производителей"
                    )
                }
                StaggeredAppear(index = 4) {
                    FeatureRow(
                        icon = Icons.Rounded.Palette,
                        title = "Оформление под вас",
                        subtitle = "Адаптивная палитра, 4 режима темы и 6 фиксированных схем"
                    )
                }
                StaggeredAppear(index = 5) {
                    FeatureRow(
                        icon = Icons.Rounded.OpenInNew,
                        title = "Обновление без лишнего",
                        subtitle = "Скачивание APK, проверка подписи и системный установщик"
                    )
                }
            }

            Spacer(Modifier.height(30.dp))

            StaggeredAppear(index = 6) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Ваше устройство",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = "${state.deviceProfile.deviceLabel} · ${state.deviceProfile.osLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (state.deviceProfile.defaultSources.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "По умолчанию выбраны магазины, которые есть на телефоне или предусмотрены производителем:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                text = state.deviceProfile.defaultSources
                                    .joinToString(", ") { it.title },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            StaggeredAppear(index = 7) {
                Column {
                    Text(
                        text = "Источники обновлений",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Выберите, откуда искать новые версии. Позже можно изменить в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UpdateSource.entries.forEach { source ->
                            SourceChoiceChip(
                                source = source,
                                selected = source.key in state.settings.enabledSources,
                                onClick = { onToggleSource(source) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            StaggeredAppear(index = 8) {
                GradientButton(
                    text = "Начать",
                    onClick = startTrigger.onClick,
                    modifier = startTrigger.modifier,
                    icon = Icons.Rounded.ArrowForward
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Для RuStore, APKPure, F-Droid и Aptoide APK скачивается и проверяется внутри UniStore. Для остальных магазинов открывается их приложение.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PulsingLogo() {
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "welcomePulse")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse"
        )
        animated
    }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val logoScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.88f,
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = 0.7f, stiffness = 120f),
        label = "logoIn"
    )

    Box(
        modifier = Modifier
            .size(132.dp)
            .graphicsLayer {
                alpha = logoScale
                scaleX = logoScale
                scaleY = logoScale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0xFF22D3EE).copy(alpha = (1f - pulse) * 0.22f),
                radius = size.minDimension * (0.34f + 0.22f * pulse)
            )
        }
        BrandMark(markSize = 100.dp)
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceChoiceChip(
    source: UpdateSource,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val background by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(260),
        label = "sourceChipBg"
    )
    val border by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        },
        animationSpec = tween(260),
        label = "sourceChipBorder"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.985f,
        animationSpec = if (LocalReduceMotion.current) {
            snap()
        } else {
            spring(dampingRatio = 0.7f, stiffness = 400f)
        },
        label = "sourceChipScale"
    )

    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .pressScale(interaction),
        shape = CircleShape,
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SourceLogo(source = source, size = 18.dp)
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.5f),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
