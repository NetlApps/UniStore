package ru.updatehub.app.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo

/**
 * Detects applications that were shipped with the phone instead of being
 * installed by the user.
 *
 * `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` cover most factory apps, but some
 * vendors put preinstalled applications into `/data/app` without these flags.
 * On Xiaomi, for example, the factory scanner and calculator have no system
 * flag at all. Such packages are written during the initial device setup,
 * seconds after the first real system package, so their `firstInstallTime`
 * falls into a narrow window around the earliest system install.
 *
 * Some Google components (for example Android System Safety) are installed
 * later by Play services but are still part of the firmware. They cannot be
 * found by the time heuristic, so they are listed explicitly.
 */
object SystemAppDetector {

    /**
     * Preinstalled data apps are unpacked during setup. Two hours leave room
     * for a slow first boot while still excluding apps the user installed
     * after using the phone.
     */
    const val SETUP_WINDOW_MS: Long = 2 * 60 * 60 * 1000L

    /**
     * A timestamp that belongs to at least this many system-flagged packages
     * is a provisioning batch, not a single app. Vendors unpack factory apps
     * in batches, so this finds the real firmware timestamp even when a few
     * system packages carry an older individual date.
     */
    private const val FACTORY_BATCH_MIN = 3

    /**
     * Firmware components that may be installed after the initial setup and
     * therefore cannot be detected by install time. Keep this list narrow:
     * every entry is hidden from the normal app list.
     */
    val knownSystemPackages: Set<String> = setOf(
        // Android System Safety (installed by Play services on many phones).
        "com.google.android.safetycore",
        "com.google.android.safetycenter.resources",
        // Android System Key Verifier and Android Developer Verifier. Both
        // are delivered through Play as background firmware components and
        // have no system flag on many vendors.
        "com.google.android.contactkeys",
        "com.google.android.verifier",
        // Document Viewer / Files.
        "com.google.android.documentsui",
        // Xiaomi factory apps that live in /data/app without FLAG_SYSTEM.
        "com.xiaomi.scanner",
        "com.miui.calculator",
        "com.miui.securitycenter",
        "com.miui.securityadd",
        "com.miui.securitycore"
    )

    /**
     * Earliest timestamp shared by a factory provisioning batch. This is the
     * moment the firmware was provisioned, which is used as the anchor for
     * preinstalled data apps. If no batch is visible, the earliest system
     * install time is used as a fallback.
     */
    fun setupAnchor(packages: Iterable<PackageInfo>): Long {
        var earliest = Long.MAX_VALUE
        val batchCounts = HashMap<Long, Int>()

        packages.forEach { info ->
            val flags = info.applicationInfo?.flags ?: return@forEach
            if (flags and (
                    ApplicationInfo.FLAG_SYSTEM or
                        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    ) == 0
            ) {
                return@forEach
            }
            val installedAt = info.firstInstallTime
            if (installedAt <= 0) return@forEach
            if (installedAt < earliest) earliest = installedAt
            batchCounts[installedAt] = (batchCounts[installedAt] ?: 0) + 1
        }

        if (earliest == Long.MAX_VALUE) return 0L
        return batchCounts
            .filterValues { count -> count >= FACTORY_BATCH_MIN }
            .keys
            .minOrNull()
            ?: earliest
    }

    fun isSystem(
        packageName: String,
        flags: Int,
        firstInstallTime: Long,
        setupAnchor: Long
    ): Boolean {
        if (flags and (
                ApplicationInfo.FLAG_SYSTEM or
                    ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ) != 0
        ) {
            return true
        }
        if (packageName in knownSystemPackages) return true
        return setupAnchor > 0 &&
            firstInstallTime >= setupAnchor &&
            firstInstallTime <= setupAnchor + SETUP_WINDOW_MS
    }

    fun isSystem(info: PackageInfo, setupAnchor: Long): Boolean {
        val flags = info.applicationInfo?.flags ?: 0
        return isSystem(
            packageName = info.packageName,
            flags = flags,
            firstInstallTime = info.firstInstallTime,
            setupAnchor = setupAnchor
        )
    }
}
