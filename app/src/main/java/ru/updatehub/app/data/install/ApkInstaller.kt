package ru.updatehub.app.data.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import ru.updatehub.app.data.InstalledApp
import ru.updatehub.app.data.SignatureUtils
import java.io.File
import java.util.zip.ZipFile

/** Result of checking that a downloaded APK/bundle is safe for this phone. */
sealed interface ApkVerification {
    data object Valid : ApkVerification

    /** The file is genuine, but the phone cannot run it. */
    data class Incompatible(val message: String) : ApkVerification

    /** The file is damaged, belongs to another app, or has a wrong signature. */
    data class Failed(
        val message: String,
        /**
         * True when retrying the exact same store version cannot help: the
         * certificate, package or version will stay wrong. The caller hides
         * this version so it does not keep offering an install that Android
         * must reject.
         */
        val hideVersion: Boolean = false
    ) : ApkVerification
}

class ApkInstaller(private val context: Context) {

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Checks package name, version, every split's signature, native ABIs,
     * minSdk and the targetSdk floor before anything is sent to Android.
     */
    fun verify(bundle: DownloadedApkBundle, installed: InstalledApp): ApkVerification {
        val base = archiveInfo(bundle.baseApk)
            ?: return ApkVerification.Failed("Не удалось прочитать скачанный APK")

        if (base.packageName != installed.packageName) {
            return ApkVerification.Failed(
                "APK относится к другому приложению",
                hideVersion = true
            )
        }

        val versionCode = PackageInfoCompat.getLongVersionCode(base)
        if (versionCode <= installed.versionCode) {
            return ApkVerification.Failed(
                "Скачанная версия не новее установленной",
                hideVersion = true
            )
        }

        val apkAbis = runCatching { nativeAbis(bundle.allApks) }.getOrElse {
            return ApkVerification.Failed("Не удалось проверить совместимость APK")
        }
        compatibilityError(
            apkMinSdk = base.applicationInfo?.minSdkVersion ?: 1,
            apkTargetSdk = base.applicationInfo?.targetSdkVersion ?: 0,
            apkAbis = apkAbis,
            deviceSdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList()
        )?.let { return ApkVerification.Incompatible(it) }

        if (bundle.splitApks.isEmpty() &&
            base.applicationInfo?.metaData?.containsKey(SPLITS_REQUIRED) == true
        ) {
            return ApkVerification.Incompatible(
                "В архиве нет обязательных частей приложения. " +
                    "Скачайте полный XAPK из APKPure"
            )
        }

        val baseSignatureError = signatureVerificationError(
            installedSha256 = installed.signature,
            installedSha1 = installed.signatureSha1,
            apkSha256 = SignatureUtils.sha256(base),
            apkSha1 = SignatureUtils.sha1(base)
        )
        if (baseSignatureError != null) {
            Log.w(
                TAG,
                "Подпись ${bundle.baseApk.name}: установлено=${installed.signature}, " +
                    "архив=${SignatureUtils.sha256(base)}"
            )
            return ApkVerification.Failed(baseSignatureError, hideVersion = true)
        }

        val baseVersionCode = PackageInfoCompat.getLongVersionCode(base)

        fun verifySplit(
            splitName: String,
            packageName: String,
            versionCode: Long,
            apkSha256: String?,
            apkSha1: String?
        ): ApkVerification.Failed? {
            if (packageName != installed.packageName) {
                return ApkVerification.Failed(
                    "Одна из частей пакета относится к другому приложению",
                    hideVersion = true
                )
            }
            if (versionCode > 0 && versionCode != baseVersionCode) {
                return ApkVerification.Failed(
                    "Версии частей пакета не совпадают",
                    hideVersion = true
                )
            }
            val signatureError = signatureVerificationError(
                installedSha256 = installed.signature,
                installedSha1 = installed.signatureSha1,
                apkSha256 = apkSha256,
                apkSha1 = apkSha1
            )
            return if (signatureError != null) {
                Log.w(
                    TAG,
                    "Подпись части $splitName: установлено=${installed.signature}, " +
                        "архив=$apkSha256"
                )
                ApkVerification.Failed(
                    "Подпись одной из частей пакета не совпадает " +
                        "с установленным приложением «$splitName»",
                    hideVersion = true
                )
            } else {
                null
            }
        }

        bundle.splitApks.forEach { split ->
            val info = archiveInfo(split)
            val infoSha256 = info?.let { SignatureUtils.sha256(it) }
            val infoSha1 = info?.let { SignatureUtils.sha1(it) }

            if (info != null && (infoSha256 != null || infoSha1 != null)) {
                verifySplit(
                    splitName = split.name,
                    packageName = info.packageName,
                    versionCode = PackageInfoCompat.getLongVersionCode(info),
                    apkSha256 = infoSha256,
                    apkSha1 = infoSha1
                )?.let { return it }
            } else {
                // Android 9 cannot parse split APKs through PackageManager,
                // so read the compiled manifest and signing certificate
                // directly from the archive.
                val manifest = ApkArchiveInspector.readManifest(split)
                    ?: return ApkVerification.Failed(
                        "Не удалось проверить часть пакета «${split.name}»"
                    )
                val (apkSha256, apkSha1) = ApkArchiveInspector.certificateHashes(split)
                verifySplit(
                    splitName = split.name,
                    packageName = manifest.packageName,
                    versionCode = manifest.versionCode,
                    apkSha256 = apkSha256,
                    apkSha1 = apkSha1
                )?.let { return it }
            }
        }

        return ApkVerification.Valid
    }

    /**
     * Installs through [PackageInstaller] because Android's classic
     * `ACTION_VIEW` installer cannot handle split APKs. The system still shows
     * its own confirmation window; the result comes back to [ApkInstallReceiver].
     */
    fun install(bundle: DownloadedApkBundle, versionName: String): Boolean {
        val installer = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(bundle.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(bundle.allApks.sumOf { it.length() })
        }

        val sessionId = runCatching { installer.createSession(sessionParams) }.getOrNull()
            ?: return false

        val session = runCatching { installer.openSession(sessionId) }.getOrElse {
            runCatching { installer.abandonSession(sessionId) }
            return false
        }

        var committed = false
        try {
            bundle.allApks.forEach { apk ->
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                    session.fsync(output)
                }
            }

            val callback = Intent(context, ApkInstallReceiver::class.java).apply {
                action = ApkInstallReceiver.ACTION_INSTALL_STATUS
                putExtra(ApkInstallReceiver.EXTRA_PACKAGE_NAME, bundle.packageName)
                putExtra(ApkInstallReceiver.EXTRA_VERSION_NAME, versionName)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                pendingFlags
            )
            session.commit(pendingIntent.intentSender)
            committed = true
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { session.close() }
            if (!committed) {
                runCatching { installer.abandonSession(sessionId) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? {
        val packageManager = context.packageManager
        val path = file.absolutePath
        val meta = PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = packageManager.getPackageArchiveInfo(
                path,
                meta or PackageManager.GET_SIGNING_CERTIFICATES
            )
            if (modern != null &&
                (SignatureUtils.sha256(modern) != null ||
                    SignatureUtils.sha1(modern) != null)
            ) {
                modern
            } else {
                packageManager.getPackageArchiveInfo(
                    path,
                    meta or PackageManager.GET_SIGNATURES
                ) ?: modern
            }
        } else {
            packageManager.getPackageArchiveInfo(
                path,
                meta or PackageManager.GET_SIGNATURES
            )
        }
    }

    private companion object {
        const val SPLITS_REQUIRED = "com.android.vending.splits.required"
        const val TAG = "UpdateHub"
    }
}

/**
 * Native ABIs declared inside an APK (`lib/<abi>/…`). An empty set means the
 * APK is pure Java/Kotlin and can run on any CPU architecture.
 */
internal fun nativeAbis(apkFile: File): Set<String> {
    ZipFile(apkFile).use { zip ->
        val result = linkedSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (!name.startsWith("lib/")) continue
            val separator = name.indexOf('/', startIndex = 4)
            if (separator <= 4) continue
            result += name.substring(4, separator)
        }
        return result
    }
}

/** Combined native ABIs of the base APK and every split. */
internal fun nativeAbis(apkFiles: Collection<File>): Set<String> {
    val result = linkedSetOf<String>()
    apkFiles.forEach { result += nativeAbis(it) }
    return result
}

/**
 * Pure device-compatibility check. Returns null when the APK can be handed to
 * the system installer, otherwise a specific Russian reason.
 *
 * Android shows the generic "Приложение не установлено, так как оно
 * несовместимо с вашим телефоном" screen for all of these cases. Checking the
 * APK manifest and native libraries beforehand lets UniStore explain what is
 * wrong instead of sending the user into a dead end.
 */
internal fun compatibilityError(
    apkMinSdk: Int,
    apkTargetSdk: Int,
    apkAbis: Set<String>,
    deviceSdk: Int,
    supportedAbis: List<String>
): String? {
    if (apkMinSdk > deviceSdk) {
        return "APK требует Android ${androidVersionName(apkMinSdk)}, " +
            "а на телефоне Android ${androidVersionName(deviceSdk)}"
    }

    if (apkAbis.isNotEmpty() && apkAbis.none { it in supportedAbis }) {
        val apk32BitOnly = apkAbis.all {
            it == "armeabi" || it == "armeabi-v7a" || it == "x86" || it == "mips"
        }
        val device64BitOnly = supportedAbis.none {
            it == "armeabi" || it == "armeabi-v7a" || it == "x86" || it == "mips"
        }
        return if (apk32BitOnly && device64BitOnly) {
            "APK собран только для 32-битных процессоров, а телефон работает только с 64-битными"
        } else {
            "APK несовместим с процессором телефона"
        }
    }

    val minimumTargetSdk = minimumTargetSdk(deviceSdk)
    if (apkTargetSdk in 1 until minimumTargetSdk) {
        return "APK устарел: Android ${androidVersionName(deviceSdk)} " +
            "не устанавливает приложения ниже Android ${androidVersionName(minimumTargetSdk)}"
    }

    return null
}

/** Minimum targetSdkVersion that Android itself still allows to install. */
internal fun minimumTargetSdk(deviceSdk: Int): Int = when {
    deviceSdk >= 35 -> 24
    deviceSdk >= 34 -> 23
    else -> 0
}

/** Human-readable Android release used in compatibility errors. */
internal fun androidVersionName(apiLevel: Int): String = when (apiLevel) {
    19 -> "4.4"
    20 -> "4.4W"
    21 -> "5.0"
    22 -> "5.1"
    23 -> "6.0"
    24 -> "7.0"
    25 -> "7.1"
    26 -> "8.0"
    27 -> "8.1"
    28 -> "9"
    29 -> "10"
    30 -> "11"
    31 -> "12"
    32 -> "12L"
    33 -> "13"
    34 -> "14"
    35 -> "15"
    36 -> "16"
    37 -> "17"
    else -> "API $apiLevel"
}

/**
 * Pure comparison used by [ApkInstaller.verify]. Returns null when the
 * fingerprints match, otherwise a Russian error for the user.
 *
 * If neither SHA-256 nor SHA-1 could be compared, the result is an error.
 * Passing an unverified APK to the system installer used to end with the
 * generic "Приложение не установлено" screen after a real certificate
 * mismatch (for example, a GitHub build being replaced by an F-Droid build).
 */
internal fun signatureVerificationError(
    installedSha256: String?,
    installedSha1: String?,
    apkSha256: String?,
    apkSha1: String?
): String? {
    if (installedSha256 != null && apkSha256 != null) {
        return if (apkSha256.equals(installedSha256, ignoreCase = true)) {
            null
        } else {
            "Подпись APK не совпадает с установленным приложением"
        }
    }
    if (installedSha1 != null && apkSha1 != null) {
        return if (apkSha1.equals(installedSha1, ignoreCase = true)) {
            null
        } else {
            "Подпись APK не совпадает с установленным приложением"
        }
    }
    return "Не удалось проверить подпись APK"
}
