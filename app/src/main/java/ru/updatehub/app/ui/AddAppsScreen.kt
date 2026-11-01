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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
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
    onAdd: (Set<String>) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    val tracked = state.settings.trackedPackages
    val query = state.search.trim()
    val systemVisible = state.showSystemApps && state.settings.checkSystemApps
    val candidates = state.installed.filter { app ->
        (!app.isSystem || systemVisible) &&
            app.packageName !in tracked &&
            (
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
                )
    }

    LaunchedEffect(systemVisible) {
        if (!systemVisible) {
            val userPackages = state.installed
                .asSequence()
                .filter { !it.isSystem }
                .map { it.packageName }
                .toSet()
            selected = selected.intersect(userPackages)
        }
    }

    fun toggle(app: InstalledApp) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selected = if (app.packageName in selected) {
            selected - app.packageName
        } else {
            selected + app.packageName
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 0.7f)

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Добавить приложения",
                subtitle = if (state.loadingApps) {
                    "Загружаем список…"
                } else {
                    "${pluralRu(candidates.size, "приложение", "приложения", "приложений")} доступно"
                },
                onBack = onBack,
                actions = {
                    if (candidates.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                val allVisible = candidates.map { it.packageName }.toSet()
                                selected = if (selected.containsAll(allVisible)) {
                                    selected - allVisible
                                } else {
                                    selected + allVisible
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        ) {
                            Text(
                                text = if (selected.containsAll(candidates.map { it.packageName })) {
                                    "Сбросить"
                                } else {
                                    "Выбрать все"
                                },
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
                if (selected.isNotEmpty()) {
                    Text(
                        text = "Выбрано: ${selected.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
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

                    candidates.isEmpty() -> {
                        EmptyAddState(
                            allAdded = state.installed.isNotEmpty() &&
                                state.installed.none { app ->
                                    (!app.isSystem || systemVisible) &&
                                        app.packageName !in tracked
                                },
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
                                items = candidates,
                                key = { it.packageName }
                            ) { app ->
                                AddAppRow(
                                    app = app,
                                    selected = app.packageName in selected,
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
                PrimaryButton(
                    text = if (selected.isEmpty()) {
                        "Выберите приложения"
                    } else {
                        "Добавить ${pluralRu(selected.size, "приложение", "приложения", "приложений")}"
                    },
                    onClick = {
                        val allowed = if (state.settings.checkSystemApps) {
                            selected
                        } else {
                            val systemPackages = state.installed
                                .asSequence()
                                .filter { it.isSystem }
                                .map { it.packageName }
                                .toHashSet()
                            selected - systemPackages
                        }
                        onAdd(allowed)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selected.isNotEmpty(),
                    icon = Icons.Rounded.Add
                )
            }
        }
    }
}

@Composable
private fun AddAppRow(
    app: InstalledApp,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val background by rememberAnimatedColor(
        targetValue = if (selected) {
            scheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            scheme.surfaceContainerLow
        },
        animationSpec = tween(220),
        label = "addRowBg"
    )
    val border by rememberAnimatedColor(
        targetValue = if (selected) {
            scheme.primary.copy(alpha = 0.55f)
        } else {
            scheme.outlineVariant.copy(alpha = 0.4f)
        },
        animationSpec = tween(220),
        label = "addRowBorder"
    )

    Surface(
        modifier = Modifier
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
                        color = if (selected) {
                            scheme.primary
                        } else {
                            scheme.surfaceContainerHighest
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(tween(120)) + scaleIn(
                        initialScale = 0.4f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
                    ),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.4f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Выбрано",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAddState(
    allAdded: Boolean,
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
            text = when {
                hasQuery -> "Ничего не найдено"
                allAdded -> "Все приложения уже добавлены"
                else -> "Нет доступных приложений"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                hasQuery -> "Попробуйте изменить запрос."
                allAdded -> "Новые приложения появятся здесь после установки."
                else -> "Включите отображение системных приложений или установите новое приложение."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
