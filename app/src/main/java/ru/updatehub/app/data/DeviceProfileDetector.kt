package ru.updatehub.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Describes the current device and picks sensible default update sources.
 *
 * Defaults are the union of:
 *  - supported store apps actually installed on the device;
 *  - stores that the device manufacturer ships with this OS
 *    (e.g. Galaxy Store on Samsung, GetApps on Xiaomi).
 *
 * If no supported store is found at all, Google Play + RuStore are used as
 * a safe fallback so that checking still works.
 */
data class DeviceProfile(
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val osName: String = "",
    val skinName: String? = null,
    val sdk: Int = 0,
    val totalRamMb: Int = 0,
    val cpuCores: Int = 0,
    val isLowRam: Boolean = false,
    val installedSources: Set<UpdateSource> = emptySet(),
    val manufacturerSources: Set<UpdateSource> = emptySet(),
    val defaultSources: Set<UpdateSource> = emptySet()
) {
    val deviceLabel: String
        get() = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android-устройство" }

    val osLabel: String
        get() = listOf(skinName, osName)
            .filter { !it.isNullOrBlank() }
            .joinToString(" · ")
            .ifBlank { "Android" }

    val defaultSourceKeys: Set<String>
        get() = defaultSources.map { it.key }.toSet()

    val isDetected: Boolean
        get() = defaultSources.isNotEmpty()

    companion object {
        fun empty() = DeviceProfile(
            osName = "Android ${Build.VERSION.RELEASE}".trim(),
            sdk = Build.VERSION.SDK_INT
        )
    }
}

class DeviceProfileDetector(private val context: Context) {

    companion object {
        /**
         * GetApps has a global and a Chinese catalogue. The Chinese one can
         * list a locally published edition of the same package with an
         * unrelated version scheme, so it must only be queried on Chinese
         * firmware. A global ROM (even with a non-global sales region) gets
         * only the global catalogue.
         */
        fun isChineseMarket(): Boolean {
            val name = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
            val isXiaomi = listOf("xiaomi", "redmi", "poco").any { it in name }
            if (!isXiaomi) return false

            val buildRegion = systemProperty("ro.miui.build.region")
            if (buildRegion.isNotBlank()) return buildRegion.equals("cn", true)

            val region = systemProperty("ro.miui.region")
            if (region.isNotBlank()) return region.equals("cn", true)

            return !Build.DEVICE.contains("global", ignoreCase = true) &&
                !Build.PRODUCT.contains("global", ignoreCase = true)
        }

        private fun systemProperty(name: String): String = runCatching {
            val process = ProcessBuilder("/system/bin/getprop", name)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText().trim() }
                .also { process.waitFor() }
        }.getOrDefault("")

        /** Total physical RAM in megabytes, or 0 if it cannot be read. */
        fun ramMb(context: Context): Int {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return 0
            val memoryInfo = ActivityManager.MemoryInfo()
            return runCatching {
                activityManager.getMemoryInfo(memoryInfo)
                (memoryInfo.totalMem / (1024L * 1024L)).toInt()
            }.getOrDefault(0)
        }

        /**
         * Android's own low-RAM flag plus a conservative 3 GB threshold. The
         * latter catches cheap phones that do not set the flag themselves.
         */
        fun isLowRam(context: Context): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager?.isLowRamDevice == true) return true
            return ramMb(context) in 1..3072
        }
    }

    fun detect(): DeviceProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val brand = Build.BRAND.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val sdk = Build.VERSION.SDK_INT
        val osName = "Android ${Build.VERSION.RELEASE}".trim()
        val properties = readSystemProperties()
        val skin = detectSkin(properties, manufacturer, brand)

        val totalRamMb = ramMb(context)
        val lowRam = isLowRam(context)
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val installed = UpdateSource.entries.filter { source ->
            source.storePackages.any(::isInstalled)
        }.toSet()

        val fromManufacturer = manufacturerSources(manufacturer, brand, installed)

        val defaults = (installed + fromManufacturer).ifEmpty {
            buildSet {
                add(UpdateSource.GOOGLE_PLAY)
                add(UpdateSource.RUSTORE)
            }
        }

        return DeviceProfile(
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            osName = osName,
            skinName = skin,
            sdk = sdk,
            totalRamMb = totalRamMb,
            cpuCores = cpuCores,
            isLowRam = lowRam,
            installedSources = installed,
            manufacturerSources = fromManufacturer,
            defaultSources = defaults
        )
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun manufacturerSources(
        manufacturer: String,
        brand: String,
        installed: Set<UpdateSource>
    ): Set<UpdateSource> = buildSet {
        val name = "$manufacturer $brand".lowercase()

        when {
            "samsung" in name -> add(UpdateSource.GALAXY_STORE)
            "xiaomi" in name || "redmi" in name || "poco" in name ->
                add(UpdateSource.GETAPPS)
            "vivo" in name || "iqoo" in name -> add(UpdateSource.VIVO_STORE)
            "meizu" in name || "flyme" in name -> add(UpdateSource.FLYME_STORE)
        }

        // Google Play is part of the standard Android build on every device
        // with Google Mobile Services, including Pixel / Motorola / Nothing.
        val hasGms = isInstalled("com.google.android.gms")
        val huaweiLike = "huawei" in name || "honor" in name
        if (hasGms || (UpdateSource.GOOGLE_PLAY in installed && !huaweiLike)) {
            add(UpdateSource.GOOGLE_PLAY)
        }
    }

    private fun detectSkin(
        properties: Map<String, String>,
        manufacturer: String,
        brand: String
    ): String? {
        properties["ro.mi.os.version.name"]?.takeIf { it.isNotBlank() }?.let { return "HyperOS" }
        properties["ro.miui.ui.version.name"]?.takeIf { it.isNotBlank() }?.let { return "MIUI" }
        properties["ro.build.version.oneui"]?.takeIf { it.isNotBlank() }?.let { return "One UI" }
        properties["ro.build.version.emui"]?.takeIf { it.isNotBlank() }?.let { return "EMUI" }
        properties["hw_sc.build.platform.version"]?.takeIf { it.isNotBlank() }?.let { return "HarmonyOS" }
        properties["ro.build.version.oxygenos"]?.takeIf { it.isNotBlank() }?.let { return "OxygenOS" }
        properties["ro.build.version.opporom"]?.takeIf { it.isNotBlank() }?.let { return "ColorOS" }
        properties["ro.vivo.os.version"]?.takeIf { it.isNotBlank() }?.let { return "Funtouch OS" }
        properties["ro.build.version.realmeui"]?.takeIf { it.isNotBlank() }?.let { return "realme UI" }
        properties["ro.build.version.magic"]?.takeIf { it.isNotBlank() }?.let { return "MagicOS" }

        val name = "$manufacturer $brand".lowercase()
        return when {
            "samsung" in name -> "One UI"
            "xiaomi" in name || "redmi" in name || "poco" in name -> "HyperOS"
            "huawei" in name -> "EMUI"
            "honor" in name -> "MagicOS"
            "oneplus" in name -> "OxygenOS"
            "oppo" in name || "realme" in name -> "ColorOS"
            "vivo" in name || "iqoo" in name -> "Funtouch OS"
            "nothing" in name -> "Nothing OS"
            "motorola" in name -> "My UX"
            else -> null
        }
    }

    /**
     * `getprop` is a stable Android tool. Reading all properties at once is
     * cheaper than spawning a process per property and avoids hidden APIs.
     */
    private fun readSystemProperties(): Map<String, String> = runCatching {
        val process = ProcessBuilder("/system/bin/getprop")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.lineSequence().mapNotNull { line ->
            val keyStart = line.indexOf('[')
            val keyEnd = line.indexOf(']', keyStart + 1)
            val valueStart = line.indexOf('[', keyEnd + 1)
            val valueEnd = line.indexOf(']', valueStart + 1)
            if (keyStart >= 0 && keyEnd > keyStart && valueStart > keyEnd && valueEnd > valueStart) {
                line.substring(keyStart + 1, keyEnd) to line.substring(valueStart + 1, valueEnd)
            } else {
                null
            }
        }.toMap()
    }.getOrDefault(emptyMap())
}
