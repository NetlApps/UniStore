package ru.updatehub.app.ui

import android.app.Application
import android.os.PowerManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.updatehub.app.UpdateHubApp
import ru.updatehub.app.data.AppSettings
import ru.updatehub.app.data.AppUpdateResult
import ru.updatehub.app.data.DeviceProfile
import ru.updatehub.app.data.InstalledApp
import ru.updatehub.app.data.KnownStores
import ru.updatehub.app.data.ManualStore
import ru.updatehub.app.data.Palette
import ru.updatehub.app.data.ResultSerializer
import ru.updatehub.app.data.SeasonalTheme
import ru.updatehub.app.data.StoreHealth
import ru.updatehub.app.data.StoreLauncher
import ru.updatehub.app.data.StoreVersion
import ru.updatehub.app.data.ThemeMode
import ru.updatehub.app.data.UNISTORE_PACKAGE
import ru.updatehub.app.data.UpdateInstallState
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.data.UpdateStatus
import ru.updatehub.app.data.VersionComparator
import ru.updatehub.app.data.VersionRules
import ru.updatehub.app.data.checkers.CheckOutcome
import ru.updatehub.app.data.install.ApkDownloader
import ru.updatehub.app.data.install.ApkInstallEvent
import ru.updatehub.app.data.install.ApkInstallEvents
import ru.updatehub.app.data.install.ApkInstaller
import ru.updatehub.app.data.install.ApkVerification
import ru.updatehub.app.data.install.DirectApkResolver
import ru.updatehub.app.data.install.DownloadedApkBundle
import ru.updatehub.app.data.install.deleteDownloadedPackage
import ru.updatehub.app.data.incompatibleUpdateKey
import ru.updatehub.app.notifications.UpdateScheduler

enum class AppFilter {
    ALL,
    UPDATES,
    ACTUAL
}

data class CheckUiState(
    val isChecking: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentApp: String? = null
)

data class BulkUpdateState(
    val isRunning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentPackage: String? = null,
    val currentLabel: String? = null
)

/**
 * A store that cannot hand UniStore an APK. The UI asks for confirmation
 * before opening the store. For a single app the store page of that app is
 * opened, so the user lands on the build they were just offered; a bulk
 * redirect has no single app and falls back to the store's update section.
 */
data class StoreRedirectRequest(
    val sources: List<UpdateSource>,
    val appCount: Int,
    val packageName: String? = null
)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val settingsLoaded: Boolean = false,
    val installed: List<InstalledApp> = emptyList(),
    val loadingApps: Boolean = true,
    val results: Map<String, AppUpdateResult> = emptyMap(),
    val check: CheckUiState = CheckUiState(),
    val bulk: BulkUpdateState = BulkUpdateState(),
    val storeRedirect: StoreRedirectRequest? = null,
    val filter: AppFilter = AppFilter.ALL,
    val search: String = "",
    val showSystemApps: Boolean = false,
    val deviceProfile: DeviceProfile = DeviceProfile.empty(),
    val installStates: Map<String, UpdateInstallState> = emptyMap(),
    val storeHealth: Map<UpdateSource, StoreHealth> = emptyMap(),
    /** A VPN is running, but UniStore itself is not routed through it. */
    val vpnBypassed: Boolean = false
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as UpdateHubApp).container
    private val settingsRepository = container.settingsRepository

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbar = _snackbar.asSharedFlow()

    private val directApkResolver: DirectApkResolver = container.directApkResolver
    private val apkDownloader: ApkDownloader = container.apkDownloader
    private val apkInstaller: ApkInstaller = container.apkInstaller
    private val installJobs = mutableMapOf<String, Job>()
    private val launchedBundles = linkedSetOf<DownloadedApkBundle>()
    private var pendingInstall: PendingInstall? = null

    private data class PendingInstall(
        val packageName: String,
        val versionName: String,
        val bundle: DownloadedApkBundle
    )

    private data class PreparedInstall(
        val packageName: String,
        val versionName: String,
        val label: String,
        val bundle: DownloadedApkBundle
    )

    private data class BulkTarget(
        val app: InstalledApp,
        val version: StoreVersion
    )

    private val bulkQueue = ArrayDeque<BulkTarget>()
    private var bulkTotal = 0
    private var bulkDone = 0
    private var bulkStoreSources = linkedSetOf<UpdateSource>()
    private var bulkStoreCount = 0

    // Downloads run in parallel (2–3 at once). The system installer, on the
    // other hand, can show only one confirmation window, so verified bundles
    // wait in [preparedInstalls] and are handed to Android one by one.
    private val bulkDownloadJobs = mutableMapOf<String, Job>()
    private val bulkDownloadsInFlight = linkedSetOf<String>()
    private val preparedInstalls = ArrayDeque<PreparedInstall>()
    private var bulkInstallingPackage: String? = null
    private var bulkPermissionRequested = false

    private val bulkDownloadLimit: Int
        get() = if (_uiState.value.settings.weakDevice) 2 else 3

    private var storeHealthJob: Job? = null
    private var lastStoreHealthAt = 0L

    private var lastLoadedCheckAt = 0L
    private var autoCheckStarted = false
    private var checkJob: Job? = null
    private var scanJob: Job? = null
    private val signatureMutex = Mutex()

    init {
        // PackageInstaller reports the result of a split-APK session through a
        // broadcast. The receiver stores failures and deletes files; the live
        // screen needs the same event to leave the "Установка" state.
        viewModelScope.launch {
            ApkInstallEvents.events.collect { event -> handleInstallEvent(event) }
        }

        // Settings must be shown as early as possible. Device detection runs
        // `getprop` and several package lookups, so keeping it on the same
        // coroutine used to delay the whole first frame after a cold start.
        refreshNetworkProfile()

        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                container.iconLoader.setWeakDevice(settings.weakDevice)
                container.updateRepository.setWeakDevice(settings.weakDevice)
                val incomingResults =
                    if (settings.lastCheckAt > lastLoadedCheckAt && settings.resultsJson.isNotBlank()) {
                        lastLoadedCheckAt = settings.lastCheckAt
                        // The JSON can contain hundreds of results. Parsing it
                        // on the main thread delayed the first frame on a cold
                        // start with a large tracked list.
                        withContext(Dispatchers.Default) {
                            ResultSerializer.deserialize(settings.resultsJson)
                        }
                    } else {
                        null
                    }

                _uiState.update { state ->
                    val results = incomingResults ?: state.results
                    state.copy(
                        settings = settings,
                        settingsLoaded = true,
                        results = reconcile(
                            results,
                            state.installed.associateBy { it.packageName },
                            settings.incompatibleUpdates
                        )
                    )
                }
                pruneUnavailableTracking()
                requestTrackedSignatures()
                maybeStartAutoCheck()
                if (lastStoreHealthAt == 0L && checkJob?.isActive != true) {
                    refreshStoreHealth()
                }
            }
        }

        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { container.deviceProfileDetector.detect() }
                    .getOrDefault(DeviceProfile.empty())
            }
            _uiState.update { it.copy(deviceProfile = profile) }
            if (profile.defaultSources.isNotEmpty()) {
                runCatching {
                    settingsRepository.initializeDefaultSources(profile.defaultSourceKeys)
                }
            }
        }

        refreshInstalledApps(showLoading = true)
    }

    fun refreshInstalledApps(showLoading: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            if (showLoading) _uiState.update { it.copy(loadingApps = true) }
            val scan = runCatching {
                container.appScanner.scan(includeSystem = true)
            }
            val apps = scan.getOrDefault(emptyList())
            val scanSucceeded = scan.isSuccess

            val before = _uiState.value
            val scannedNames = apps.mapTo(HashSet()) { it.packageName }
            val missingTracked = before.settings.trackedPackages - scannedNames
            // Background components have no launcher and no MAIN activity.
            // They are not apps, so they never belong to the update list even
            // when a vendor installs them without a system flag.
            val componentPackages = container.appScanner
                .componentPackagesAmong(missingTracked)
            val systemPackages = apps.asSequence()
                .filter { it.isSystem }
                .map { it.packageName }
                .toHashSet()
            // The scan intentionally omits system apps without a launcher.
            // Look up the remaining tracked names so an old hidden entry can
            // not survive the system-app switch.
            systemPackages += container.appScanner.systemPackagesAmong(
                missingTracked - componentPackages
            )
            val hiddenSystem = if (before.settings.checkSystemApps) {
                emptySet()
            } else {
                systemPackages
            }
            // Components are pruned regardless of the system-app switch, so
            // their stale results must not survive either.
            val cleanedResults = before.results - hiddenSystem - componentPackages

            _uiState.update { state ->
                state.copy(
                    installed = apps,
                    loadingApps = false,
                    results = reconcile(
                        cleanedResults,
                        apps.associateBy { it.packageName },
                        before.settings.incompatibleUpdates
                    )
                )
            }

            // The old build let system packages slip into the tracked set
            // through "Выбрать все". Remove them permanently when the system
            // apps switch is off so they cannot reappear in settings, checks
            // or bulk updates. The same pass drops apps that were uninstalled
            // and store apps, which are managed from Settings.
            if (scanSucceeded) pruneUnavailableTracking()

            requestTrackedSignatures()
            maybeStartAutoCheck()
        }
    }

    /**
     * Starts the first update check of the process as soon as the settings and
     * the installed app list are ready. The user should not have to tap
     * "Проверить" after every launch. One check per process is enough:
     * configuration changes and returning from other screens must not restart
     * network requests or spam the user with result messages.
     */
    private fun maybeStartAutoCheck() {
        if (autoCheckStarted) return
        val state = _uiState.value
        if (!state.settingsLoaded || state.loadingApps) return
        if (!state.settings.onboarded) return
        if (state.installed.isEmpty()) return
        if (state.settings.trackedPackages.isEmpty()) return
        if (state.settings.enabledSources.isEmpty()) return
        if (state.check.isChecking || state.bulk.isRunning) return

        autoCheckStarted = true
        checkUpdates()
    }

    /**
     * Removes tracked entries that are no longer installed, plus store apps
     * that older builds allowed into the list. Called after every successful
     * scan and once settings arrive, so an app uninstalled by the user
     * disappears without manual cleanup.
     *
     * Background components (packages without a MAIN activity) are pruned
     * permanently: they cannot be opened, so they are not update candidates
     * on any vendor's firmware. System apps are handled separately: they are
     * hidden only while the system-app switch is off.
     */
    private suspend fun pruneUnavailableTracking() {
        val state = _uiState.value
        if (state.installed.isEmpty()) return

        val scannedNames = state.installed.mapTo(HashSet()) { it.packageName }
        val missingTracked = state.settings.trackedPackages - scannedNames
        val componentPackages = container.appScanner
            .componentPackagesAmong(missingTracked)
        val systemPackages = state.installed.asSequence()
            .filter { it.isSystem }
            .map { it.packageName }
            .toHashSet()
        // The scan skips system apps without a launcher, but a tracked name
        // may still be installed. Keep such packages out of the "missing" set.
        systemPackages += container.appScanner.systemPackagesAmong(
            missingTracked - componentPackages
        )

        val storePackages = state.settings.trackedPackages intersect KnownStores.packages
        val unavailable = state.settings.trackedPackages
            .minus(scannedNames)
            .minus(systemPackages)
            .plus(componentPackages)
            .plus(storePackages)
            .plus(UNISTORE_PACKAGE)

        if (unavailable.isNotEmpty()) {
            settingsRepository.pruneMissingPackages(unavailable)
            _uiState.update { it.copy(results = it.results - unavailable) }
        }

        if (!state.settings.checkSystemApps && systemPackages.isNotEmpty()) {
            settingsRepository.pruneTrackedPackages(systemPackages)
            settingsRepository.pruneResultPackages(systemPackages)
            _uiState.update { it.copy(results = it.results - systemPackages) }
        }
    }

    /**
     * Fingerprints are only needed for tracked apps. Loading them after the
     * basic app list is already visible keeps a cold start responsive, while
     * the slower certificate query continues in the background.
     */
    private fun ensureSignatures(packageNames: Set<String>) {
        if (packageNames.isEmpty()) return
        val state = _uiState.value
        if (state.installed.isEmpty()) return
        val installed = state.installed.associateBy { it.packageName }
        val targets = packageNames.filterTo(HashSet()) { packageName ->
            val app = installed[packageName] ?: return@filterTo false
            if (app.isSystem && !state.settings.checkSystemApps) return@filterTo false
            app.signature == null && app.signatureSha1 == null
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            signatureMutex.withLock {
                val current = _uiState.value
                val currentInstalled = current.installed.associateBy { it.packageName }
                val missing = targets.filter { packageName ->
                    val app = currentInstalled[packageName] ?: return@filter false
                    app.signature == null && app.signatureSha1 == null
                }.toSet()
                if (missing.isEmpty()) return@withLock

                val loaded = runCatching {
                    container.appScanner.signatures(missing)
                }.getOrDefault(emptyMap())
                if (loaded.isEmpty()) return@withLock

                _uiState.update { latest ->
                    latest.copy(
                        installed = latest.installed.map { app ->
                            val fingerprints = loaded[app.packageName] ?: return@map app
                            app.copy(
                                signature = fingerprints.first ?: app.signature,
                                signatureSha1 = fingerprints.second ?: app.signatureSha1
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * Returns the installed map with fingerprints for the packages that are
     * about to be checked. The background prefetch usually did this already,
     * but a manual check right after a cold start must not race it.
     */
    private suspend fun withTrackedSignatures(
        packageNames: Set<String>,
        installed: Map<String, InstalledApp>
    ): Map<String, InstalledApp> {
        val missing = packageNames.filterTo(HashSet()) { packageName ->
            val app = installed[packageName] ?: return@filterTo false
            app.signature == null && app.signatureSha1 == null
        }
        if (missing.isEmpty()) return installed

        val loaded = signatureMutex.withLock {
            runCatching { container.appScanner.signatures(missing) }
                .getOrDefault(emptyMap())
        }
        if (loaded.isEmpty()) return installed

        val updated = installed.mapValues { (packageName, app) ->
            val fingerprints = loaded[packageName] ?: return@mapValues app
            app.copy(
                signature = fingerprints.first ?: app.signature,
                signatureSha1 = fingerprints.second ?: app.signatureSha1
            )
        }
        _uiState.update { latest ->
            latest.copy(
                installed = latest.installed.map { app ->
                    updated[app.packageName] ?: app
                }
            )
        }
        return updated
    }

    private fun requestTrackedSignatures() {
        ensureSignatures(_uiState.value.settings.trackedPackages)
    }

    fun onResume() {
        // The system installer has closed, so the files handed to
        // PackageInstaller are no longer needed. The receiver also removes
        // them, but this covers a cancelled install or a missed broadcast.
        cleanupLaunchedBundles()

        // Returning from the system installer means the previous attempt is
        // over. Finished rows are cleared; a bulk install keeps its state
        // until the receiver reports, otherwise the queue could jump to the
        // next application while the current dialog is still open.
        _uiState.update { state ->
            val finished = state.installStates
                .filterValues {
                    it is UpdateInstallState.Completed ||
                        (it is UpdateInstallState.Installing && !state.bulk.isRunning)
                }
                .keys
            if (finished.isEmpty()) state else state.copy(installStates = state.installStates - finished)
        }

        // A download that waited for the "install unknown apps" permission is
        // resumed after the old attempt state was cleared, so it keeps its new
        // "Установка" state instead of looking like a failed bulk item.
        pendingInstall?.let { pending ->
            if (apkInstaller.canInstall()) {
                pendingInstall = null
                launchInstall(pending)
            }
        }

        resumeBulkAfterResume()
        refreshNetworkProfile()
        refreshStoreHealth()
        refreshInstalledApps(showLoading = false)
    }

    /**
     * Keeps the request budget in sync with the current link and records
     * whether a running VPN is bypassing UniStore. Android does not let one
     * app join another app's VPN; the UI explains that checks are going
     * through the regular network instead of pretending the tunnel is used.
     */
    private fun refreshNetworkProfile() {
        val snapshot = container.networkMonitor.snapshot()
        container.updateRepository.setMeteredNetwork(snapshot.metered)
        _uiState.update { it.copy(vpnBypassed = snapshot.vpnBypassed) }
        if (snapshot.vpnBypassed) {
            Log.i(
                "UpdateHub",
                "VPN активен, но UniStore использует обычную сеть: " +
                    "проверки идут напрямую"
            )
        }
    }

    /**
     * Re-measures store latency in the background. Called on launch, when the
     * settings screen opens and when the app returns to the foreground, but
     * never more than once a minute unless [force] is set.
     */
    fun refreshStoreHealth(force: Boolean = false) {
        // Health probes are two HEAD requests per store. Running them while
        // an update check is downloading Play pages made both slower and was
        // the main reason a healthy mobile link looked broken.
        if (checkJob?.isActive == true || _uiState.value.check.isChecking) return
        val now = System.currentTimeMillis()
        if (!force && now - lastStoreHealthAt < 60_000L) return
        if (storeHealthJob?.isActive == true) return
        val sources = UpdateSource.fromKeys(_uiState.value.settings.enabledSources)
        if (sources.isEmpty()) return
        lastStoreHealthAt = now
        storeHealthJob = viewModelScope.launch {
            val probed = runCatching {
                container.updateRepository.probeStores(sources)
            }.getOrDefault(emptyMap())
            if (probed.isNotEmpty()) {
                _uiState.update { it.copy(storeHealth = it.storeHealth + probed) }
            }
        }
    }

    private fun launchInstall(pending: PendingInstall) {
        setInstallState(pending.packageName, UpdateInstallState.Installing)
        launchedBundles += pending.bundle
        viewModelScope.launch {
            if (!apkInstaller.install(pending.bundle, pending.versionName)) {
                launchedBundles -= pending.bundle
                pending.bundle.delete()
                setInstallState(
                    pending.packageName,
                    UpdateInstallState.Failed("Не удалось запустить установку")
                )
            }
        }
    }

    private fun cleanupLaunchedBundles() {
        if (launchedBundles.isEmpty()) return
        launchedBundles.forEach { it.delete() }
        launchedBundles.clear()
    }

    private fun handleInstallEvent(event: ApkInstallEvent) {
        launchedBundles.removeAll { it.packageName == event.packageName }
        val label = _uiState.value.installed
            .firstOrNull { it.packageName == event.packageName }
            ?.label
            ?: event.packageName

        if (event.success) {
            setInstallState(event.packageName, UpdateInstallState.Completed)
            showMessage("$label обновлено")
            if (bulkInstallingPackage == event.packageName) {
                advanceBulkInstall(event.packageName)
            }
            refreshInstalledApps(showLoading = false)
            return
        }

        val message = event.message?.takeIf { it.isNotBlank() }
            ?: "Не удалось установить обновление"
        setInstallState(event.packageName, UpdateInstallState.Failed(message))
        showMessage(message)

        if (event.incompatible) {
            markResultIncompatible(event.packageName, message, event.versionName)
        }
        if (bulkInstallingPackage == event.packageName) {
            advanceBulkInstall(event.packageName)
        }
    }

    fun updateApp(packageName: String) {
        val state = _uiState.value
        if (state.bulk.isRunning) {
            showMessage("Дождитесь окончания массового обновления")
            return
        }
        val app = state.installed.firstOrNull { it.packageName == packageName }
        if (app == null) {
            showMessage("Приложение не установлено")
            return
        }

        val pending = pendingInstall
        if (pending != null && pending.packageName == packageName) {
            if (apkInstaller.canInstall()) {
                pendingInstall = null
                launchInstall(pending)
            } else {
                showMessage("Разрешите установку приложений из UniStore")
                apkInstaller.openInstallPermissionSettings()
            }
            return
        }

        val current = state.installStates[packageName]
        if (current is UpdateInstallState.Resolving ||
            current is UpdateInstallState.Downloading ||
            current is UpdateInstallState.Verifying ||
            current is UpdateInstallState.Installing
        ) {
            return
        }

        val result = state.results[packageName]
        if (result == null) {
            showMessage("Сначала проверьте обновления")
            return
        }
        if (result.status == UpdateStatus.INCOMPATIBLE) {
            showMessage(
                result.message ?: "Версия несовместима с этим телефоном"
            )
            return
        }

        val direct = chooseDirectSource(result, app)
        if (direct != null) {
            startInstall(app, direct, result)
            return
        }

        val fallback = result.best
            ?.takeIf { VersionRules.isNewer(it, app) }
            ?: result.versions.firstOrNull { VersionRules.isNewer(it, app) }
        if (fallback != null) {
            verifyStoreAndRedirect(app, fallback)
        } else {
            showMessage("Установлена последняя версия")
        }
    }

    /**
     * Stores that cannot hand us an APK are checked once more right before the
     * redirect. Cached metadata can be stale, an app can disappear from a
     * catalog, or the listing can already contain an older build. The extra
     * request keeps the user from landing on an empty store page. If the store
     * is too slow to answer, the redirect is still offered because its own
     * update screen will show the real state.
     */
    private fun verifyStoreAndRedirect(app: InstalledApp, version: StoreVersion) {
        val packageName = app.packageName
        viewModelScope.launch {
            setInstallState(packageName, UpdateInstallState.Resolving)
            val outcome = withTimeoutOrNull(12_000) {
                runCatching {
                    container.updateRepository.verify(version.source, packageName)
                }.getOrElse { throwable ->
                    CheckOutcome.Failed(
                        throwable.message ?: "Не удалось проверить магазин",
                        transient = true
                    )
                }
            }
            _uiState.update { current ->
                current.copy(installStates = current.installStates - packageName)
            }

            val found = outcome as? CheckOutcome.Found
            if (found != null) {
                val verified = version.copy(
                    versionName = found.versionName.trim(),
                    versionCode = found.versionCode,
                    title = found.title ?: version.title,
                    signature = found.signature ?: version.signature,
                    signatureSha1 = found.signatureSha1 ?: version.signatureSha1
                )
                if (VersionRules.isNewer(verified, app)) {
                    requestStoreRedirect(listOf(version.source), 1, packageName)
                } else {
                    markResultWithoutUpdate(
                        packageName,
                        "В магазине уже нет более новой версии"
                    )
                    showMessage("В ${version.source.title} нет более новой версии")
                }
                return@launch
            }

            if (outcome is CheckOutcome.NotFound) {
                markResultWithoutUpdate(
                    packageName,
                    "Приложение не найдено в ${version.source.title}"
                )
                showMessage("Приложение не найдено в ${version.source.title}")
                return@launch
            }

            showMessage("Магазин покажет актуальную версию при открытии")
            requestStoreRedirect(listOf(version.source), 1, packageName)
        }
    }

    private fun markResultWithoutUpdate(packageName: String, message: String) {
        _uiState.update { state ->
            val result = state.results[packageName] ?: return@update state
            state.copy(
                results = state.results + (
                    packageName to result.copy(
                        status = UpdateStatus.UP_TO_DATE,
                        best = null,
                        versions = emptyList(),
                        message = message
                    )
                    )
            )
        }
    }

    /**
     * Immediately hides the install button after a compatibility failure.
     * The same pair is saved in settings, so the status survives a restart and
     * future checks, and a newer store version is allowed through again.
     */
    private fun markResultIncompatible(
        packageName: String,
        message: String,
        versionName: String? = null
    ) {
        _uiState.update { state ->
            val result = state.results[packageName] ?: return@update state
            state.copy(
                results = state.results + (
                    packageName to result.copy(
                        status = UpdateStatus.INCOMPATIBLE,
                        versions = result.versions.filterNot { version ->
                            version.versionName == versionName ||
                                incompatibleUpdateKey(
                                    packageName,
                                    version.versionName
                                ) in state.settings.incompatibleUpdates
                        },
                        message = message
                    )
                    )
            )
        }
    }

    fun openInStore(packageName: String, source: UpdateSource) {
        if (!StoreLauncher.open(getApplication(), packageName, source)) {
            showMessage("Не удалось открыть ${source.title}")
        }
    }

    fun dismissStoreRedirect() {
        _uiState.update { it.copy(storeRedirect = null) }
    }

    fun openStoreRedirect(source: UpdateSource) {
        // Read the request before dismissing it: a single-app redirect knows
        // which app page to open, a bulk redirect only knows the stores.
        val packageName = _uiState.value.storeRedirect?.packageName
        _uiState.update { it.copy(storeRedirect = null) }
        val opened = if (packageName != null) {
            StoreLauncher.open(getApplication(), packageName, source) ||
                StoreLauncher.openUpdateCenter(getApplication(), source)
        } else {
            StoreLauncher.openUpdateCenter(getApplication(), source)
        }
        if (!opened) {
            showMessage("Не удалось открыть ${source.title}")
        }
    }

    fun openManualStoreUpdateCenter(store: ManualStore) {
        if (!StoreLauncher.openUpdateCenter(getApplication(), store)) {
            showMessage("Не удалось открыть ${store.title}")
        }
    }

    private fun requestStoreRedirect(
        sources: List<UpdateSource>,
        appCount: Int,
        packageName: String? = null
    ) {
        if (sources.isEmpty()) return
        _uiState.update {
            it.copy(
                storeRedirect = StoreRedirectRequest(
                    sources = sources.distinct(),
                    appCount = appCount.coerceAtLeast(1),
                    packageName = packageName
                )
            )
        }
    }

    private fun chooseDirectSource(result: AppUpdateResult, app: InstalledApp): StoreVersion? =
        result.versions
            .filter { it.source.supportsDirectDownload }
            .filter { VersionRules.isNewer(it, app) }
            .maxWithOrNull { first, second ->
                val byVersion = VersionComparator.compare(first.versionName, second.versionName)
                if (byVersion != 0) {
                    byVersion
                } else {
                    UpdateSource.entries.indexOf(second.source) -
                        UpdateSource.entries.indexOf(first.source)
                }
            }

    private fun startInstall(
        app: InstalledApp,
        version: StoreVersion,
        result: AppUpdateResult? = null
    ) {
        val packageName = app.packageName
        installJobs.remove(packageName)?.cancel()

        val job = viewModelScope.launch {
            setInstallState(packageName, UpdateInstallState.Resolving)
            try {
                val bundle = downloadAndVerify(app, version)

                if (!apkInstaller.canInstall()) {
                    pendingInstall = PendingInstall(
                        packageName = packageName,
                        versionName = version.versionName,
                        bundle = bundle
                    )
                    setInstallState(
                        packageName,
                        UpdateInstallState.Failed("Разрешите установку из UniStore")
                    )
                    showMessage("Разрешите установку приложений из UniStore")
                    apkInstaller.openInstallPermissionSettings()
                    return@launch
                }

                setInstallState(packageName, UpdateInstallState.Installing)
                launchedBundles += bundle
                if (!apkInstaller.install(bundle, version.versionName)) {
                    launchedBundles -= bundle
                    bundle.delete()
                    error("Не удалось запустить установку")
                }
                showMessage("Подтвердите установку в системном окне")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val message = throwable.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "Не удалось скачать обновление"
                setInstallState(packageName, UpdateInstallState.Failed(message))
                showMessage(message)

                // A direct source can hold a stale or fake listing for this
                // package (for example, a same-name app with a 404 download
                // link). When the update itself was reported by another
                // store, offer that store instead of leaving a dead end.
                val store = result?.best
                    ?.takeIf {
                        it.source != version.source && VersionRules.isNewer(it, app)
                    }
                if (store != null) {
                    verifyStoreAndRedirect(app, store)
                }
            } finally {
                installJobs.remove(packageName)
            }
        }
        installJobs[packageName] = job
    }

    /**
     * Resolves the download link, downloads the APK/XAPK and verifies the
     * package, version, signature and device compatibility. Shared by the
     * single-app flow and the parallel bulk downloader.
     */
    private suspend fun downloadAndVerify(
        app: InstalledApp,
        version: StoreVersion
    ): DownloadedApkBundle {
        val packageName = app.packageName
        // Never download and verify an APK without the installed
        // certificate. On a cold start signatures are loaded in the
        // background; fetch this one synchronously if it is missing.
        val installed = if (app.signature == null && app.signatureSha1 == null) {
            val hashes = container.appScanner
                .signatures(setOf(packageName))[packageName]
            if (hashes != null) {
                app.copy(
                    signature = hashes.first ?: app.signature,
                    signatureSha1 = hashes.second ?: app.signatureSha1
                )
            } else {
                app
            }
        } else {
            app
        }

        val spec = directApkResolver.resolve(version.source, packageName)
        setInstallState(packageName, UpdateInstallState.Downloading(0, 0))
        val weakDevice = _uiState.value.settings.weakDevice
        val progressInterval = if (weakDevice) 500L else 200L
        val bundle = apkDownloader.download(
            spec = spec,
            packageName = packageName,
            progressIntervalMs = progressInterval,
            maxConnections = if (weakDevice) 2 else 4
        ) { downloaded, total ->
            setInstallState(
                packageName,
                UpdateInstallState.Downloading(downloaded, total)
            )
        }

        setInstallState(packageName, UpdateInstallState.Verifying)
        when (val verification = withContext(Dispatchers.IO) {
            apkInstaller.verify(bundle, installed)
        }) {
            ApkVerification.Valid -> Unit
            is ApkVerification.Incompatible -> {
                bundle.delete()
                settingsRepository.markUpdateIncompatible(
                    packageName,
                    version.versionName
                )
                markResultIncompatible(
                    packageName,
                    verification.message,
                    version.versionName
                )
                error(verification.message)
            }

            is ApkVerification.Failed -> {
                bundle.delete()
                if (verification.hideVersion) {
                    // The store serves a package Android can never install
                    // over this build: a different certificate, package or a
                    // stale version. Remember the exact version so it stops
                    // appearing as an update, while a newer version is still
                    // checked normally.
                    settingsRepository.markUpdateIncompatible(
                        packageName,
                        version.versionName
                    )
                    markResultIncompatible(
                        packageName,
                        verification.message,
                        version.versionName
                    )
                }
                error(verification.message)
            }
        }

        return bundle
    }

    private fun setInstallState(packageName: String, state: UpdateInstallState) {
        _uiState.update { current ->
            current.copy(installStates = current.installStates + (packageName to state))
        }
    }

    fun cancelInstall(packageName: String) {
        val current = _uiState.value.installStates[packageName]
        if (current is UpdateInstallState.Installing) {
            showMessage("Установку уже подтверждает системное окно")
            return
        }
        installJobs.remove(packageName)?.cancel()
        val pending = pendingInstall
        if (pending != null && pending.packageName == packageName) {
            pendingInstall = null
            pending.bundle.delete()
        }

        // The item may belong to the parallel bulk queue: cancel its download
        // or remove its already prepared bundle, then let the queue continue.
        val isBulkItem = bulkDownloadsInFlight.contains(packageName) ||
            preparedInstalls.any { it.packageName == packageName }
        bulkDownloadJobs.remove(packageName)?.cancel()
        bulkDownloadsInFlight -= packageName
        preparedInstalls.firstOrNull { it.packageName == packageName }?.let { prepared ->
            preparedInstalls.remove(prepared)
            prepared.bundle.delete()
        }
        deleteDownloadedPackage(getApplication(), packageName)
        _uiState.update {
            it.copy(installStates = it.installStates - packageName)
        }
        if (_uiState.value.bulk.isRunning && isBulkItem) {
            showMessage("Загрузка отменена — переходим к следующему приложению")
            bulkDone = (bulkDone + 1).coerceAtMost(bulkTotal)
            updateBulkProgress()
            pumpBulkInstalls()
            pumpBulkDownloads()
            finishBulkIfIdle()
        } else {
            showMessage("Загрузка отменена")
        }
    }

    /**
     * Updates every app that has a real newer version. Apps whose store does
     * not expose an APK are collected and offered as store update-center
     * redirects after the direct installs finish.
     */
    fun updateAll() {
        val state = _uiState.value
        if (state.check.isChecking) {
            showMessage("Дождитесь окончания проверки обновлений")
            return
        }
        if (state.bulk.isRunning) {
            showMessage("Массовое обновление уже идёт")
            return
        }
        if (state.installed.isEmpty()) {
            showMessage("Список приложений ещё загружается")
            return
        }

        val installed = state.installed.associateBy { it.packageName }
        val tracked = if (state.settings.checkSystemApps) {
            state.settings.trackedPackages
        } else {
            state.settings.trackedPackages
                .filter { installed[it]?.isSystem != true }
                .toSet()
        }
        val direct = mutableListOf<BulkTarget>()
        val storeOnly = linkedMapOf<UpdateSource, Int>()

        state.results.values.forEach { result ->
            if (result.packageName !in tracked) return@forEach
            if (result.status != UpdateStatus.UPDATE_AVAILABLE) return@forEach
            val app = installed[result.packageName] ?: return@forEach
            val best = result.best ?: return@forEach
            if (!VersionRules.isNewer(best, app)) return@forEach

            val directVersion = chooseDirectSource(result, app)
            if (directVersion != null) {
                direct += BulkTarget(app, directVersion)
            } else {
                storeOnly[best.source] = (storeOnly[best.source] ?: 0) + 1
            }
        }

        if (direct.isEmpty() && storeOnly.isEmpty()) {
            showMessage("Нет доступных обновлений")
            return
        }

        bulkQueue.clear()
        bulkQueue.addAll(direct.sortedBy { it.app.label.lowercase() })
        bulkTotal = bulkQueue.size
        bulkDone = 0
        bulkInstallingPackage = null
        bulkPermissionRequested = false
        bulkDownloadJobs.values.forEach { it.cancel() }
        bulkDownloadJobs.clear()
        bulkDownloadsInFlight.clear()
        preparedInstalls.forEach { it.bundle.delete() }
        preparedInstalls.clear()
        bulkStoreSources = LinkedHashSet(storeOnly.keys)
        bulkStoreCount = storeOnly.values.sum()

        if (bulkQueue.isEmpty()) {
            _uiState.update { it.copy(bulk = BulkUpdateState()) }
            requestStoreRedirect(bulkStoreSources.toList(), bulkStoreCount)
            return
        }

        _uiState.update {
            it.copy(
                bulk = BulkUpdateState(
                    isRunning = true,
                    done = 0,
                    total = bulkTotal,
                    currentPackage = bulkQueue.first().app.packageName,
                    currentLabel = bulkQueue.first().app.label
                )
            )
        }
        pumpBulkDownloads()
    }

    fun cancelBulkUpdate() {
        val state = _uiState.value
        if (!state.bulk.isRunning) return

        bulkDownloadJobs.values.forEach { it.cancel() }
        bulkDownloadJobs.clear()
        val downloading = bulkDownloadsInFlight.toSet()
        bulkDownloadsInFlight.clear()
        preparedInstalls.forEach { it.bundle.delete() }
        val preparedPackages = preparedInstalls.mapTo(HashSet()) { it.packageName }
        preparedInstalls.clear()
        pendingInstall?.let { pending ->
            if (pending.packageName in preparedPackages) {
                pendingInstall = null
                pending.bundle.delete()
            }
        }

        bulkQueue.clear()
        // An install that has already reached the system window cannot be
        // pulled back; its result is ignored because bulk is no longer active.
        bulkInstallingPackage = null
        bulkPermissionRequested = false
        bulkTotal = 0
        bulkDone = 0
        bulkStoreSources.clear()
        bulkStoreCount = 0
        _uiState.update {
            it.copy(
                bulk = BulkUpdateState(),
                installStates = it.installStates - downloading - preparedPackages
            )
        }
        showMessage("Массовое обновление отменено")
    }

    /**
     * Starts downloads until the parallel limit is reached. The queue is
     * refilled as soon as any download finishes, without waiting for the
     * system install confirmation of the previous application.
     */
    private fun pumpBulkDownloads() {
        val state = _uiState.value
        if (!state.bulk.isRunning) return
        while (bulkDownloadsInFlight.size < bulkDownloadLimit &&
            preparedInstalls.size < MAX_PREPARED_INSTALLS &&
            bulkQueue.isNotEmpty()
        ) {
            startBulkDownload(bulkQueue.removeFirst())
        }
        updateBulkProgress()
        finishBulkIfIdle()
    }

    private fun startBulkDownload(target: BulkTarget) {
        val packageName = target.app.packageName
        bulkDownloadsInFlight += packageName
        setInstallState(packageName, UpdateInstallState.Resolving)
        val job = viewModelScope.launch {
            var prepared: PreparedInstall? = null
            try {
                val bundle = downloadAndVerify(target.app, target.version)
                prepared = PreparedInstall(
                    packageName = packageName,
                    versionName = target.version.versionName,
                    label = target.app.label,
                    bundle = bundle
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val message = throwable.message
                    ?.takeIf { it.isNotBlank() }
                    ?: "Не удалось скачать обновление"
                setInstallState(packageName, UpdateInstallState.Failed(message))
                showMessage(message)
                bulkDone = (bulkDone + 1).coerceAtMost(bulkTotal)
            } finally {
                bulkDownloadsInFlight -= packageName
                bulkDownloadJobs.remove(packageName)
            }
            if (prepared != null) {
                preparedInstalls.addLast(prepared)
                setInstallState(packageName, UpdateInstallState.Ready)
            }
            updateBulkProgress()
            pumpBulkInstalls()
            pumpBulkDownloads()
            finishBulkIfIdle()
        }
        bulkDownloadJobs[packageName] = job
    }

    /** Hands verified bundles to Android one at a time. */
    private fun pumpBulkInstalls() {
        val state = _uiState.value
        if (!state.bulk.isRunning || bulkInstallingPackage != null) return
        val next = preparedInstalls.firstOrNull() ?: return

        if (!apkInstaller.canInstall()) {
            if (!bulkPermissionRequested) {
                bulkPermissionRequested = true
                setInstallState(
                    next.packageName,
                    UpdateInstallState.Failed("Разрешите установку из UniStore")
                )
                showMessage("Разрешите установку приложений из UniStore")
                apkInstaller.openInstallPermissionSettings()
            }
            return
        }

        bulkPermissionRequested = false
        preparedInstalls.removeFirst()
        bulkInstallingPackage = next.packageName
        setInstallState(next.packageName, UpdateInstallState.Installing)
        launchedBundles += next.bundle
        viewModelScope.launch {
            if (!apkInstaller.install(next.bundle, next.versionName)) {
                launchedBundles -= next.bundle
                next.bundle.delete()
                setInstallState(
                    next.packageName,
                    UpdateInstallState.Failed("Не удалось запустить установку")
                )
                advanceBulkInstall(next.packageName)
            } else {
                showMessage("Подтвердите установку в системном окне")
            }
        }
    }

    private fun advanceBulkInstall(packageName: String) {
        if (bulkInstallingPackage != packageName) return
        bulkInstallingPackage = null
        bulkDone = (bulkDone + 1).coerceAtMost(bulkTotal)
        updateBulkProgress()
        pumpBulkInstalls()
        pumpBulkDownloads()
        finishBulkIfIdle()
    }

    private fun updateBulkProgress() {
        val state = _uiState.value
        if (!state.bulk.isRunning) return
        val activePackage = bulkInstallingPackage
            ?: bulkDownloadsInFlight.firstOrNull()
            ?: preparedInstalls.firstOrNull()?.packageName
            ?: bulkQueue.firstOrNull()?.app?.packageName
        val label = activePackage?.let { packageName ->
            state.installed.firstOrNull { it.packageName == packageName }?.label
                ?: preparedInstalls.firstOrNull { it.packageName == packageName }?.label
                ?: bulkQueue.firstOrNull { it.app.packageName == packageName }?.app?.label
                ?: packageName
        }
        _uiState.update {
            it.copy(
                bulk = it.bulk.copy(
                    done = bulkDone,
                    total = bulkTotal,
                    currentPackage = activePackage,
                    currentLabel = label
                )
            )
        }
    }

    /**
     * Resumes the pipeline after the user returns to UniStore. A finished
     * install (Completed/Failed/cleared by the receiver) advances the queue.
     * If the result broadcast was missed, a short grace period prevents the
     * queue from stalling forever without skipping a still-open dialog.
     */
    private fun resumeBulkAfterResume() {
        val state = _uiState.value
        if (!state.bulk.isRunning) return
        val active = bulkInstallingPackage

        if (active != null) {
            when (state.installStates[active]) {
                null, is UpdateInstallState.Completed, is UpdateInstallState.Failed ->
                    advanceBulkInstall(active)

                is UpdateInstallState.Installing -> viewModelScope.launch {
                    delay(1_500)
                    if (bulkInstallingPackage == active &&
                        _uiState.value.installStates[active] is UpdateInstallState.Installing
                    ) {
                        advanceBulkInstall(active)
                    }
                }

                else -> Unit
            }
        }

        bulkPermissionRequested = false
        updateBulkProgress()
        pumpBulkInstalls()
        pumpBulkDownloads()
        finishBulkIfIdle()
    }

    private fun finishBulkIfIdle() {
        val state = _uiState.value
        if (!state.bulk.isRunning) return
        if (bulkQueue.isNotEmpty() ||
            bulkDownloadsInFlight.isNotEmpty() ||
            preparedInstalls.isNotEmpty() ||
            bulkInstallingPackage != null
        ) {
            return
        }
        finishBulk()
    }

    private fun finishBulk() {
        val sources = bulkStoreSources.toList()
        val appCount = bulkStoreCount
        val hasStoreRedirect = sources.isNotEmpty()

        bulkQueue.clear()
        bulkDownloadJobs.clear()
        bulkDownloadsInFlight.clear()
        preparedInstalls.forEach { it.bundle.delete() }
        preparedInstalls.clear()
        bulkInstallingPackage = null
        bulkPermissionRequested = false
        bulkTotal = 0
        bulkDone = 0
        bulkStoreSources.clear()
        bulkStoreCount = 0

        _uiState.update {
            it.copy(
                bulk = BulkUpdateState(),
                storeRedirect = if (hasStoreRedirect) {
                    StoreRedirectRequest(sources, appCount.coerceAtLeast(1))
                } else {
                    it.storeRedirect
                }
            )
        }
        if (!hasStoreRedirect) {
            showMessage("Массовое обновление завершено")
        }
    }

    fun checkUpdates() {
        val state = _uiState.value
        if (state.check.isChecking) return
        if (state.bulk.isRunning) {
            showMessage("Дождитесь окончания массового обновления")
            return
        }

        if (state.settings.trackedPackages.isEmpty()) {
            showMessage("Сначала добавьте приложения")
            return
        }

        val sources = UpdateSource.fromKeys(state.settings.enabledSources).toSet()
        if (sources.isEmpty()) {
            showMessage("Включите хотя бы один источник обновлений")
            return
        }

        if (state.installed.isEmpty()) {
            showMessage("Список приложений ещё загружается")
            refreshInstalledApps(showLoading = true)
            return
        }

        val installed = state.installed.associateBy { it.packageName }
        val allTracked = state.settings.trackedPackages - UNISTORE_PACKAGE
        val tracked = if (state.settings.checkSystemApps) {
            allTracked
        } else {
            allTracked.filter { installed[it]?.isSystem != true }.toSet()
        }

        if (tracked.isEmpty()) {
            showMessage("Все отслеживаемые приложения системные. Включите их проверку в настройках.")
            return
        }

        checkJob?.cancel()
        // Free the link for the update pages; the indicator probes resume
        // after the check instead of competing with them.
        storeHealthJob?.cancel()
        refreshNetworkProfile()
        checkJob = viewModelScope.launch {
            // A full check takes about two minutes on LTE. Without a wake
            // lock HyperOS suspends the process as soon as the screen turns
            // off, and most store requests fail with UnknownHostException or
            // "connection abort". Hold the CPU until the check finishes; the
            // timeout is only a safety net if the coroutine is stuck.
            val wakeLock = getApplication<Application>()
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CHECK_WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire(CHECK_WAKE_LOCK_TIMEOUT_MS)
                }
            val startedAt = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    check = CheckUiState(
                        isChecking = true,
                        total = tracked.size
                    )
                )
            }

            try {
                // Ensure certificate fingerprints are present before the
                // check so a repackaged store build cannot be reported as a
                // valid update. Usually they were already loaded in the
                // background and this is a no-op.
                val signedInstalled = withTrackedSignatures(tracked, installed)
                val results = container.updateRepository.checkAll(
                    packages = tracked,
                    sources = sources,
                    installed = signedInstalled,
                    onProgress = { done, total, current ->
                        _uiState.update { currentState ->
                            currentState.copy(
                                check = currentState.check.copy(
                                    done = done,
                                    total = total,
                                    currentApp = current
                                )
                            )
                        }
                    },
                    onResult = { result ->
                        _uiState.update { currentState ->
                            val partial = reconcile(
                                mapOf(result.packageName to result),
                                signedInstalled,
                                currentState.settings.incompatibleUpdates
                            )
                            currentState.copy(results = currentState.results + partial)
                        }
                    },
                    onSourceUnavailable = { source ->
                        showMessage("${source.title} временно недоступен — проверка продолжена без него")
                    }
                )

                val reconciled = reconcile(
                    results,
                    installed,
                    _uiState.value.settings.incompatibleUpdates
                )
                val timestamp = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        results = reconciled,
                        check = CheckUiState()
                    )
                }
                settingsRepository.saveCheckResults(
                    timestamp = timestamp,
                    json = ResultSerializer.serialize(reconciled.values)
                )
                lastLoadedCheckAt = timestamp

                android.util.Log.i(
                    "UpdateHub",
                    "Проверка завершена за ${System.currentTimeMillis() - startedAt} мс: " +
                        "приложений ${tracked.size}, источников ${sources.size}"
                )

                val updates = reconciled.values.count {
                    it.status == UpdateStatus.UPDATE_AVAILABLE
                }
                val errors = reconciled.values.count { it.status == UpdateStatus.ERROR }
                when {
                    updates > 0 -> showMessage(
                        if (updates == 1) "Найдено 1 обновление"
                        else "Найдено обновлений: $updates"
                    )

                    errors > 0 -> showMessage("Не удалось проверить часть приложений")
                    else -> showMessage("Все приложения актуальны")
                }

                // Refresh the connection dots only after the last store page
                // has finished. A short delay also lets the result list settle
                // before the health requests start.
                viewModelScope.launch {
                    delay(1_200)
                    refreshStoreHealth(force = true)
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(check = CheckUiState()) }
                throw cancelled
            } catch (throwable: Throwable) {
                _uiState.update { it.copy(check = CheckUiState()) }
                showMessage("Ошибка проверки: ${throwable.message ?: "неизвестная ошибка"}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /**
     * Applies a batch of additions and removals in one DataStore write. The
     * management screen edits a local draft, so both halves of the update must
     * land atomically: two separate writes could overwrite each other when the
     * user confirms quickly.
     */
    fun applyTrackedChanges(packagesToAdd: Set<String>, packagesToRemove: Set<String>) {
        if (packagesToAdd.isEmpty() && packagesToRemove.isEmpty()) return
        val state = _uiState.value
        val installed = state.installed.associateBy { it.packageName }
        val requestedAdd = packagesToAdd - UNISTORE_PACKAGE
        val allowedAdd = if (state.settings.checkSystemApps) {
            requestedAdd
        } else {
            requestedAdd.filterTo(HashSet()) { packageName ->
                installed[packageName]?.isSystem != true
            }
        }
        val skippedSystem = requestedAdd.size - allowedAdd.size
        val toRemove = (packagesToRemove - UNISTORE_PACKAGE)
            .filterTo(HashSet()) { it in state.settings.trackedPackages }

        if (allowedAdd.isEmpty() && toRemove.isEmpty()) {
            if (skippedSystem > 0) {
                showMessage("Системные приложения не добавляются без их проверки")
            }
            return
        }

        toRemove.forEach { installJobs.remove(it)?.cancel() }
        viewModelScope.launch {
            val current = _uiState.value.settings.trackedPackages
            settingsRepository.setTrackedPackages((current - toRemove) + allowedAdd)
            if (allowedAdd.isNotEmpty()) ensureSignatures(allowedAdd)
            val newResults = _uiState.value.results - toRemove
            _uiState.update {
                it.copy(
                    results = newResults,
                    installStates = it.installStates - toRemove
                )
            }
            settingsRepository.setResultsJson(ResultSerializer.serialize(newResults.values))
            showMessage(
                when {
                    allowedAdd.isNotEmpty() && toRemove.isNotEmpty() ->
                        "Добавлено: ${allowedAdd.size}, убрано: ${toRemove.size}"

                    allowedAdd.size > 1 -> "Добавлено приложений: ${allowedAdd.size}"
                    allowedAdd.size == 1 -> "Приложение добавлено"
                    toRemove.size > 1 -> "Убрано приложений: ${toRemove.size}"
                    else -> "Убрано из отслеживаемых"
                }
            )
        }
    }

    fun removePackage(packageName: String) {
        applyTrackedChanges(emptySet(), setOf(packageName))
    }

    fun toggleSource(source: UpdateSource) {
        val current = _uiState.value.settings.enabledSources
        if (source.key in current && current.size == 1) {
            showMessage("Оставьте хотя бы один источник")
            return
        }
        val updated = if (source.key in current) current - source.key else current + source.key
        viewModelScope.launch {
            settingsRepository.setEnabledSources(updated)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setTheme(mode, _uiState.value.settings.palette)
        }
    }

    fun setPalette(palette: Palette) {
        viewModelScope.launch {
            settingsRepository.setTheme(_uiState.value.settings.themeMode, palette)
        }
    }

    fun setSeasonalTheme(theme: SeasonalTheme) {
        viewModelScope.launch {
            settingsRepository.setSeasonalTheme(theme)
        }
    }

    fun setWeakDevice(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWeakDevice(enabled)
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        val app = getApplication<Application>()
        val interval = _uiState.value.settings.intervalHours
        viewModelScope.launch {
            settingsRepository.setAutoCheck(enabled)
            if (enabled) {
                UpdateScheduler.schedule(app, interval)
                showMessage("Фоновая проверка включена")
            } else {
                UpdateScheduler.cancel(app)
                showMessage("Фоновая проверка выключена")
            }
        }
    }

    fun setIntervalHours(hours: Int) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            settingsRepository.setIntervalHours(hours)
            if (_uiState.value.settings.autoCheck) {
                UpdateScheduler.schedule(app, hours)
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboarded(true)
        }
    }

    fun setFilter(filter: AppFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setSearch(query: String) {
        _uiState.update { it.copy(search = query) }
    }

    fun setShowSystemApps(show: Boolean) {
        _uiState.update { it.copy(showSystemApps = show) }
    }

    fun setCheckSystemApps(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCheckSystemApps(enabled)
            if (enabled) {
                _uiState.update { it.copy(showSystemApps = false) }
                requestTrackedSignatures()
                return@launch
            }

            val state = _uiState.value
            val systemPackages = state.installed.asSequence()
                .filter { it.isSystem }
                .map { it.packageName }
                .toHashSet()
            val scannedNames = state.installed.mapTo(HashSet()) { it.packageName }
            systemPackages += container.appScanner.systemPackagesAmong(
                state.settings.trackedPackages - scannedNames
            )
            val cleanedResults = state.results - systemPackages

            _uiState.update {
                it.copy(
                    showSystemApps = false,
                    results = cleanedResults
                )
            }
            settingsRepository.pruneTrackedPackages(systemPackages)
            settingsRepository.pruneResultPackages(systemPackages)
        }
    }

    fun showMessage(message: String) {
        _snackbar.tryEmit(message)
    }

    suspend fun loadIcon(packageName: String): ImageBitmap? =
        container.iconLoader.load(packageName)

    fun peekIcon(packageName: String): ImageBitmap? =
        container.iconLoader.peek(packageName)

    private fun reconcile(
        results: Map<String, AppUpdateResult>,
        installed: Map<String, InstalledApp>,
        incompatibleUpdates: Set<String>
    ): Map<String, AppUpdateResult> {
        val visibleResults = results - UNISTORE_PACKAGE
        if (visibleResults.isEmpty() || installed.isEmpty()) return visibleResults

        return visibleResults.mapValues { (packageName, result) ->
            val app = installed[packageName]
            if (app == null) {
                if (result.status == UpdateStatus.NOT_INSTALLED) {
                    result
                } else {
                    result.copy(
                        status = UpdateStatus.NOT_INSTALLED,
                        installedVersion = ""
                    )
                }
            } else {
                val blocked = { version: StoreVersion ->
                    incompatibleUpdateKey(packageName, version.versionName) in
                        incompatibleUpdates
                }
                val best = result.best
                val blockedBest = best != null &&
                    VersionRules.isNewer(best, app) &&
                    blocked(best)
                val incompatibleBest = result.status == UpdateStatus.INCOMPATIBLE &&
                    best != null &&
                    VersionRules.isNewer(best, app)
                val allowedVersions = result.versions.filterNot(blocked)
                val allowedBest = allowedVersions
                    .filter { VersionRules.isNewer(it, app) }
                    .maxWithOrNull { first, second ->
                        val byVersion = VersionComparator.compare(
                            first.versionName,
                            second.versionName
                        )
                        if (byVersion != 0) {
                            byVersion
                        } else {
                            UpdateSource.entries.indexOf(second.source) -
                                UpdateSource.entries.indexOf(first.source)
                        }
                    }

                val status = when {
                    // APKPure already reported an ABI/minSdk mismatch.
                    incompatibleBest &&
                        allowedBest == null -> UpdateStatus.INCOMPATIBLE

                    allowedBest != null -> UpdateStatus.UPDATE_AVAILABLE
                    // Android rejected this exact version earlier.
                    blockedBest -> UpdateStatus.INCOMPATIBLE
                    best != null -> UpdateStatus.UP_TO_DATE
                    result.status == UpdateStatus.NOT_INSTALLED -> UpdateStatus.UNKNOWN
                    else -> result.status
                }

                val message = when {
                    status != UpdateStatus.INCOMPATIBLE -> result.message
                    !result.message.isNullOrBlank() -> result.message
                    best != null ->
                        "Версия ${best.versionName} несовместима с этим телефоном"

                    else -> "Версия несовместима с этим телефоном"
                }

                result.copy(
                    installedVersion = app.versionName,
                    status = status,
                    best = when (status) {
                        UpdateStatus.UPDATE_AVAILABLE -> allowedBest
                        UpdateStatus.INCOMPATIBLE -> best
                        else -> null
                    },
                    versions = allowedVersions,
                    message = message
                )
            }
        }
    }

    private companion object {
        /** Downloaded bundles waiting for their system install window. */
        const val MAX_PREPARED_INSTALLS = 4

        const val CHECK_WAKE_LOCK_TAG = "UniStore:update-check"
        const val CHECK_WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 1000L
    }
}
