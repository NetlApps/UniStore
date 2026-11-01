package ru.updatehub.app.data.install

import ru.updatehub.app.data.checkers.VersionDetails

private val thirtyTwoBitAbis = setOf("armeabi", "armeabi-v7a", "x86", "mips")

private fun normalizeAbi(abi: String): String = abi.trim().lowercase().replace('_', '-')

/**
 * Checks the lightweight metadata from a store's download page before the APK
 * is downloaded. This prevents a 32-bit-only build from appearing as a normal
 * update on a 64-bit-only phone. The downloaded bundle is checked again with
 * [compatibilityError] because store pages can be incomplete or stale.
 */
internal fun metadataCompatibilityError(
    details: VersionDetails,
    deviceSdk: Int,
    supportedAbis: List<String>
): String? {
    val minSdk = details.minSdk
    if (minSdk != null && minSdk > deviceSdk) {
        return "APK требует Android ${androidVersionName(minSdk)}, " +
            "а на телефоне Android ${androidVersionName(deviceSdk)}"
    }

    val apkAbis = details.architectures.mapTo(LinkedHashSet(), ::normalizeAbi)
    if (apkAbis.isEmpty()) return null

    val deviceAbis = supportedAbis.mapTo(LinkedHashSet(), ::normalizeAbi)
    if (apkAbis.any { it in deviceAbis }) return null

    val apkIs32BitOnly = apkAbis.all { it in thirtyTwoBitAbis }
    val deviceIs64BitOnly = deviceAbis.none { it in thirtyTwoBitAbis }
    return if (apkIs32BitOnly && deviceIs64BitOnly) {
        "APK собран только для 32-битных процессоров, а телефон работает только с 64-битными"
    } else {
        "APK несовместим с процессором телефона"
    }
}
