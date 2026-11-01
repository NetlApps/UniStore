package ru.updatehub.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.updatehub.app.UpdateHubApp
import ru.updatehub.app.data.ResultSerializer
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.data.UpdateStatus
import ru.updatehub.app.data.incompatibleUpdateKey

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val container = (applicationContext as UpdateHubApp).container
            val settings = container.settingsRepository.settings.first()
            if (!settings.autoCheck || settings.trackedPackages.isEmpty()) {
                return Result.success()
            }

            val sources = UpdateSource.fromKeys(settings.enabledSources).toSet()
            if (sources.isEmpty()) return Result.success()

            // Background checks used to run with the Wi-Fi concurrency profile
            // on LTE as well, which caused mass timeouts on slow carriers.
            // Apply the same link profile as the foreground check.
            container.updateRepository.setWeakDevice(settings.weakDevice)
            container.updateRepository.setMeteredNetwork(
                container.networkMonitor.snapshot().metered
            )

            val installed = container.appScanner.scan(includeSystem = true)
                .associateBy { it.packageName }
            val packages = if (settings.checkSystemApps) {
                settings.trackedPackages
            } else {
                settings.trackedPackages
                    .filter { installed[it]?.isSystem != true }
                    .toSet()
            }
            if (packages.isEmpty()) return Result.success()

            // Background checks need the same certificate fingerprints as the
            // UI so notifications never point at a repackaged build.
            val fingerprints = container.appScanner.signatures(packages)
            val signedInstalled = installed.mapValues { (packageName, app) ->
                val hashes = fingerprints[packageName] ?: return@mapValues app
                app.copy(
                    signature = hashes.first ?: app.signature,
                    signatureSha1 = hashes.second ?: app.signatureSha1
                )
            }

            val results = container.updateRepository.checkAll(
                packages = packages,
                sources = sources,
                installed = signedInstalled
            )
            val timestamp = System.currentTimeMillis()
            container.settingsRepository.saveCheckResults(
                timestamp = timestamp,
                json = ResultSerializer.serialize(results.values)
            )

            val updates = results.values.filter { result ->
                result.status == UpdateStatus.UPDATE_AVAILABLE &&
                    result.best?.let { version ->
                        incompatibleUpdateKey(result.packageName, version.versionName) !in
                            settings.incompatibleUpdates
                    } == true
            }
            if (updates.isNotEmpty()) {
                Notifications.showUpdates(
                    context = applicationContext,
                    updates = updates,
                    labels = installed.mapValues { it.value.label }
                )
            }
            Result.success()
        }.getOrElse { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            Result.success()
        }
    }
}
