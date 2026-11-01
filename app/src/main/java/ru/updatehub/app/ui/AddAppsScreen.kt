package ru.updatehub.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.updatehub.app.data.InstalledApp
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.AppIcon
import ru.updatehub.app.ui.components.PrimaryButton
import ru.updatehub.app.ui.components.ScreenHeader
import ru.updatehub.app.ui.components.ShimmerBox
import ru.updatehub.app.ui.components.pluralRu
import ru.updatehub.app.ui.theme.rememberAnimatedColor

@Composable
fun AddAppsScreen(
    state: UiState,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onShowSystem: (Boolean) -> Unit,
    onApply: (add: Set<String>, remove: Set<String>) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val tracked = state.settings.trackedPackages
    // Edits stay local until the user confirms them, so a stray tap cannot
    // remove an app and several quick taps produce one atomic update.
    var draft by remember(tracked) { mutableStateOf(tracked) }

    val query = state.search.trim()
    val systemVisible = state.showSystemApps && state.settings.checkSystemApps
    val apps = state.installed.filter { app ->
        (!app.isSystem || systemVisible) &&
            (
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                )
    }

    val additions = draft - tracked
    val removals = tracked - draft
    val changed = additions.isNotEmpty() || removals.isNotEmpty()
    val removalOnly = additions.isEmpty() && removals.isNotEmpty()
    val installedCount = state.installed.count {
        !it.isSystem || state.settings.checkSystemApps
    }

    fun toggle(app: InstalledApp) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        draft = if (app.packageName in draft) {
            draft - app.packageName
        } else {
            draft + app.packageName
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 0.7f)

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Приложения",
                subtitle = when {
                    state.loadingApps -> "Загружаем список…"
                    else -> "Отслеживается ${draft.size} из $installedCount"
                },
                onBack = onBack,
                actions = {
                    if (changed) {
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                draft = tracked
                            }
                        ) {
                            Text(
                                text = "Сбросить",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            )

            OutlinedTextField(
                value = state.search,
                onValueChange = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = {
                    Text(
                        text = "Название или пакет",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = state.search.isNotEmpty(),
                        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.7f),
                        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.7f)
                    ) {
                        IconButton(onClick = { onSearch("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Очистить",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.settings.checkSystemApps) {
                    FilterChip(
                        selected = state.showSystemApps,
                        onClick = { onShowSystem(!state.showSystemApps) },
                        label = {
                            Text(
                                text = "Системные",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        shape = CircleShape
                    )
                } else {
                    Text(
                        text = "Системные приложения скрыты",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (changed) {
                    Text(
                        text = "Изменено: ${additions.size + removals.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (removalOnly) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loadingApps -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(7) {
                                AddAppSkeleton()
                            }
                        }
                    }

                    apps.isEmpty() -> {
                        EmptyAppsState(
                            hasQuery = query.isNotBlank(),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(24.dp)
                        )
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 120.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = apps,
                                key = { it.packageName }
                            ) { app ->
                                AddAppRow(
                                    app = app,
                                    checked = app.packageName in draft,
                                    onClick = { toggle(app) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val scheme = MaterialTheme.colorScheme
                PrimaryButton(
                    text = when {
                        additions.isNotEmpty() && removals.isNotEmpty() ->
                            "Сохранить изменения"

                        additions.isNotEmpty() -> "Добавить ${pluralRu(
                            additions.size,
                            "приложение",
                            "приложения",
                            "приложений"
                        )}"

                        removals.isNotEmpty() -> "Убрать ${pluralRu(
                            removals.size,
                            "приложение",
                            "приложения",
                            "приложений"
                        )}"

                        else -> "Нет изменений"
                    },
                    onClick = { onApply(additions, removals) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = changed,
                    icon = when {
                        additions.isNotEmpty() && removals.isNotEmpty() -> Icons.Rounded.Check
                        additions.isNotEmpty() -> Icons.Rounded.Check
                        removals.isNotEmpty() -> Icons.Rounded.Delete
                        else -> Icons.Rounded.Check
                    },
                    containerColor = if (removalOnly) scheme.error else scheme.primary,
                    contentColor = if (removalOnly) scheme.onError else scheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun AddAppRow(
    app: InstalledApp,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val background by rememberAnimatedColor(
        targetValue = if (checked) {
            scheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            scheme.surfaceContainerLow
        },
        animationSpec = tween(220),
        label = "addRowBg"
    )
    val border by rememberAnimatedColor(
        targetValue = if (checked) {
            scheme.primary.copy(alpha = 0.55f)
        } else {
            scheme.outlineVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(220),
        label = "addRowBorder"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(18.dp),
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                packageName = app.packageName,
                label = app.label,
                size = 46.dp
            )
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${app.packageName} · ${app.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(27.dp)
                    .clip(CircleShape)
                    .background(
                        color = if (checked) {
                            scheme.primary
                        } else {
                            scheme.surfaceContainerHighest
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = checked,
                    enter = fadeIn(tween(120)) + scaleIn(
                        initialScale = 0.4f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
                    ),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.4f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Отслеживается",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAppsState(
    hasQuery: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (hasQuery) Icons.Rounded.Search else Icons.Rounded.SelectAll,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (hasQuery) "Ничего не найдено" else "Нет доступных приложений",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasQuery) {
                "Попробуйте изменить запрос."
            } else {
                "Включите отображение системных приложений или установите новое приложение."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddAppSkeleton() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(13.dp)
            )
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(13.dp)
                )
                Spacer(Modifier.height(6.dp))
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(10.dp)
                )
            }
            ShimmerBox(
                modifier = Modifier.size(27.dp),
                shape = CircleShape
            )
        }
    }
}
