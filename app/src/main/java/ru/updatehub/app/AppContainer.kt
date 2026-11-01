package ru.updatehub.app

import android.content.Context
import android.os.Build
import ru.updatehub.app.data.AppScanner
import ru.updatehub.app.data.DeviceProfileDetector
import ru.updatehub.app.data.IconLoader
import ru.updatehub.app.data.NetworkMonitor
import ru.updatehub.app.data.SettingsRepository
import ru.updatehub.app.data.UpdateRepository
import ru.updatehub.app.data.checkers.ApkPureChecker
import ru.updatehub.app.data.checkers.AptoideChecker
import ru.updatehub.app.data.checkers.FdroidChecker
import ru.updatehub.app.data.checkers.FlymeStoreChecker
import ru.updatehub.app.data.checkers.GalaxyStoreChecker
import ru.updatehub.app.data.checkers.GetAppsChecker
import ru.updatehub.app.data.checkers.GooglePlayChecker
import ru.updatehub.app.data.checkers.RuStoreChecker
import ru.updatehub.app.data.checkers.VivoStoreChecker
import ru.updatehub.app.data.install.ApkDownloader
import ru.updatehub.app.data.install.ApkInstaller
import ru.updatehub.app.data.install.DirectApkResolver

class AppContainer(context: Context) {
    private val supportedAbis = Build.SUPPORTED_ABIS.toList()

    val settingsRepository = SettingsRepository(context)
    val appScanner = AppScanner(context)
    val iconLoader = IconLoader(context)
    val deviceProfileDetector = DeviceProfileDetector(context)
    val networkMonitor = NetworkMonitor(context)
    val directApkResolver = DirectApkResolver(supportedAbis)
    val apkDownloader = ApkDownloader(context)
    val apkInstaller = ApkInstaller(context)

    private val checkers = listOf(
        GooglePlayChecker(),
        RuStoreChecker(),
        GetAppsChecker(
            chineseCatalog = DeviceProfileDetector::isChineseMarket
        ),
        GalaxyStoreChecker(),
        ApkPureChecker(deviceAbis = supportedAbis),
        FdroidChecker(),
        AptoideChecker(),
        VivoStoreChecker(),
        FlymeStoreChecker()
    )

    val updateRepository = UpdateRepository(
        checkers = checkers,
        parallelism = if (DeviceProfileDetector.isLowRam(context)) 6 else 12,
        deviceSdk = Build.VERSION.SDK_INT,
        supportedAbis = supportedAbis
    ).also { repository ->
        repository.setMeteredNetwork(networkMonitor.snapshot().metered)
    }
}
