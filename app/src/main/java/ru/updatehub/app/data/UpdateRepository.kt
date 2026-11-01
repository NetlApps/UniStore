package ru.updatehub.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ru.updatehub.app.data.checkers.ApkPureChecker
import ru.updatehub.app.data.checkers.CheckOutcome
import ru.updatehub.app.data.checkers.GooglePlayChecker
import ru.updatehub.app.data.checkers.Http
import ru.updatehub.app.data.checkers.StoreChecker
import ru.updatehub.app.data.checkers.VersionDetailsProvider
import ru.updatehub.app.data.checkers.clearRouteMemory
import ru.updatehub.app.data.install.metadataCompatibilityError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class UpdateRepository(
    checkers: List<StoreChecker>,
    private val cacheTtlMs: Long = 10 * 60 * 1000L,
    parallelism: Int = 12,
    private val deviceSdk: Int,
    private val supportedAbis: List<String>
) {
    private val checkersBySource = checkers.associateBy { it.source }
    private val cache = ConcurrentHashMap<String, CachedOutcome>()
    private val baselineParallelism = parallelism

    @Volatile
    private var networkSemaphore = Semaphore(parallelism)

    @Volatile
    private var networkCapacity = parallelism

    @Volatile
    private var weakDevice = false

    @Volatile
    private var metered = false

    /** Reduces simultaneous store requests on weak devices. */
    fun setWeakDevice(enabled: Boolean) {
        if (weakDevice == enabled) return
        weakDevice = enabled
        applyNetworkProfile()
    }

    /**
     * A cellular link has a much higher round-trip time and is shared by every
     * request. Twelve parallel store pages made each one slower and pushed
     * healthy Play responses past their route timeouts. Four slots keep the
     * link busy without letting one large response starve all the others.
     */
    fun setMeteredNetwork(enabled: Boolean) {
        if (metered == enabled) return
        metered = enabled
        applyNetworkProfile()
        clearRouteMemory()
        Http.resetNetworkState()
        checkersBySource.values.forEach { checker ->
            when (checker) {
                is GooglePlayChecker -> checker.setMeteredNetwork(enabled)
                is ApkPureChecker -> checker.setMeteredNetwork(enabled)
                else -> Unit
            }
        }
    }

    private fun applyNetworkProfile() {
        val capacity = when {
            metered -> 4
            weakDevice -> minOf(6, baselineParallelism)
            else -> baselineParallelism
        }
        if (capacity == networkCapacity) return
        networkCapacity = capacity
        networkSemaphore = Semaphore(capacity)
    }

    suspend fun checkAll(
        packages: Set<String>,
        sources: Set<UpdateSource>,
        installed: Map<String, InstalledApp>,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
        onResult: (AppUpdateResult) -> Unit = {},
        onSourceUnavailable: (UpdateSource) -> Unit = {}
    ): Map<String, AppUpdateResult> = coroutineScope {
        val orderedSources = UpdateSource.entries.filter { it in sources }
        val total = packages.size
        val done = AtomicInteger(0)
        val results = ConcurrentHashMap<String, AppUpdateResult>()
        val health = SourceHealth(
            failureThreshold = if (metered) 2 else 3,
            metered = metered
        )

        // Every package starts immediately and queues its store requests on
        // the shared network semaphore. There is no small package-level gate,
        // so a slow Google Play request for the first few apps no longer holds
        // up every other app in the list.
        packages.map { packageName ->
            async(Dispatchers.IO) {
                val app = installed[packageName]
                val result = if (app == null) {
                    AppUpdateResult(
                        packageName = packageName,
                        installedVersion = "",
                        status = UpdateStatus.NOT_INSTALLED,
                        checkedAt = System.currentTimeMillis(),
                        message = "Приложение не установлено"
                    )
                } else {
                    checkApp(app, orderedSources, health, onSourceUnavailable)
                }
                results[packageName] = result
                // Publish each finished package right away: counters and rows
                // update during the check instead of jumping at the end.
                onResult(result)
                onProgress(done.incrementAndGet(), total, app?.label ?: packageName)
            }
        }.awaitAll()

        results.toMap()
    }

    /** Measures the connection to every source in parallel. */
    suspend fun probeStores(
        sources: Collection<UpdateSource> = UpdateSource.entries
    ): Map<UpdateSource, StoreHealth> = coroutineScope {
        sources.distinct().map { source ->
            async(Dispatchers.IO) {
                val probe = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    try {
                        Http.probe(source.healthUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                source to storeHealthForLatency(probe?.latencyMs, source.healthOnlineMaxMs)
            }
        }.awaitAll().toMap()
    }

    /**
     * Re-checks one source without using the in-memory cache. Used right
     * before redirecting to a store that cannot install an APK, so a stale
     * result or a removed listing does not send the user to an empty page.
     */
    suspend fun verify(source: UpdateSource, packageName: String): CheckOutcome {
        val checker = checkersBySource[source]
            ?: return CheckOutcome.Failed("Источник недоступен", transient = true)
        return networkSemaphore.withPermit {
            checker.check(packageName)
        }
    }

    private suspend fun checkApp(
        app: InstalledApp,
        sources: List<UpdateSource>,
        health: SourceHealth,
        onSourceUnavailable: (UpdateSource) -> Unit
    ): AppUpdateResult = coroutineScope {
        // A copy signed with the public AOSP test key can never be updated by
        // a store: Play sees a different certificate and offers only "Open",
        // while scraped store pages still advertise a newer version. Asking
        // the stores would only produce a fake update, so skip the network
        // entirely and explain why the app cannot be updated.
        if (!app.isSystem && SignatureUtils.isPublicTestSignature(app.signature)) {
            Log.w(TAG, "Skipping stores for ${app.packageName}: public AOSP test key")
            return@coroutineScope AppUpdateResult(
                packageName = app.packageName,
                installedVersion = app.versionName,
                status = UpdateStatus.UP_TO_DATE,
                checkedAt = System.currentTimeMillis(),
                message = "Тестовая подпись: обновление невозможно, переустановите приложение из официального источника"
            )
        }

        val activeSources = sources.filterNot { health.isDisabled(it) }
        if (activeSources.isEmpty()) {
            return@coroutineScope AppUpdateResult(
                packageName = app.packageName,
                installedVersion = app.versionName,
                status = UpdateStatus.ERROR,
                checkedAt = System.currentTimeMillis(),
                message = "Все источники временно недоступны"
            )
        }

        val outcomes: List<Pair<UpdateSource, CheckOutcome>> = activeSources.map { source ->
            async(Dispatchers.IO) {
                source to checkSource(source, app.packageName, health, onSourceUnavailable)
            }
        }.awaitAll()

        var signatureIncompatibleCount = 0
        val foundVersions = outcomes.mapNotNull { (source, outcome) ->
            val found = outcome as? CheckOutcome.Found ?: return@mapNotNull null
            val versionName = found.versionName.trim()
            if (versionName.isBlank() ||
                versionName.equals("VARY", ignoreCase = true) ||
                versionName.contains("varies", ignoreCase = true)
            ) {
                return@mapNotNull null
            }

            val signatureCompatible = when {
                found.signature != null && app.signature != null ->
                    found.signature.equals(app.signature, ignoreCase = true)

                found.signatureSha1 != null && app.signatureSha1 != null ->
                    found.signatureSha1.equals(app.signatureSha1, ignoreCase = true)

                else -> true
            }
            if (!signatureCompatible) {
                signatureIncompatibleCount++
                return@mapNotNull null
            }

            StoreVersion(
                source = source,
                versionName = versionName,
                versionCode = found.versionCode,
                storeUrl = source.webUrl(app.packageName),
                title = found.title,
                signature = found.signature,
                signatureSha1 = found.signatureSha1
            )
        }

        // Never keep versions that are older than (or equal to) the installed
        // build. Some stores publish stale metadata for repackaged apps; such
        // rows used to look like updates and then opened a store with nothing
        // to install. Only genuinely newer candidates survive.
        val newerVersions = foundVersions.filter { VersionRules.isNewer(it, app) }

        // APKPure exposes architecture and minSdk on its download page. Drop a
        // candidate here when it is already known to be unusable on this exact
        // phone, so it cannot stay in the "Обновить" state after a failed
        // install attempt. Details are fetched in parallel: one extra page
        // must not serialize the whole application.
        val compatibility = newerVersions.map { version ->
            async(Dispatchers.IO) {
                val provider = checkersBySource[version.source] as? VersionDetailsProvider
                val details = if (provider == null) {
                    null
                } else {
                    withTimeoutOrNull(sourceTimeoutMillis(version.source)) {
                        try {
                            provider.details(app.packageName)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                version to details?.let {
                    metadataCompatibilityError(it, deviceSdk, supportedAbis)
                }
            }
        }.awaitAll()

        val compatibleVersions = mutableListOf<StoreVersion>()
        val deviceIncompatibleVersions = mutableListOf<StoreVersion>()
        var deviceIncompatibleReason: String? = null
        compatibility.forEach { (version, error) ->
            if (error == null) {
                compatibleVersions += version
            } else {
                deviceIncompatibleVersions += version
                if (deviceIncompatibleReason == null) deviceIncompatibleReason = error
            }
        }

        val versions = compatibleVersions
        val best = newestVersion(compatibleVersions)
            ?: newestVersion(deviceIncompatibleVersions)

        val errors = outcomes.mapNotNull { (source, outcome) ->
            (outcome as? CheckOutcome.Failed)?.let { "${source.title}: ${it.message}" }
        }

        val status = when {
            compatibleVersions.isNotEmpty() -> UpdateStatus.UPDATE_AVAILABLE
            deviceIncompatibleVersions.isNotEmpty() -> UpdateStatus.INCOMPATIBLE
            foundVersions.isNotEmpty() -> UpdateStatus.UP_TO_DATE
            signatureIncompatibleCount > 0 -> UpdateStatus.UP_TO_DATE
            errors.isNotEmpty() -> UpdateStatus.ERROR
            else -> UpdateStatus.NOT_FOUND
        }

        val message = when {
            status == UpdateStatus.INCOMPATIBLE ->
                deviceIncompatibleReason ?: "Версия несовместима с этим телефоном"

            status == UpdateStatus.ERROR -> errors.take(2).joinToString("; ")
            versions.isEmpty() && signatureIncompatibleCount > 0 ->
                "В источнике только версия, несовместимая с установленной"

            signatureIncompatibleCount > 0 ->
                "Версии с другой подписью пропущены"

            errors.isNotEmpty() && status == UpdateStatus.UP_TO_DATE ->
                "Часть источников недоступна"

            else -> null
        }

        AppUpdateResult(
            packageName = app.packageName,
            installedVersion = app.versionName,
            status = status,
            best = best,
            versions = versions,
            checkedAt = System.currentTimeMillis(),
            message = message
        )
    }

    private fun newestVersion(versions: List<StoreVersion>): StoreVersion? =
        versions.maxWithOrNull { first, second ->
            val byVersion = VersionComparator.compare(first.versionName, second.versionName)
            if (byVersion != 0) {
                byVersion
            } else {
                UpdateSource.entries.indexOf(second.source) -
                    UpdateSource.entries.indexOf(first.source)
            }
        }

    private suspend fun checkSource(
        source: UpdateSource,
        packageName: String,
        health: SourceHealth,
        onSourceUnavailable: (UpdateSource) -> Unit
    ): CheckOutcome {
        val key = "${source.key}|$packageName"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.timestamp < cacheTtlMs }?.let { return it.outcome }

        val checker = checkersBySource[source]
            ?: return CheckOutcome.Failed("Источник недоступен", transient = true)

        val outcome = networkSemaphore.withPermit {
            if (health.isDisabled(source)) {
                CheckOutcome.Failed("Источник временно недоступен", transient = true)
            } else {
                // A store that stalls must not hold its network slot until the
                // OkHttp call timeout. Cancelling the coroutine also cancels
                // the enqueued HTTP call inside Http.
                withTimeoutOrNull(sourceTimeoutMillis(source)) {
                    checker.check(packageName)
                } ?: CheckOutcome.Failed(
                    "${source.title}: превышено время ожидания",
                    transient = true
                )
            }
        }
        if (health.record(source, outcome)) {
            android.util.Log.i(
                "UpdateHub",
                "Источник ${source.key} отключён до конца проверки после сетевых ошибок"
            )
            onSourceUnavailable(source)
        }
        // Cache only positive results. A temporary "not found" (Cloudflare
        // challenge, rate limit or a missing listing) must not hide a real
        // update for the whole TTL; the next check should retry immediately.
        if (outcome is CheckOutcome.Found) {
            cache[key] = CachedOutcome(outcome, now)
        }
        return outcome
    }

    private class SourceHealth(
        private val failureThreshold: Int = 3,
        private val metered: Boolean = false
    ) {
        private val consecutiveFailures = ConcurrentHashMap<UpdateSource, Int>()
        private val disabled = ConcurrentHashMap.newKeySet<UpdateSource>()

        fun isDisabled(source: UpdateSource): Boolean = source in disabled

        /** Returns true when this outcome disabled the source. */
        fun record(source: UpdateSource, outcome: CheckOutcome): Boolean {
            if (outcome is CheckOutcome.Failed && outcome.transient) {
                // On Wi-Fi, Google Play pages are large and a few slow
                // responses are normal. The compact data route is small and
                // usually answers in one or two seconds, so even two isolated
                // timeouts should not disable the whole source for the rest
                // of the check. On a metered link the limit is four instead
                // of the generic two; the old two-failure rule dropped Play
                // for later apps after a single bad patch of LTE.
                val threshold = if (source == UpdateSource.GOOGLE_PLAY) {
                    if (metered) 4 else failureThreshold + 2
                } else {
                    failureThreshold
                }
                val failures = consecutiveFailures.merge(source, 1, Int::plus) ?: 1
                if (failures >= threshold) {
                    return disabled.add(source)
                }
            } else {
                consecutiveFailures[source] = 0
            }
            return false
        }
    }

    private data class CachedOutcome(
        val outcome: CheckOutcome,
        val timestamp: Long
    )

    private companion object {
        const val PROBE_TIMEOUT_MS = 12_000L
        const val TAG = "UpdateRepository"
    }
}

/**
 * Upper bound for a single store lookup. Google Play pages are large (about
 * 200 KB compressed) and a mobile link can be slow; 14 seconds often cut a
 * healthy page off in the middle. Other stores are small and fast.
 */
internal fun sourceTimeoutMillis(source: UpdateSource): Long = when (source) {
    UpdateSource.GOOGLE_PLAY -> 27_000L
    // The API, the HTML page and two CDN filename routes each have their own
    // short timeout. The outer bound only exists so a stalled socket cannot
    // hold the source slot forever.
    UpdateSource.APKPURE -> 22_000L
    UpdateSource.RUSTORE -> 12_000L
    UpdateSource.GETAPPS -> 12_000L
    else -> 10_000L
}
