package ru.updatehub.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.updatehub.app.BuildConfig
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AppScanner(private val context: Context) {

    // Fingerprints never change for an installed package (Android refuses
    // updates signed with another key), so they can be reused between scans.
    // The cache keeps startup fast after the first pass.
    private val signatureCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    suspend fun scan(includeSystem: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            // Metadata alone does not need signing certificates. Loading them
            // for every package (including hundreds of system apps) was the
            // slowest part of a cold start. Signatures are loaded on demand
            // for tracked apps by [signatures].
            val packages = installedPackages(packageManager, signing = false)
            // Anchor for preinstalled apps that have no system flag. Computed
            // once per scan from the packages that do carry a real flag.
            val setupAnchor = SystemAppDetector.setupAnchor(packages)

            val collator = Collator.getInstance(Locale("ru", "RU"))
            // Resolve every launcher once. Asking PackageManager for a launch
            // intent per system app costs hundreds of IPC calls on a full ROM.
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val launcherPackages: Set<String> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        launcherIntent,
                        PackageManager.ResolveInfoFlags.of(0L)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(launcherIntent, 0)
                }.mapTo(HashSet()) { it.activityInfo.packageName }

            // An app without any MAIN activity cannot be opened by the user.
            // Such packages are firmware components, providers, overlays or
            // plugins, even when a vendor ships them in /data/app without a
            // system flag. Android System Key Verifier and Android Developer
            // Verifier are exactly this kind of package.
            val mainActivityPackages = mainActivityPackages(packageManager)

            packages.mapNotNull { info ->
                val appInfo = info.applicationInfo ?: return@mapNotNull null
                // Store apps are configured on the Settings screen, not
                // updated like regular applications. UniStore itself is
                // installed manually, so it never belongs to the update list.
                if (info.packageName == UNISTORE_PACKAGE ||
                    info.packageName in KnownStores.packages
                ) {
                    return@mapNotNull null
                }

                if (info.packageName !in mainActivityPackages) {
                    return@mapNotNull null
                }

                // FLAG_UPDATED_SYSTEM_APP means the app was preinstalled and then
                // updated. It is still a system app and must stay hidden unless
                // the user explicitly enables system app checks. Some vendor
                // preinstalls have no flag at all and are recognised by their
                // setup-time install date instead.
                val isSystem = SystemAppDetector.isSystem(info, setupAnchor)

                if (isSystem && !includeSystem) return@mapNotNull null
                if (isSystem && info.packageName !in launcherPackages) {
                    return@mapNotNull null
                }

                val label = packageManager.getApplicationLabel(appInfo).toString()
                val versionCode = PackageInfoCompat.getLongVersionCode(info)
                val versionName = info.versionName?.takeIf { it.isNotBlank() }
                    ?: versionCode.toString()
                val cachedSignature = signatureCache[info.packageName]

                InstalledApp(
                    packageName = info.packageName,
                    label = label,
                    versionName = versionName,
                    versionCode = versionCode,
                    isSystem = isSystem,
                    installedAt = info.firstInstallTime,
                    signature = cachedSignature?.first,
                    signatureSha1 = cachedSignature?.second
                )
            }.sortedWith(compareBy(collator) { it.label })
                .also { apps ->
                    // Building the log string is pointless in release unless
                    // someone explicitly enabled debug logs for this tag.
                    if (BuildConfig.DEBUG) {
                        val systemNames = apps.asSequence()
                            .filter { it.isSystem }
                            .map { it.packageName }
                            .toList()
                        Log.d(
                            "UpdateHub",
                            "Сканирование: ${apps.size} приложений, системных " +
                                "${systemNames.size}: ${systemNames.take(25).joinToString()}"
                        )
                    }
                }
        }

    /**
     * Checks a small set of package names for system-app flags. The main scan
     * skips system apps without a launcher, so this catches older tracked
     * entries that would otherwise survive the system-app switch.
     */
    suspend fun systemPackagesAmong(packageNames: Set<String>): Set<String> =
        withContext(Dispatchers.IO) {
            if (packageNames.isEmpty()) return@withContext emptySet()
            val packageManager = context.packageManager
            // One package-list IPC instead of one call per tracked name. The
            // list is also needed to compute the setup-time anchor.
            val packages = installedPackages(packageManager, signing = false)
            val setupAnchor = SystemAppDetector.setupAnchor(packages)
            val byName = packages.associateBy { it.packageName }
            packageNames.filterTo(HashSet()) { packageName ->
                val info = byName[packageName] ?: return@filterTo false
                SystemAppDetector.isSystem(info, setupAnchor)
            }
        }

    /**
     * Tracked packages that cannot be opened by the user because they expose
     * no MAIN activity. They are firmware components rather than apps, so
     * they are removed from tracking regardless of the system-app switch.
     */
    suspend fun componentPackagesAmong(packageNames: Set<String>): Set<String> =
        withContext(Dispatchers.IO) {
            if (packageNames.isEmpty()) return@withContext emptySet()
            val packageManager = context.packageManager
            val installed = installedPackages(packageManager, signing = false)
                .mapTo(HashSet()) { it.packageName }
            val mainActivityPackages = mainActivityPackages(packageManager)
            packageNames.filterTo(HashSet()) { packageName ->
                packageName in installed && packageName !in mainActivityPackages
            }
        }

    /**
     * Loads signing fingerprints only for the requested packages. This is used
     * for tracked apps, where the repository and the APK installer need the
     * certificate to reject repackaged or re-signed builds.
     */
    suspend fun signatures(
        packageNames: Set<String>
    ): Map<String, Pair<String?, String?>> = withContext(Dispatchers.IO) {
        if (packageNames.isEmpty()) return@withContext emptyMap()

        val packageManager = context.packageManager
        val result = HashMap<String, Pair<String?, String?>>(packageNames.size)
        packageNames.forEach { packageName ->
            signatureCache[packageName]?.let { cached ->
                result[packageName] = cached
                return@forEach
            }
            val info = runCatching { packageInfo(packageManager, packageName, signing = true) }
                .getOrNull() ?: return@forEach
            val hashes = SignatureUtils.hashes(info)
            signatureCache[packageName] = hashes
            result[packageName] = hashes
        }
        result
    }

    private fun mainActivityPackages(packageManager: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }.mapTo(HashSet()) { it.activityInfo.packageName }
    }

    private fun installedPackages(
        packageManager: PackageManager,
        signing: Boolean
    ): List<PackageInfo> {
        val flags = packageFlags(signing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        }

        @Suppress("DEPRECATION")
        return packageManager.getInstalledPackages(flags.toInt())
    }

    private fun packageInfo(
        packageManager: PackageManager,
        packageName: String,
        signing: Boolean
    ): PackageInfo {
        val flags = packageFlags(signing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        }

        @Suppress("DEPRECATION")
        return packageManager.getPackageInfo(packageName, flags.toInt())
    }

    private fun packageFlags(signing: Boolean): Int {
        if (!signing) return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}
