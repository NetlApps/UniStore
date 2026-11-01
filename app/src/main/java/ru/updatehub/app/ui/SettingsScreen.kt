package ru.updatehub.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.updatehub.app.BuildConfig
import ru.updatehub.app.data.DeviceProfile
import ru.updatehub.app.data.ManualStore
import ru.updatehub.app.data.ManualStoreAvailability
import ru.updatehub.app.data.Palette
import ru.updatehub.app.data.SeasonalTheme
import ru.updatehub.app.data.StoreConnection
import ru.updatehub.app.data.StoreHealth
import ru.updatehub.app.data.StoreLauncher
import ru.updatehub.app.data.ThemeMode
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.LocalSkipAppearAnimations
import ru.updatehub.app.ui.components.ScreenHeader
import ru.updatehub.app.ui.components.SectionCard
import ru.updatehub.app.ui.components.SourceLogo
import ru.updatehub.app.ui.components.StaggeredAppear
import ru.updatehub.app.ui.components.StoreHealthDot
import ru.updatehub.app.ui.components.formatLastCheck
import ru.updatehub.app.ui.components.pluralRu
import ru.updatehub.app.data.storeHealthLabel
import ru.updatehub.app.ui.theme.pressScale
import ru.updatehub.app.ui.theme.rememberAnimatedColor

private data class ThemeModeOption(
    val mode: ThemeMode,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

private data class PaletteOption(
    val palette: Palette,
    val colors: List<Color>
)

private val themeModeOptions = listOf(
    ThemeModeOption(ThemeMode.SYSTEM, "Системная", "Как в Android", Icons.Rounded.SettingsBrightness),
    ThemeModeOption(ThemeMode.LIGHT, "Светлая", "Всегда светлая", Icons.Rounded.LightMode),
    ThemeModeOption(ThemeMode.DARK, "Тёмная", "Всегда тёмная", Icons.Rounded.DarkMode),
    ThemeModeOption(ThemeMode.BLACK, "Чёрная", "AMOLED-экран", Icons.Rounded.Contrast)
)

private val paletteOptions = listOf(
    PaletteOption(
        Palette.ADAPTIVE,
        listOf(Color(0xFF5B5BD6), Color(0xFF00A896), Color(0xFFE86A5E))
    ),
    PaletteOption(Palette.DEFAULT, listOf(Color(0xFF5B5BD6))),
    PaletteOption(Palette.OCEAN, listOf(Color(0xFF0077B6))),
    PaletteOption(Palette.FOREST, listOf(Color(0xFF2D6A4F))),
    PaletteOption(Palette.SUNSET, listOf(Color(0xFFD95D39))),
    PaletteOption(Palette.LAVENDER, listOf(Color(0xFF7B61FF))),
    PaletteOption(Palette.ROSE, listOf(Color(0xFFC9184A)))
)

private val intervalOptions = listOf(6, 12, 24, 48)

/**
 * Sections of the settings list that other screens can jump to. [itemIndex]
 * must match the order of the `item(key = ...)` blocks in [SettingsScreen];
 * keeping the mapping here avoids magic numbers at every call site.
 */
enum class SettingsSection(val key: String, val itemIndex: Int) {
    TOP("top", 0),
    APPEARANCE("appearance", 0),
    SEASONAL("seasonal", 1),
    APPS("apps", 2),
    SOURCES("sources", 3),
    MANUAL_STORES("manual_stores", 4),
    DEVICE("device", 5),
    PERFORMANCE("performance", 6),
    BACKGROUND("background", 7),
    CHECK("check", 8),
    SYSTEM("system", 9),
    ABOUT("about", 10);

    companion object {
        fun fromKey(key: String?): SettingsSection =
            entries.firstOrNull { it.key == key } ?: TOP
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (Palette) -> Unit,
    onSeasonalTheme: (SeasonalTheme) -> Unit,
    onWeakDevice: (Boolean) -> Unit,
    onToggleSource: (UpdateSource) -> Unit,
    onRefreshStoreHealth: () -> Unit,
    onOpenManualStore: (ManualStore) -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onCheckSystemApps: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onManageApps: () -> Unit,
    onMessage: (String) -> Unit,
    initialSection: SettingsSection = SettingsSection.TOP
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val manualStores = remember(context) { StoreLauncher.manualStores(context) }
    val listState = rememberLazyListState()

    // Arriving from a card on the main screen: scroll to the exact section
    // that card controls instead of dropping the user at the top of a long
    // settings page. The first frame is awaited so the lazy list has its
    // layout and the target item can be resolved.
    LaunchedEffect(initialSection) {
        if (initialSection.itemIndex > 0) {
            withFrameNanos { }
            listState.animateScrollToItem(initialSection.itemIndex)
        }
    }

    LaunchedEffect(Unit) {
        onRefreshStoreHealth()
    }
    val installedMap = remember(state.installed) {
        state.installed.associateBy { it.packageName }
    }
    // System packages may be present in the scanner result so that the user
    // can enable them explicitly, but they must never count as tracked while
    // the switch is off.
    val trackedCount = remember(
        state.settings.trackedPackages,
        installedMap,
        state.settings.checkSystemApps
    ) {
        if (state.settings.checkSystemApps) {
            state.settings.trackedPackages.size
        } else {
            state.settings.trackedPackages.count {
                installedMap[it]?.isSystem != true
            }
        }
    }
    val installedCount = remember(installedMap, state.settings.checkSystemApps) {
        if (state.settings.checkSystemApps) {
            installedMap.size
        } else {
            installedMap.values.count { !it.isSystem }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            onMessage("Уведомления отключены — проверка будет работать без напоминаний")
        }
    }

    fun changeAutoCheck(enabled: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        onAutoCheck(enabled)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 0.8f)

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Настройки",
                subtitle = "Оформление, производительность и проверки",
                onBack = onBack
            )

            CompositionLocalProvider(
                LocalSkipAppearAnimations provides listState.isScrollInProgress
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item(key = "appearance") {
                        StaggeredAppear(index = 0) {
                            AppearanceSection(
                                mode = state.settings.themeMode,
                                palette = state.settings.palette,
                                onThemeMode = onThemeMode,
                                onPalette = onPalette
                            )
                        }
                    }

                    item(key = "seasonal") {
                        StaggeredAppear(index = 1) {
                            SeasonalSection(
                                selected = state.settings.seasonalTheme,
                                onSelect = onSeasonalTheme
                            )
                        }
                    }

                    item(key = "apps") {
                        StaggeredAppear(index = 2) {
                            AppsSettingsSection(
                                trackedCount = trackedCount,
                                installedCount = installedCount,
                                onManageApps = onManageApps
                            )
                        }
                    }

                    item(key = "sources") {
                        StaggeredAppear(index = 3) {
                            SourcesSettingsSection(
                                enabledSources = state.settings.enabledSources,
                                storeHealth = state.storeHealth,
                                vpnBypassed = state.vpnBypassed,
                                onToggleSource = onToggleSource
                            )
                        }
                    }

                    item(key = "manual_stores") {
                        StaggeredAppear(index = 4) {
                            ManualStoresSection(
                                stores = manualStores,
                                onOpenStore = onOpenManualStore
                            )
                        }
                    }

                    item(key = "device") {
                        StaggeredAppear(index = 5) {
                            DeviceSection(profile = state.deviceProfile)
                        }
                    }

                    item(key = "performance") {
                        StaggeredAppear(index = 6) {
                            WeakDeviceSection(
                                enabled = state.settings.weakDevice,
                                profile = state.deviceProfile,
                                onToggle = onWeakDevice
                            )
                        }
                    }

                    item(key = "background") {
                        StaggeredAppear(index = 7) {
                            BackgroundCheckSection(
                                autoCheck = state.settings.autoCheck,
                                intervalHours = state.settings.intervalHours,
                                onAutoCheck = { changeAutoCheck(it) },
                                onInterval = onInterval
                            )
                        }
                    }

                    item(key = "check") {
                        StaggeredAppear(index = 8) {
                            CheckNowSection(
                                lastCheckAt = state.settings.lastCheckAt,
                                trackedCount = trackedCount,
                                isChecking = state.check.isChecking,
                                onCheck = onCheck
                            )
                        }
                    }

                    item(key = "system") {
                        StaggeredAppear(index = 9) {
                            SystemAppsSection(
                                checkSystemApps = state.settings.checkSystemApps,
                                onCheckSystemApps = onCheckSystemApps
                            )
                        }
                    }

                    item(key = "about") {
                        StaggeredAppear(index = 10) {
                            AboutSection(
                                installedCount = installedCount,
                                trackedCount = trackedCount
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(
    mode: ThemeMode,
    palette: Palette,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (Palette) -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Palette,
            title = "Оформление",
            subtitle = "Тема и цвет приложения"
        )

        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            themeModeOptions.chunked(2).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { option ->
                        ThemeModeCard(
                            option = option,
                            selected = option.mode == mode,
                            onClick = { onThemeMode(option.mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "Цветовая схема",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(9.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            paletteOptions.forEach { option ->
                PaletteChip(
                    option = option,
                    selected = option.palette == palette,
                    onClick = { onPalette(option.palette) }
                )
            }
        }

        if (palette == Palette.ADAPTIVE) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Адаптивная схема подстраивается под обои, системную тему и сезонное " +
                    "оформление: на Хэллоуин — тёплые оранжево-графитовые тона, в Новый год — " +
                    "снежно-синие с лёгкой стеклянностью, осенью — янтарные, зимой — холодные " +
                    "голубые. Вне сезона на Android 12+ используется Material You. Остальные " +
                    "схемы фиксированы.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonalSection(
    selected: SeasonalTheme,
    onSelect: (SeasonalTheme) -> Unit
) {
    val effect = selected.resolve()

    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.AcUnit,
            title = "Сезонное оформление",
            subtitle = "Снег, листья и праздничные эффекты"
        )

        Spacer(Modifier.height(14.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SeasonalTheme.entries.forEach { theme ->
                FilterChip(
                    selected = theme == selected,
                    onClick = { onSelect(theme) },
                    label = {
                        Text(
                            text = theme.title,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = CircleShape
                )
            }
        }

        Spacer(Modifier.height(11.dp))

        Text(
            text = if (selected == SeasonalTheme.AUTO) {
                "Сейчас: ${effect.title}. Эффект выбирается автоматически по дате."
            } else {
                "Сейчас: ${effect.title}."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Зимой — крупные снежинки и сугробы, осенью — листья, на Хэллоуин — паутина и тыквы по краям, на Новый год — Санта, гирлянды и снег. Весной и летом фон остаётся обычным. Можно выбрать оформление вручную.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThemeModeCard(
    option: ThemeModeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val background by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(260),
        label = "themeCardBg"
    )
    val border by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(260),
        label = "themeCardBorder"
    )
    val contentColor by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(260),
        label = "themeCardContent"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(17.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .pressScale(interaction),
        shape = RoundedCornerShape(17.dp),
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = option.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.5f),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.5f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PaletteChip(
    option: PaletteOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val background by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = tween(240),
        label = "paletteChipBg"
    )
    val border by rememberAnimatedColor(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        },
        animationSpec = tween(240),
        label = "paletteChipBorder"
    )

    Surface(
        modifier = Modifier
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
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(
                        if (option.colors.size > 1) {
                            Brush.sweepGradient(option.colors + option.colors.first())
                        } else {
                            Brush.linearGradient(listOf(option.colors.first(), option.colors.first()))
                        }
                    )
            )
            Text(
                text = option.palette.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.5f),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.5f)
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

@Composable
private fun SourcesSettingsSection(
    enabledSources: Set<String>,
    storeHealth: Map<UpdateSource, StoreHealth>,
    vpnBypassed: Boolean,
    onToggleSource: (UpdateSource) -> Unit
) {
    val universal = UpdateSource.entries.filter { it.isUniversal }
    val manufacturer = UpdateSource.entries.filterNot { it.isUniversal }

    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Storefront,
            title = "Источники обновлений",
            subtitle = "Где искать новые версии приложений"
        )

        Spacer(Modifier.height(14.dp))

        SourceGroupHeader(
            title = "Топ-5 универсальных",
            subtitle = "Работают на любом Android-телефоне"
        )
        SourceRows(universal, enabledSources, storeHealth, vpnBypassed, onToggleSource)

        Spacer(Modifier.height(16.dp))

        SourceGroupHeader(
            title = "Магазины производителей",
            subtitle = "Для техники конкретного бренда"
        )
        SourceRows(manufacturer, enabledSources, storeHealth, vpnBypassed, onToggleSource)

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Должен оставаться включённым хотя бы один источник.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        StoreHealthLegend()
    }
}

@Composable
private fun StoreHealthLegend() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StoreHealthLegendItem(StoreConnection.ONLINE, "хорошая связь")
        StoreHealthLegendItem(StoreConnection.SLOW, "слабая")
        StoreHealthLegendItem(StoreConnection.OFFLINE, "нет связи")
    }
}

@Composable
private fun StoreHealthLegendItem(connection: StoreConnection, label: String) {
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
private fun SourceGroupHeader(
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SourceRows(
    sources: List<UpdateSource>,
    enabledSources: Set<String>,
    storeHealth: Map<UpdateSource, StoreHealth>,
    vpnBypassed: Boolean,
    onToggleSource: (UpdateSource) -> Unit
) {
    sources.forEachIndexed { index, source ->
        if (index > 0) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            )
            Spacer(Modifier.height(4.dp))
        }
        SourceSettingsRow(
            source = source,
            enabled = source.key in enabledSources,
            health = storeHealth[source],
            vpnBypassed = vpnBypassed,
            onToggle = { onToggleSource(source) }
        )
    }
}

@Composable
private fun ManualStoresSection(
    stores: List<ManualStoreAvailability>,
    onOpenStore: (ManualStore) -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Storefront,
            title = "Магазины производителей",
            subtitle = "Ручной переход к обновлениям"
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "AppGallery, Honor App Market, HeyTap Store, Palm Store, Amazon " +
                "Appstore, Lenovo и ZTE не публикуют версии приложений. UniStore " +
                "не может проверять их автоматически и не показывает выдуманные обновления.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Показаны все поддерживаемые магазины. Установленные открываются сразу, " +
                "для остальных кнопка «Сайт» ведёт на страницу производителя.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        if (stores.isEmpty()) {
            Text(
                text = "Список магазинов пуст.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            stores.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                    )
                    Spacer(Modifier.height(4.dp))
                }
                ManualStoreRow(
                    store = item.store,
                    installed = item.installed,
                    onOpen = { onOpenStore(item.store) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Кнопка «Открыть» запускает установленный магазин. На Huawei UniStore " +
                "дополнительно пробует сразу открыть раздел обновлений. Автоматически " +
                "нажимать кнопки за вас Android не позволяет.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ManualStoreRow(
    store: ManualStore,
    installed: Boolean,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(store.brandColor),
                            Color(store.brandColor).copy(alpha = 0.65f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Storefront,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = store.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = store.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onOpen,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(
                text = if (installed) "Открыть" else "Сайт",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun SourceSettingsRow(
    source: UpdateSource,
    enabled: Boolean,
    health: StoreHealth?,
    vpnBypassed: Boolean,
    onToggle: () -> Unit
) {
    val healthColor = when (health?.connection) {
        StoreConnection.OFFLINE -> MaterialTheme.colorScheme.error
        StoreConnection.SLOW -> Color(0xFFB07A00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        health == null -> source.subtitle
        vpnBypassed &&
            health.connection == StoreConnection.OFFLINE &&
            source in setOf(UpdateSource.GOOGLE_PLAY, UpdateSource.APKPURE) ->
            "VPN активен · UniStore вне туннеля"

        source == UpdateSource.GOOGLE_PLAY && health.connection == StoreConnection.OFFLINE ->
            "Нет связи · проверьте сеть"

        else -> storeHealthLabel(health)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceLogo(source = source, size = 34.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = healthColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (health != null) {
            Spacer(Modifier.width(8.dp))
            StoreHealthDot(health = health, size = 8.dp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun DeviceSection(profile: DeviceProfile) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.PhoneAndroid,
            title = "Ваше устройство",
            subtitle = "Определено автоматически"
        )

        Spacer(Modifier.height(12.dp))

        InfoLine(label = "Модель", value = profile.deviceLabel)
        InfoLine(
            label = "Операционная система",
            value = profile.osLabel
        )
        if (profile.totalRamMb > 0) {
            InfoLine(label = "Оперативная память", value = "${profile.totalRamMb} МБ")
        }
        if (profile.cpuCores > 0) {
            InfoLine(
                label = "Ядра процессора",
                value = profile.cpuCores.toString()
            )
        }
        InfoLine(
            label = "Магазины на телефоне",
            value = profile.installedSources
                .joinToString(", ") { it.title }
                .ifBlank { "Не найдены" }
        )
        InfoLine(
            label = "Выбрано по умолчанию",
            value = profile.defaultSources
                .joinToString(", ") { it.title }
                .ifBlank { "Не определены" }
        )

        Spacer(Modifier.height(9.dp))
        Text(
            text = "Источники выбраны по установленным магазинам и рекомендациям производителя для этой версии Android. Вы можете изменить выбор в разделе выше.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeakDeviceSection(
    enabled: Boolean,
    profile: DeviceProfile,
    onToggle: (Boolean) -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                icon = Icons.Rounded.Speed,
                title = "Слабое устройство",
                subtitle = "Быстрее и без анимаций",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (enabled) {
                "Анимации отключены: переходы, появление карточек, shimmer и сезонные эффекты статичны. Смена темы и палитры применяется сразу. Прогресс скачивания обновляется реже, чтобы интерфейс не дёргался."
            } else if (profile.isLowRam) {
                "Устройство маломощное. Включите этот режим, чтобы убрать все анимации и сделать интерфейс максимально отзывчивым."
            } else {
                "Включите на слабом телефоне: анимации, переходы и сезонные эффекты отключатся, а интерфейс будет работать быстрее."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppsSettingsSection(
    trackedCount: Int,
    installedCount: Int,
    onManageApps: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val trigger = rememberPointOriginTrigger(onManageApps)

    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Apps,
            title = "Приложения",
            subtitle = "Список для проверки обновлений"
        )

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
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
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pluralRu(
                            trackedCount,
                            "приложение отслеживается",
                            "приложения отслеживаются",
                            "приложений отслеживается"
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Из $installedCount установленного · изменить",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SystemAppsSection(
    checkSystemApps: Boolean,
    onCheckSystemApps: (Boolean) -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                icon = Icons.Rounded.Build,
                title = "Системные приложения",
                subtitle = "Проверка встроенных приложений",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checkSystemApps,
                onCheckedChange = onCheckSystemApps,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (checkSystemApps) {
                "Системные приложения будут проверяться наравне с остальными и появятся в общем списке."
            } else {
                "По умолчанию системные приложения пропускаются: для них обновления обычно приходят вместе с прошивкой."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundCheckSection(
    autoCheck: Boolean,
    intervalHours: Int,
    onAutoCheck: (Boolean) -> Unit,
    onInterval: (Int) -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                icon = Icons.Rounded.NotificationsActive,
                title = "Фоновая проверка",
                subtitle = "Уведомления о новых версиях",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = autoCheck,
                onCheckedChange = onAutoCheck,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        AnimatedVisibility(
            visible = autoCheck,
            enter = fadeIn(tween(220)) + expandVertically(tween(260)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200))
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Периодичность",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    intervalOptions.forEach { hours ->
                        FilterChip(
                            selected = hours == intervalHours,
                            onClick = { onInterval(hours) },
                            label = {
                                Text(
                                    text = "$hours ч",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            shape = CircleShape
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "Android может откладывать фоновые проверки для экономии заряда. Уведомление появится только при наличии обновлений.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CheckNowSection(
    lastCheckAt: Long,
    trackedCount: Int,
    isChecking: Boolean,
    onCheck: () -> Unit
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Refresh,
            title = "Проверка обновлений",
            subtitle = "Последняя: ${formatLastCheck(lastCheckAt)}"
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pluralRu(
                        trackedCount,
                        "приложение отслеживается",
                        "приложения отслеживаются",
                        "приложений отслеживается"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isChecking) "Идёт проверка…" else "Можно запустить вручную в любой момент",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = onCheck,
                enabled = !isChecking && trackedCount > 0,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isChecking) "Проверяем" else "Проверить",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun AboutSection(
    installedCount: Int,
    trackedCount: Int
) {
    SectionCard(contentPadding = PaddingValues(18.dp)) {
        SectionTitle(
            icon = Icons.Rounded.Info,
            title = "О приложении",
            subtitle = "UniStore ${BuildConfig.VERSION_NAME}"
        )

        Spacer(Modifier.height(12.dp))

        InfoLine(label = "Установлено приложений", value = installedCount.toString())
        InfoLine(label = "Отслеживается", value = trackedCount.toString())
        InfoLine(label = "Версия", value = BuildConfig.VERSION_NAME)

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Версии берутся из выбранных магазинов. Для RuStore, APKPure, F-Droid и Aptoide UniStore может скачать APK, проверить подпись и открыть системный установщик. Для остальных магазинов открывается их приложение.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionTitle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
