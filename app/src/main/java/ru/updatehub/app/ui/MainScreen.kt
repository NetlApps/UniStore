package ru.updatehub.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.updatehub.app.data.AppUpdateResult
import ru.updatehub.app.data.InstalledApp
import ru.updatehub.app.data.Palette
import ru.updatehub.app.data.SeasonalEffect
import ru.updatehub.app.data.StoreConnection
import ru.updatehub.app.data.StoreHealth
import ru.updatehub.app.data.UpdateInstallState
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.data.UpdateStatus
import ru.updatehub.app.data.VersionRules
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.AppIcon
import ru.updatehub.app.ui.components.BrandMark
import ru.updatehub.app.ui.components.LocalSeasonalEffect
import ru.updatehub.app.ui.components.LocalSkipAppearAnimations
import ru.updatehub.app.ui.components.SectionCard
import ru.updatehub.app.ui.components.SourceBadge
import ru.updatehub.app.ui.components.SourceLogo
import ru.updatehub.app.ui.components.StaggeredAppear
import ru.updatehub.app.ui.components.StatusPill
import ru.updatehub.app.ui.components.StoreHealthDot
import ru.updatehub.app.ui.components.formatLastCheck
import ru.updatehub.app.ui.components.pluralRu
import ru.updatehub.app.ui.theme.LocalReduceMotion
import ru.updatehub.app.ui.theme.pressScale
import ru.updatehub.app.ui.theme.rememberAnimatedColor

/** Always-blue "Обновление" badge, independent of the seasonal palette. */
private val UpdateBadgeBlue = Color(0xFF2F6BFF)

private data class TrackedRow(
    val packageName: String,
    val app: InstalledApp?,
    val result: AppUpdateResult?
) {
    val label: String get() = app?.label ?: packageName
    val installedVersion: String
        get() = app?.versionName ?: result?.installedVersion.orEmpty()
    val status: UpdateStatus
        get() = when {
            app == null -> UpdateStatus.NOT_INSTALLED
            result == null -> UpdateStatus.UNKNOWN
            // A version known to be incompatible must stay that way even when
            // its version number is newer than the installed build.
            result.status == UpdateStatus.INCOMPATIBLE -> UpdateStatus.INCOMPATIBLE
            // Cached results may be from an older check. Re-check the
            // comparison so a stale store version can never look like an
            // update after the app was updated in the meantime.
            result.status == UpdateStatus.UPDATE_AVAILABLE &&
                result.best?.let { VersionRules.isNewer(it, app) } != true ->
                UpdateStatus.UP_TO_DATE

            else -> result.status
        }
    val best get() = result?.best?.takeIf { version ->
        result?.status == UpdateStatus.UPDATE_AVAILABLE &&
            app != null && VersionRules.isNewer(version, app)
    }
}

private enum class HeroState {
    CHECKING,
    UPDATES,
    ERROR,
    READY,
    ACTUAL,
    NO_COMPATIBLE,
    EMPTY
}

private data class HeroColors(
    val gradient: List<Color>,
    val content: Color,
    val buttonContainer: Color,
    val buttonContent: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: UiState,
    onCheck: () -> Unit,
    onAdd: () -> Unit,
    onSettings: (SettingsSection) -> Unit,
    onMessage: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetFilter: (AppFilter) -> Unit,
    onRetry: () -> Unit,
    onUpdate: (String) -> Unit,
    onOpenStore: (String, UpdateSource) -> Unit,
    onCancelInstall: (String) -> Unit,
    onUpdateAll: () -> Unit,
    onCancelBulk: () -> Unit,
    onDismissStoreRedirect: () -> Unit,
    onOpenStoreRedirect: (UpdateSource) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reduceMotion = LocalReduceMotion.current

    val installedMap = remember(state.installed) {
        state.installed.associateBy { it.packageName }
    }
    val allRows = remember(
        state.settings.trackedPackages,
        state.installed,
        state.results,
        state.settings.checkSystemApps
    ) {
        state.settings.trackedPackages
            .filter { packageName ->
                state.settings.checkSystemApps || installedMap[packageName]?.isSystem != true
            }
            .map { packageName ->
                TrackedRow(
                    packageName = packageName,
                    app = installedMap[packageName],
                    result = state.results[packageName]
                )
            }.sortedWith(
                compareBy<TrackedRow> { row ->
                    when (row.status) {
                        UpdateStatus.UPDATE_AVAILABLE -> 0
                        UpdateStatus.INCOMPATIBLE -> 1
                        UpdateStatus.ERROR -> 2
                        UpdateStatus.NOT_INSTALLED -> 3
                        UpdateStatus.UNKNOWN -> 4
                        UpdateStatus.UP_TO_DATE -> 5
                        UpdateStatus.NOT_FOUND -> 6
                    }
                }.thenBy { it.label.lowercase() }
            )
    }

    // The newest build in a store may be unusable on this phone (for example,
    // a 32-bit-only APK on a 64-bit device). Such versions are not offered to
    // the user at all: no card, no counter and no "Обновить" button. The app
    // stays tracked, so a compatible release will appear after the next check.
    val rows = allRows.filter { it.status != UpdateStatus.INCOMPATIBLE }
    val hiddenIncompatibleCount = allRows.size - rows.size

    val updatesCount = rows.count { it.status == UpdateStatus.UPDATE_AVAILABLE }
    val actualCount = rows.count { it.status == UpdateStatus.UP_TO_DATE }
    val errorCount = rows.count { it.status == UpdateStatus.ERROR }

    val visibleRows = when (state.filter) {
        AppFilter.ALL -> rows
        AppFilter.UPDATES -> rows.filter { it.status == UpdateStatus.UPDATE_AVAILABLE }
        AppFilter.ACTUAL -> rows.filter { it.status == UpdateStatus.UP_TO_DATE }
    }

    var selectedPackage by remember { mutableStateOf<String?>(null) }
    val selectedRow = selectedPackage?.let { pkg -> rows.firstOrNull { it.packageName == pkg } }

    fun startUpdate(packageName: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onUpdate(packageName)
    }

    fun openStore(packageName: String, source: UpdateSource) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onOpenStore(packageName, source)
    }

    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.check.isChecking, pullRefreshing) {
        if (pullRefreshing && !state.check.isChecking) {
            delay(250)
            if (!state.check.isChecking) pullRefreshing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            MainHeader(
                trackedCount = allRows.size,
                updatesCount = updatesCount,
                errorCount = errorCount,
                onSettings = { onSettings(SettingsSection.TOP) }
            )

            PullToRefreshBox(
                isRefreshing = pullRefreshing,
                onRefresh = {
                    pullRefreshing = true
                    onCheck()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                CompositionLocalProvider(
                    LocalSkipAppearAnimations provides listState.isScrollInProgress
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 110.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "hero") {
                        StaggeredAppear(index = 0) {
                            HeroCard(
                                state = state,
                                rows = rows,
                                updatesCount = updatesCount,
                                errorCount = errorCount,
                                hiddenIncompatibleCount = hiddenIncompatibleCount,
                                onCheck = onCheck,
                                onAdd = onAdd,
                                onUpdateAll = onUpdateAll,
                                onCancelBulk = onCancelBulk
                            )
                        }
                    }

                    item(key = "sources") {
                        StaggeredAppear(index = 1) {
                            SourcesSection(
                                enabledSources = UpdateSource.fromKeys(state.settings.enabledSources),
                                storeHealth = state.storeHealth,
                                onSettings = { onSettings(SettingsSection.SOURCES) }
                            )
                        }
                    }

                    item(key = "filter") {
                        StaggeredAppear(index = 2) {
                            FilterBar(
                                selected = state.filter,
                                allCount = rows.size,
                                updatesCount = updatesCount,
                                actualCount = actualCount,
                                onSelect = onSetFilter
                            )
                        }
                    }

                    when {
                        state.loadingApps && rows.isEmpty() -> {
                            items(3) { index ->
                                ShimmerTrackedCard(index)
                            }
                        }

                        rows.isEmpty() && hiddenIncompatibleCount > 0 -> {
                            item(key = "emptyIncompatible") {
                                NoCompatibleUpdatesState(onCheck = onCheck)
                            }
                        }

                        rows.isEmpty() &&
                            state.settings.trackedPackages.isNotEmpty() &&
                            !state.settings.checkSystemApps -> {
                            item(key = "emptySystem") {
                                SystemAppsHiddenState(
                                    onSettings = { onSettings(SettingsSection.SYSTEM) }
                                )
                            }
                        }

                        rows.isEmpty() -> {
                            item(key = "empty") {
                                EmptyTrackedState(onAdd = onAdd)
                            }
                        }

                        visibleRows.isEmpty() -> {
                            item(key = "emptyFilter") {
                                EmptyFilterState(
                                    filter = state.filter,
                                    onReset = { onSetFilter(AppFilter.ALL) }
                                )
                            }
                        }

                        else -> {
                            itemsIndexed(
                                items = visibleRows,
                                key = { _, row -> row.packageName }
                            ) { index, row ->
                                StaggeredAppear(
                                    index = index + 3,
                                    modifier = Modifier.animateItem()
                                ) {
                                    TrackedAppCard(
                                        row = row,
                                        installState = state.installStates[row.packageName],
                                        onOpen = { selectedPackage = row.packageName },
                                        onUpdate = { startUpdate(row.packageName) },
                                        onCancel = { onCancelInstall(row.packageName) },
                                        onRetry = onRetry
                                    )
                                }
                            }
                        }
                    }

                    if (rows.isNotEmpty()) {
                        item(key = "footer") {
                            Text(
                                text = "Проверено ${formatLastCheck(state.settings.lastCheckAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 4.dp)
                            )
                        }
                    }
                    }
                }
            }
        }

        // The checklist is one tap away at all times: the same draft lets the
        // user add new apps and uncheck the ones they no longer follow.
        AnimatedVisibility(
            visible = allRows.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(if (reduceMotion) snap() else tween(220)) +
                slideInVertically(if (reduceMotion) snap() else tween(280)) { it / 3 },
            exit = fadeOut(if (reduceMotion) snap() else tween(150)) +
                slideOutVertically(if (reduceMotion) snap() else tween(220)) { it / 3 }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
            ) {
                ManageAppsCard(
                    trackedCount = allRows.size,
                    onAdd = onAdd
                )
            }
        }
    }

    if (selectedRow != null) {
        AppDetailsSheet(
            row = selectedRow,
            installState = state.installStates[selectedRow.packageName],
            onDismiss = { selectedPackage = null },
            onUpdate = {
                selectedPackage = null
                startUpdate(selectedRow.packageName)
            },
            onOpenStore = { source ->
                selectedPackage = null
                openStore(selectedRow.packageName, source)
            },
            onCancel = {
                onCancelInstall(selectedRow.packageName)
            },
            onRemove = {
                selectedPackage = null
                onRemove(selectedRow.packageName)
            },
            onRetry = {
                selectedPackage = null
                onRetry()
            }
        )
    }

    state.storeRedirect?.let { request ->
        StoreRedirectDialog(
            request = request,
            onDismiss = onDismissStoreRedirect,
            onOpenStore = onOpenStoreRedirect
        )
    }
}

@Composable
private fun StoreRedirectDialog(
    request: StoreRedirectRequest,
    onDismiss: () -> Unit,
    onOpenStore: (UpdateSource) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val singleSource = request.sources.singleOrNull()
        ?.takeIf { request.packageName != null }
    val storesText = if (request.sources.size == 1) {
        "магазина ${request.sources.first().title}"
    } else {
        "магазинов ${request.sources.joinToString(", ") { it.title }}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = scheme.primary
            )
        },
        title = {
            Text(if (singleSource != null) "Страница приложения" else "Раздел обновлений")
        },
        text = {
            Column {
                Text(
                    text = if (singleSource != null) {
                        "Вы будете перенаправлены на страницу приложения " +
                            "в ${singleSource.title}, где можно установить новую версию."
                    } else {
                        "Вы будете перенаправлены в раздел обновлений $storesText, " +
                            "так как это предусмотрено его разработчиками."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (singleSource != null) {
                        "UniStore не может скачать это обновление напрямую: " +
                            "магазин не отдаёт APK-файл. Откроется страница " +
                            "приложения в ${singleSource.title}."
                    } else {
                        "UniStore не может скачать ${pluralRu(request.appCount, "это обновление", "эти обновления", "эти обновления")} " +
                            "напрямую: магазин не отдаёт APK-файл. Откроется нужный раздел, " +
                            "и магазин сам проверит наличие новых версий."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (singleSource != null) {
                        "На странице нажмите «Обновить» — Android не позволяет " +
                            "сделать это автоматически."
                    } else {
                        "Автоматически нажать кнопку проверки за вас Android не позволяет."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                request.sources.forEach { source ->
                    TextButton(onClick = { onOpenStore(source) }) {
                        SourceLogo(source = source, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть ${source.title}")
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun MainHeader(
    trackedCount: Int,
    updatesCount: Int,
    errorCount: Int,
    onSettings: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val settingsTrigger = rememberPointOriginTrigger(onSettings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(markSize = 40.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "UniStore",
                style = MaterialTheme.typography.titleLarge.copy(
                    brush = Brush.horizontalGradient(
                        listOf(scheme.primary, lerp(scheme.primary, scheme.tertiary, 0.8f))
                    )
                )
            )
            Text(
                text = when {
                    trackedCount == 0 -> "Добавьте приложения для отслеживания"
                    updatesCount > 0 -> "Доступно ${
                        pluralRu(updatesCount, "обновление", "обновления", "обновлений")
                    }"

                    errorCount > 0 -> "Не удалось проверить часть приложений"
                    else -> "${pluralRu(trackedCount, "приложение", "приложения", "приложений")} под наблюдением"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            IconButton(
                onClick = settingsTrigger.onClick,
                modifier = Modifier
                    .size(44.dp)
                    .then(settingsTrigger.modifier)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Настройки",
                    tint = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: UiState,
    rows: List<TrackedRow>,
    updatesCount: Int,
    errorCount: Int,
    hiddenIncompatibleCount: Int,
    onCheck: () -> Unit,
    onAdd: () -> Unit,
    onUpdateAll: () -> Unit,
    onCancelBulk: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    val seasonal = if (state.settings.palette == Palette.ADAPTIVE) {
        LocalSeasonalEffect.current
    } else {
        SeasonalEffect.NONE
    }
    val dark = scheme.background.luminance() < 0.5f
    val addTrigger = rememberPointOriginTrigger(onAdd)
    val heroState = when {
        state.check.isChecking -> HeroState.CHECKING
        rows.isEmpty() && hiddenIncompatibleCount > 0 -> HeroState.NO_COMPATIBLE
        rows.isEmpty() -> HeroState.EMPTY
        updatesCount > 0 -> HeroState.UPDATES
        rows.isNotEmpty() && errorCount == rows.size -> HeroState.ERROR
        state.settings.lastCheckAt == 0L -> HeroState.READY
        else -> HeroState.ACTUAL
    }

    // Halloween and New Year get a deliberately saturated hero instead of the
    // pastel blend produced by lightening every dark-theme accent. Text stays
    // white on these deep gradients; the primary button flips to a dark or
    // white plate so the contrast never depends on the gradient itself.
    val heroColors = when (seasonal) {
        SeasonalEffect.HALLOWEEN -> HeroColors(
            // Deep burnt orange into aubergine keeps white text readable.
            // A bright orange start looked nice but body copy fell below the
            // comfortable contrast ratio.
            gradient = listOf(
                Color(0xFFC2410C),
                Color(0xFF9A3412),
                Color(0xFF581C87)
            ),
            content = Color.White,
            buttonContainer = Color(0xFF1C131A),
            buttonContent = Color(0xFFFFC38A)
        )

        SeasonalEffect.NEW_YEAR -> HeroColors(
            gradient = if (dark) {
                listOf(Color(0xFF1E3A8A), Color(0xFF0369A1), Color(0xFF0891B2))
            } else {
                listOf(Color(0xFF2563EB), Color(0xFF0284C7), Color(0xFF0891B2))
            },
            content = Color.White,
            buttonContainer = Color.White,
            buttonContent = Color(0xFF0C4A6E)
        )

        else -> HeroColors(
            gradient = listOf(
                scheme.primary,
                lerp(scheme.primary, scheme.secondary, 0.58f),
                lerp(scheme.primary, scheme.tertiary, 0.42f)
            ),
            content = scheme.onPrimary,
            buttonContainer = scheme.onPrimary,
            buttonContent = scheme.primary
        )
    }

    val gradient = Brush.linearGradient(colors = heroColors.gradient)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = heroColors.content.copy(alpha = 0.09f),
                radius = size.width * 0.42f,
                center = Offset(size.width * 0.94f, -size.height * 0.12f)
            )
            drawCircle(
                color = heroColors.content.copy(alpha = 0.07f),
                radius = size.width * 0.30f,
                center = Offset(size.width * 0.80f, size.height * 0.86f)
            )
            drawCircle(
                color = heroColors.content.copy(alpha = 0.06f),
                radius = size.width * 0.16f,
                center = Offset(size.width * 0.18f, size.height * 0.95f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(heroColors.content.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = heroColors.content,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "ПРОВЕРКА ОБНОВЛЕНИЙ",
                    style = MaterialTheme.typography.labelSmall,
                    color = heroColors.content.copy(alpha = 0.78f),
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(14.dp))

            AnimatedContent(
                targetState = heroState,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(260)) + scaleIn(
                            animationSpec = tween(300),
                            initialScale = 0.92f,
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        )) togetherWith
                            (fadeOut(tween(140)) + scaleOut(
                                animationSpec = tween(180),
                                targetScale = 0.97f,
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            ))
                    }
                },
                label = "heroContent"
            ) { hero ->
                Column {
                    Text(
                        text = when (hero) {
                            HeroState.CHECKING -> "Проверяем…"
                            HeroState.UPDATES -> pluralRu(
                                updatesCount,
                                "обновление",
                                "обновления",
                                "обновлений"
                            )

                            HeroState.ERROR -> "Не удалось проверить"
                            HeroState.READY -> "Готовы проверить"
                            HeroState.ACTUAL -> "Всё актуально"
                            HeroState.NO_COMPATIBLE -> "Нет совместимых обновлений"
                            HeroState.EMPTY -> "Начните с приложений"
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        color = heroColors.content
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (hero) {
                            HeroState.CHECKING -> {
                                val total = state.check.total.coerceAtLeast(1)
                                val done = state.check.done.coerceIn(0, total)
                                if (state.check.currentApp != null) {
                                    "Проверено $done из $total · ${state.check.currentApp}"
                                } else {
                                    "Проверено $done из $total"
                                }
                            }

                            HeroState.UPDATES -> "Для ${pluralRu(updatesCount, "приложения", "приложений", "приложений")} есть новые версии"
                            HeroState.ERROR -> "Проверьте подключение к интернету и попробуйте снова"
                            HeroState.READY -> "Узнайте, какие приложения можно обновить"
                            HeroState.ACTUAL -> "Последняя проверка ${formatLastCheck(state.settings.lastCheckAt)}"
                            HeroState.NO_COMPATIBLE ->
                                "Магазины предлагают только версии, несовместимые с этим телефоном"

                            HeroState.EMPTY -> "Добавьте первое приложение, чтобы следить за обновлениями"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = heroColors.content.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (rows.isNotEmpty()) {
                val actualCount = rows.count { it.status == UpdateStatus.UP_TO_DATE }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroStat(
                        value = updatesCount.toString(),
                        label = "обновления",
                        modifier = Modifier.weight(1f),
                        highlight = updatesCount > 0,
                        contentColor = heroColors.content
                    )
                    HeroStat(
                        value = actualCount.toString(),
                        label = "актуальны",
                        modifier = Modifier.weight(1f),
                        contentColor = heroColors.content
                    )
                    HeroStat(
                        value = errorCount.toString(),
                        label = "ошибки",
                        modifier = Modifier.weight(1f),
                        highlight = errorCount > 0,
                        contentColor = heroColors.content
                    )
                }
            }

            if (state.check.isChecking) {
                Spacer(Modifier.height(16.dp))
                val total = state.check.total.coerceAtLeast(1)
                val target = state.check.done.toFloat() / total
                val reduceMotion = LocalReduceMotion.current
                val progress by animateFloatAsState(
                    targetValue = target.coerceIn(0f, 1f),
                    animationSpec = if (reduceMotion) snap() else tween(420),
                    label = "checkProgress"
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = heroColors.content,
                    trackColor = heroColors.content.copy(alpha = 0.22f),
                    strokeCap = StrokeCap.Round
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val buttonLabel = when (heroState) {
                    HeroState.EMPTY -> "Добавить приложения"
                    HeroState.CHECKING -> "Проверяем…"
                    else -> if (state.settings.lastCheckAt > 0L) "Проверить снова" else "Проверить"
                }
                val interaction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        if (heroState == HeroState.EMPTY) addTrigger.onClick() else onCheck()
                    },
                    enabled = heroState != HeroState.CHECKING,
                    modifier = Modifier
                        .height(46.dp)
                        .pressScale(interaction)
                        .then(addTrigger.modifier),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = heroColors.buttonContainer,
                        contentColor = heroColors.buttonContent,
                        disabledContainerColor = heroColors.buttonContainer.copy(alpha = 0.62f),
                        disabledContentColor = heroColors.buttonContent.copy(alpha = 0.65f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    interactionSource = interaction
                ) {
                    if (heroState == HeroState.CHECKING) {
                        RotatingRefreshIcon(tint = heroColors.buttonContent)
                    } else {
                        Icon(
                            imageVector = if (heroState == HeroState.EMPTY) {
                                Icons.Rounded.Add
                            } else {
                                Icons.Rounded.Refresh
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = formatLastCheck(state.settings.lastCheckAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = heroColors.content.copy(alpha = 0.68f)
                )
            }

            if (updatesCount > 0 && !state.check.isChecking) {
                Spacer(Modifier.height(12.dp))
                val bulk = state.bulk
                val updateAllInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { if (bulk.isRunning) onCancelBulk() else onUpdateAll() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .pressScale(updateAllInteraction),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bulk.isRunning) {
                            scheme.error.copy(alpha = 0.24f)
                        } else {
                            heroColors.content.copy(alpha = 0.16f)
                        },
                        contentColor = heroColors.content
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    interactionSource = updateAllInteraction
                ) {
                    Icon(
                        imageVector = if (bulk.isRunning) {
                            Icons.Rounded.Close
                        } else {
                            Icons.Rounded.SystemUpdate
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (bulk.isRunning) {
                            val step = (bulk.done + 1).coerceAtMost(bulk.total.coerceAtLeast(1))
                            "Отменить · $step из ${bulk.total}"
                        } else {
                            "Обновить всё"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (bulk.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    val target = if (bulk.total > 0) {
                        bulk.done.toFloat() / bulk.total
                    } else {
                        0f
                    }
                    val progress by animateFloatAsState(
                        targetValue = target.coerceIn(0f, 1f),
                        animationSpec = if (reduceMotion) snap() else tween(420),
                        label = "bulkProgress"
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = heroColors.content,
                        trackColor = heroColors.content.copy(alpha = 0.22f),
                        strokeCap = StrokeCap.Round
                    )
                    bulk.currentLabel?.let { label ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Сейчас: $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = heroColors.content.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    contentColor: Color = Color.White
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                contentColor.copy(alpha = if (highlight) 0.20f else 0.11f)
            )
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RotatingRefreshIcon(tint: Color) {
    if (LocalReduceMotion.current) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "rotatingRefresh")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refreshAngle"
    )
    Icon(
        imageVector = Icons.Rounded.Refresh,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(18.dp)
            .graphicsLayer { rotationZ = angle }
    )
}

@Composable
private fun SourcesSection(
    enabledSources: List<UpdateSource>,
    storeHealth: Map<UpdateSource, StoreHealth>,
    onSettings: () -> Unit
) {
    val settingsTrigger = rememberPointOriginTrigger(onSettings)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Источники",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = settingsTrigger.onClick,
                modifier = Modifier.then(settingsTrigger.modifier),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "Настроить",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        if (enabledSources.isEmpty()) {
            Text(
                text = "Источники не выбраны",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                enabledSources.forEach { source ->
                    SourceQuickChip(
                        source = source,
                        health = storeHealth[source],
                        onClick = onSettings
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StoreHealthLegendDot(StoreConnection.ONLINE, "хорошая")
                StoreHealthLegendDot(StoreConnection.SLOW, "слабая")
                StoreHealthLegendDot(StoreConnection.OFFLINE, "нет связи")
            }
        }
    }
}

@Composable
private fun StoreHealthLegendDot(connection: StoreConnection, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StoreHealthDot(health = StoreHealth(connection), size = 7.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SourceQuickChip(
    source: UpdateSource,
    health: StoreHealth?,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val trigger = rememberPointOriginTrigger(onClick)
    Surface(
        modifier = Modifier
            .then(trigger.modifier)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = trigger.onClick
            )
            .pressScale(interaction),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SourceLogo(source = source, size = 16.dp)
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StoreHealthDot(health = health, size = 7.dp)
        }
    }
}

@Composable
private fun FilterBar(
    selected: AppFilter,
    allCount: Int,
    updatesCount: Int,
    actualCount: Int,
    onSelect: (AppFilter) -> Unit
) {
    val tabs = listOf(
        Triple(AppFilter.ALL, "Все", allCount),
        Triple(AppFilter.UPDATES, "Обновления", updatesCount),
        Triple(AppFilter.ACTUAL, "Актуальные", actualCount)
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp)
    ) {
        val tabWidth = maxWidth / 3
        val reduceMotion = LocalReduceMotion.current
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex.toFloat(),
            animationSpec = if (reduceMotion) {
                snap()
            } else {
                spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow
                )
            },
            label = "filterOffset"
        )

        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { (filter, label, count) ->
                val isSelected = filter == selected
                val textColor by rememberAnimatedColor(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(220),
                    label = "filterText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable { onSelect(filter) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (count > 0) "$label $count" else label,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackedAppCard(
    row: TrackedRow,
    installState: UpdateInstallState?,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val busy = installState.isBusy()
    val hasUpdate = row.status == UpdateStatus.UPDATE_AVAILABLE
    val cardColor = if (hasUpdate) {
        lerp(scheme.primaryContainer, scheme.surfaceContainerLow, 0.58f)
    } else {
        scheme.surfaceContainerLow
    }
    val cardBorder = if (hasUpdate) {
        scheme.primary.copy(alpha = 0.32f)
    } else {
        scheme.outlineVariant.copy(alpha = 0.45f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen
            )
            .pressScale(interaction),
        shape = RoundedCornerShape(22.dp),
        color = cardColor,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    if (hasUpdate) {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            scheme.primary.copy(alpha = 0.20f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                    AppIcon(
                        packageName = row.packageName,
                        label = row.label,
                        size = 52.dp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(7.dp))
                        RowStatusPill(row = row)
                    }
                    Spacer(Modifier.height(4.dp))
                    VersionLine(row = row)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val installText = installState.installStatusText()
                        when {
                            installText != null -> Text(
                                text = installText,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (installState) {
                                    is UpdateInstallState.Failed -> scheme.error
                                    else -> scheme.primary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            row.status == UpdateStatus.UPDATE_AVAILABLE && row.best != null ->
                                SourceBadge(source = row.best!!.source)

                            else -> Text(
                                text = when (row.status) {
                                    UpdateStatus.ERROR -> row.result?.message ?: "Ошибка проверки"
                                    UpdateStatus.INCOMPATIBLE ->
                                        row.result?.message ?: "Несовместимо с телефоном"

                                    UpdateStatus.NOT_FOUND -> "Нет в выбранных источниках"
                                    UpdateStatus.NOT_INSTALLED -> "Приложение не установлено"
                                    UpdateStatus.UNKNOWN -> "Ещё не проверяли"
                                    UpdateStatus.UP_TO_DATE ->
                                        row.result?.message ?: "Установлена последняя версия"

                                    UpdateStatus.UPDATE_AVAILABLE -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.status == UpdateStatus.ERROR ||
                                    row.status == UpdateStatus.INCOMPATIBLE
                                ) {
                                    scheme.error
                                } else {
                                    scheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InstallBusyButton(installState)
                        if (installState.isCancellable()) {
                            Spacer(Modifier.width(2.dp))
                            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Отменить загрузку",
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    installState is UpdateInstallState.Failed &&
                        row.status == UpdateStatus.UPDATE_AVAILABLE -> {
                        FilledTonalButton(
                            onClick = onUpdate,
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Повторить",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    row.status == UpdateStatus.UPDATE_AVAILABLE -> {
                        FilledTonalButton(
                            onClick = onUpdate,
                            modifier = Modifier.height(36.dp),
                            shape = RoundedCornerShape(13.dp),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Обновить",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    row.status == UpdateStatus.UP_TO_DATE -> {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = "Актуально",
                            tint = scheme.secondary,
                            modifier = Modifier.size(25.dp)
                        )
                    }

                    row.status == UpdateStatus.INCOMPATIBLE -> {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "Несовместимо с телефоном",
                            tint = scheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    row.status == UpdateStatus.ERROR || row.status == UpdateStatus.UNKNOWN -> {
                        IconButton(onClick = onRetry, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Проверить",
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    row.status == UpdateStatus.NOT_FOUND -> {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    row.status == UpdateStatus.NOT_INSTALLED -> {
                        IconButton(onClick = onRetry, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Проверить",
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            if (installState is UpdateInstallState.Downloading) {
                Spacer(Modifier.height(9.dp))
                DownloadProgress(installState)
            }
        }
    }
}

private fun UpdateInstallState?.isBusy(): Boolean =
    this is UpdateInstallState.Resolving ||
        this is UpdateInstallState.Downloading ||
        this is UpdateInstallState.Verifying ||
        this is UpdateInstallState.Ready ||
        this is UpdateInstallState.Installing

private fun UpdateInstallState?.isCancellable(): Boolean =
    this is UpdateInstallState.Resolving ||
        this is UpdateInstallState.Downloading ||
        this is UpdateInstallState.Verifying ||
        this is UpdateInstallState.Ready

private fun UpdateInstallState?.installStatusText(): String? = when (this) {
    is UpdateInstallState.Resolving -> "Получаем ссылку на APK…"
    is UpdateInstallState.Downloading -> {
        val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        "Скачивание$percent"
    }

    is UpdateInstallState.Verifying -> "Проверяем подпись APK…"
    is UpdateInstallState.Ready -> "Скачано, ожидает установки"
    is UpdateInstallState.Installing -> "Открываем системный установщик…"
    is UpdateInstallState.Failed -> message
    is UpdateInstallState.Completed -> "Готово"
    null -> null
}

@Composable
private fun InstallBusyButton(state: UpdateInstallState?) {
    val scheme = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(13.dp),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
    ) {
        if (LocalReduceMotion.current || state is UpdateInstallState.Ready) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(14.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = scheme.primary
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = when (state) {
                is UpdateInstallState.Downloading ->
                    state.progress?.let { "${(it * 100).toInt()}%" } ?: "Загрузка"

                is UpdateInstallState.Verifying -> "Проверка"
                is UpdateInstallState.Ready -> "В очереди"
                is UpdateInstallState.Installing -> "Установка"
                else -> "Загрузка"
            },
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun DownloadProgress(state: UpdateInstallState.Downloading) {
    val progress = state.progress
    val modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
        .clip(CircleShape)
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            strokeCap = StrokeCap.Round
        )
    } else {
        if (LocalReduceMotion.current) {
            LinearProgressIndicator(
                progress = { 0.35f },
                modifier = modifier,
                strokeCap = StrokeCap.Round
            )
        } else {
            LinearProgressIndicator(
                modifier = modifier,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun RowStatusPill(row: TrackedRow) {
    when (row.status) {
        UpdateStatus.UPDATE_AVAILABLE -> StatusPill(
            text = "Обновление",
            color = UpdateBadgeBlue,
            icon = Icons.Rounded.NewReleases
        )

        UpdateStatus.INCOMPATIBLE -> StatusPill(
            text = "Несовместимо",
            color = MaterialTheme.colorScheme.error,
            icon = Icons.Rounded.ErrorOutline
        )

        UpdateStatus.UP_TO_DATE -> StatusPill(
            text = "Актуально",
            color = MaterialTheme.colorScheme.secondary,
            icon = Icons.Rounded.CheckCircle
        )

        UpdateStatus.ERROR -> StatusPill(
            text = "Ошибка",
            color = MaterialTheme.colorScheme.error,
            icon = Icons.Rounded.ErrorOutline
        )

        UpdateStatus.NOT_FOUND -> StatusPill(
            text = "Не найдено",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Rounded.Info
        )

        UpdateStatus.NOT_INSTALLED -> StatusPill(
            text = "Не установлено",
            color = MaterialTheme.colorScheme.error,
            icon = Icons.Rounded.ErrorOutline
        )

        UpdateStatus.UNKNOWN -> StatusPill(
            text = "Не проверено",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VersionLine(row: TrackedRow) {
    val scheme = MaterialTheme.colorScheme
    val best = row.best
    if (row.status == UpdateStatus.UPDATE_AVAILABLE && best != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.installedVersion.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = "  →  ",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = best.versionName,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    } else {
        Text(
            text = if (row.installedVersion.isBlank()) "Версия неизвестна"
            else "Версия ${row.installedVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NoCompatibleUpdatesState(onCheck: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Нет совместимых обновлений",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Магазины предлагают только версии, которые не подходят этому телефону. " +
                    "Приложения остаются в отслеживаемых — новая совместимая версия появится " +
                    "здесь после следующей проверки.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCheck,
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text("Проверить снова")
            }
        }
    }
}

@Composable
private fun ManageAppsCard(
    trackedCount: Int,
    onAdd: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val trigger = rememberPointOriginTrigger(onAdd)
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    trigger.onClick()
                }
            )
            .pressScale(interaction)
            .then(trigger.modifier),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f)),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Apps,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Добавить или убрать приложения",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Отслеживается ${pluralRu(
                        trackedCount,
                        "приложение",
                        "приложения",
                        "приложений"
                    )} · нажмите, чтобы поставить галочки",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EmptyTrackedState(onAdd: () -> Unit) {
    val addTrigger = rememberPointOriginTrigger(onAdd)
    SectionCard(contentPadding = PaddingValues(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmptyIllustration()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Пока пусто",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Добавьте приложения, за обновлениями которых хотите следить. Мы проверим их в выбранных магазинах.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = addTrigger.onClick,
                modifier = Modifier.then(addTrigger.modifier),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text("Добавить приложения")
            }
        }
    }
}

@Composable
private fun SystemAppsHiddenState(onSettings: () -> Unit) {
    val settingsTrigger = rememberPointOriginTrigger(onSettings)
    SectionCard(contentPadding = PaddingValues(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Только системные приложения",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Системные приложения не проверяются по умолчанию. Включите их проверку в настройках, чтобы увидеть их в списке.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = settingsTrigger.onClick,
                modifier = Modifier.then(settingsTrigger.modifier),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text("Открыть настройки")
            }
        }
    }
}

@Composable
private fun EmptyIllustration() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(112.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = scheme.primary.copy(alpha = 0.18f),
                radius = size.minDimension * 0.48f,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(7.dp.toPx(), 6.dp.toPx())
                    )
                )
            )
            drawCircle(
                color = scheme.primary.copy(alpha = 0.08f),
                radius = size.minDimension * 0.34f
            )
        }
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
private fun EmptyFilterState(filter: AppFilter, onReset: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (filter == AppFilter.UPDATES) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.Info
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (filter == AppFilter.UPDATES) "Обновлений нет" else "Здесь пока пусто",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (filter == AppFilter.UPDATES) {
                    "Все отслеживаемые приложения уже обновлены."
                } else {
                    "В этой категории пока нет приложений."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onReset) {
                Text("Показать все")
            }
        }
    }
}

@Composable
private fun ShimmerTrackedCard(index: Int) {
    SectionCard(contentPadding = PaddingValues(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ru.updatehub.app.ui.components.ShimmerBox(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(15.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ru.updatehub.app.ui.components.ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .height(15.dp)
                )
                Spacer(Modifier.height(7.dp))
                ru.updatehub.app.ui.components.ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(11.dp)
                )
            }
            ru.updatehub.app.ui.components.ShimmerBox(
                modifier = Modifier
                    .width(78.dp)
                    .height(30.dp),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDetailsSheet(
    row: TrackedRow,
    installState: UpdateInstallState?,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onOpenStore: (UpdateSource) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current
    var sheetEntered by remember { mutableStateOf(reduceMotion) }
    val iconScale by animateFloatAsState(
        targetValue = if (sheetEntered) 1f else 0.66f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "detailsIconScale"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (sheetEntered) 1f else 0f,
        animationSpec = tween(150),
        label = "detailsIconAlpha"
    )
    LaunchedEffect(Unit) { sheetEntered = true }
    val best = row.best
    val result = row.result
    val versions = result?.versions.orEmpty()
    val app = row.app
    val newerVersion = if (row.status == UpdateStatus.INCOMPATIBLE) {
        result?.best
    } else {
        best?.takeIf { version ->
            app != null && VersionRules.isNewer(version, app)
        }
    }
    val availableVersions = versions.filter { version ->
        app != null && VersionRules.isNewer(version, app)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    packageName = row.packageName,
                    label = row.label,
                    size = 62.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = iconAlpha
                        transformOrigin = TransformOrigin.Center
                    }
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = row.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    RowStatusPill(row)
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.surfaceContainer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VersionBlock(
                    title = "Установлено",
                    version = row.installedVersion.ifBlank { "—" },
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurfaceVariant
                )
                VersionBlock(
                    title = when {
                        row.status == UpdateStatus.INCOMPATIBLE -> "Несовместимо"
                        newerVersion != null -> "Доступно"
                        else -> "Актуально"
                    },
                    version = newerVersion?.versionName ?: row.installedVersion.ifBlank { "—" },
                    color = when {
                        row.status == UpdateStatus.INCOMPATIBLE -> scheme.error
                        newerVersion != null -> scheme.primary
                        else -> scheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }

            if (installState != null && installState !is UpdateInstallState.Completed) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (installState is UpdateInstallState.Failed) {
                                scheme.errorContainer.copy(alpha = 0.4f)
                            } else {
                                scheme.primaryContainer.copy(alpha = 0.4f)
                            }
                        )
                        .padding(14.dp)
                ) {
                    Text(
                        text = installState.installStatusText().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (installState is UpdateInstallState.Failed) {
                            scheme.onErrorContainer
                        } else {
                            scheme.onPrimaryContainer
                        }
                    )
                    if (installState is UpdateInstallState.Downloading) {
                        Spacer(Modifier.height(9.dp))
                        DownloadProgress(installState)
                    }
                    if (installState.isCancellable()) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Отменить загрузку",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.error
                            )
                        }
                    }
                }
            }

            if (row.status == UpdateStatus.INCOMPATIBLE) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.errorContainer.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result?.message
                            ?: "Эта версия несовместима с вашим телефоном",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer
                    )
                }
            }

            if (result?.message != null && row.status == UpdateStatus.ERROR) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.errorContainer.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = result.message.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (availableVersions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Найдено в источниках",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(scheme.surfaceContainer)
                ) {
                    availableVersions.forEachIndexed { index, version ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = scheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SourceLogo(source = version.source, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = version.source.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = scheme.onSurface
                                )
                                Text(
                                    text = "Версия ${version.versionName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onOpenStore(version.source) }) {
                                Text("В магазине")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (best != null && row.app != null) {
                val busy = installState.isBusy()
                Button(
                    onClick = onUpdate,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary
                    )
                ) {
                    if (busy) {
                        if (LocalReduceMotion.current) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(19.dp),
                                strokeWidth = 2.dp,
                                color = scheme.onPrimary
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (installState is UpdateInstallState.Failed) {
                                Icons.Rounded.Refresh
                            } else {
                                Icons.Rounded.Download
                            },
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when {
                            busy -> "Подготовка…"
                            installState is UpdateInstallState.Failed -> "Повторить"
                            best.source.supportsDirectDownload -> "Скачать и установить"
                            else -> "Обновить в ${best.source.title}"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить снова")
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = scheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text("Убрать из отслеживаемых")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    row.status == UpdateStatus.INCOMPATIBLE ->
                        "Эта версия не устанавливается на телефон: не подходит процессор, версия Android или набор частей APK. Более новая версия будет проверена заново."

                    best?.source?.supportsDirectDownload == true ->
                        "UniStore скачает APK, проверит подпись и откроет системный установщик. Установку нужно подтвердить."

                    else ->
                        "Для этого магазина откроется его приложение — скачивание и установку подтвердите там."
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VersionBlock(
    title: String,
    version: String,
    color: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = version,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
