package ru.updatehub.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.BrandMark
import ru.updatehub.app.ui.components.LocalAppViewModel
import ru.updatehub.app.ui.components.LocalSeasonalEffect
import ru.updatehub.app.ui.components.SeasonalForegroundOverlay
import ru.updatehub.app.ui.theme.LocalReduceMotion
import ru.updatehub.app.ui.theme.UpdateHubTheme

private object Routes {
    const val WELCOME = "welcome"
    const val MAIN = "main"
    const val ADD = "add"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "section"
    const val SETTINGS_ROUTE = "$SETTINGS?$SETTINGS_SECTION={$SETTINGS_SECTION}"

    fun settings(section: SettingsSection): String =
        "$SETTINGS?$SETTINGS_SECTION=${section.key}"
}

@Composable
fun UpdateHubRoot(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = state.settings.weakDevice
    val seasonalEffect = state.settings.seasonalTheme.resolve()
    val pointOrigin = remember { PointOriginState() }

    CompositionLocalProvider(
        LocalAppViewModel provides viewModel,
        LocalReduceMotion provides reduceMotion,
        LocalSeasonalEffect provides seasonalEffect,
        LocalPointOrigin provides pointOrigin
    ) {
        UpdateHubTheme(
            mode = state.settings.themeMode,
            palette = state.settings.palette,
            seasonal = seasonalEffect
        ) {
            LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }

            LaunchedEffect(Unit) {
                viewModel.snackbar.collect { message ->
                    snackbarHostState.showSnackbar(message)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                if (!state.settingsLoaded) {
                    BrandedSplash()
                } else {
                    val navController = rememberNavController()
                    val startDestination = if (state.settings.onboarded) {
                        Routes.MAIN
                    } else {
                        Routes.WELCOME
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged(pointOrigin::updateRootSize)
                    ) {
                        LaunchRevealHost {
                            NavHost(
                                navController = navController,
                                startDestination = startDestination,
                                enterTransition = {
                                    if (reduceMotion) {
                                        EnterTransition.None
                                    } else {
                                        fadeIn(tween(220)) + scaleIn(
                                            animationSpec = tween(
                                                340,
                                                easing = FastOutSlowInEasing
                                            ),
                                            initialScale = 0.82f,
                                            transformOrigin = pointOrigin.transformOrigin()
                                        )
                                    }
                                },
                                exitTransition = {
                                    if (reduceMotion) {
                                        ExitTransition.None
                                    } else {
                                        fadeOut(tween(140)) + scaleOut(
                                            animationSpec = tween(
                                                220,
                                                easing = FastOutSlowInEasing
                                            ),
                                            targetScale = 0.97f,
                                            transformOrigin = pointOrigin.transformOrigin()
                                        )
                                    }
                                },
                                popEnterTransition = {
                                    if (reduceMotion) {
                                        EnterTransition.None
                                    } else {
                                        fadeIn(tween(200)) + scaleIn(
                                            animationSpec = tween(
                                                320,
                                                easing = FastOutSlowInEasing
                                            ),
                                            initialScale = 0.86f,
                                            transformOrigin = pointOrigin.transformOrigin()
                                        )
                                    }
                                },
                                popExitTransition = {
                                    if (reduceMotion) {
                                        ExitTransition.None
                                    } else {
                                        fadeOut(tween(150)) + scaleOut(
                                            animationSpec = tween(
                                                240,
                                                easing = FastOutSlowInEasing
                                            ),
                                            targetScale = 0.92f,
                                            transformOrigin = pointOrigin.transformOrigin()
                                        )
                                    }
                                }
                            ) {
                                composable(Routes.WELCOME) {
                                    WelcomeScreen(
                                        state = state,
                                        onToggleSource = viewModel::toggleSource,
                                        onStart = {
                                            viewModel.completeOnboarding()
                                            navController.navigate(Routes.MAIN) {
                                                popUpTo(Routes.WELCOME) { inclusive = true }
                                            }
                                        }
                                    )
                                }

                                composable(Routes.MAIN) {
                                    MainScreen(
                                        state = state,
                                        onCheck = viewModel::checkUpdates,
                                        onAdd = { navController.navigate(Routes.ADD) },
                                        onSettings = { section ->
                                            navController.navigate(Routes.settings(section))
                                        },
                                        onMessage = viewModel::showMessage,
                                        onRemove = viewModel::removePackage,
                                        onSetFilter = viewModel::setFilter,
                                        onRetry = viewModel::checkUpdates,
                                        onUpdate = viewModel::updateApp,
                                        onOpenStore = viewModel::openInStore,
                                        onCancelInstall = viewModel::cancelInstall,
                                        onUpdateAll = viewModel::updateAll,
                                        onCancelBulk = viewModel::cancelBulkUpdate,
                                        onDismissStoreRedirect = viewModel::dismissStoreRedirect,
                                        onOpenStoreRedirect = viewModel::openStoreRedirect
                                    )
                                }

                                composable(Routes.ADD) {
                                    AddAppsScreen(
                                        state = state,
                                        onBack = { navController.popBackStack() },
                                        onSearch = viewModel::setSearch,
                                        onShowSystem = viewModel::setShowSystemApps,
                                        onApply = { add, remove ->
                                            viewModel.applyTrackedChanges(add, remove)
                                            navController.popBackStack()
                                        }
                                    )
                                }

                                composable(
                                    route = Routes.SETTINGS_ROUTE,
                                    arguments = listOf(
                                        navArgument(Routes.SETTINGS_SECTION) {
                                            type = NavType.StringType
                                            defaultValue = SettingsSection.TOP.key
                                        }
                                    )
                                ) { entry ->
                                    SettingsScreen(
                                        state = state,
                                        onBack = { navController.popBackStack() },
                                        onThemeMode = viewModel::setThemeMode,
                                        onPalette = viewModel::setPalette,
                                        onSeasonalTheme = viewModel::setSeasonalTheme,
                                        onWeakDevice = viewModel::setWeakDevice,
                                        onToggleSource = viewModel::toggleSource,
                                        onRefreshStoreHealth = { viewModel.refreshStoreHealth() },
                                        onOpenManualStore = viewModel::openManualStoreUpdateCenter,
                                        onAutoCheck = viewModel::setAutoCheck,
                                        onInterval = viewModel::setIntervalHours,
                                        onCheckSystemApps = viewModel::setCheckSystemApps,
                                        onCheck = viewModel::checkUpdates,
                                        onManageApps = { navController.navigate(Routes.ADD) },
                                        onMessage = viewModel::showMessage,
                                        initialSection = SettingsSection.fromKey(
                                            entry.arguments?.getString(Routes.SETTINGS_SECTION)
                                        )
                                    )
                                }
                            }
                        }

                        SeasonalForegroundOverlay(modifier = Modifier.fillMaxSize())

                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandedSplash() {
    val scale = if (LocalReduceMotion.current) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "splash")
        val animated by transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "splashScale"
        )
        animated
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 1.3f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandMark(
                markSize = 96.dp,
                modifier = Modifier.scale(scale)
            )
            Text(
                text = "UniStore",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 22.dp)
            )
            Text(
                text = "Все обновления в одном месте",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 28.dp)
                    .size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
