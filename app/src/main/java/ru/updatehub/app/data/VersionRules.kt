package ru.updatehub.app.data

object VersionRules {

    /**
     * The numeric version name decides whether an update exists. versionCode is
     * only a tie-breaker for the same version name (for example build 100 vs
     * build 101). Stores sometimes use different versionCode schemes for the
     * same app, so comparing codes first creates fake updates.
     */
    fun isNewer(version: StoreVersion, app: InstalledApp): Boolean {
        val nameComparison = VersionComparator.compare(version.versionName, app.versionName)
        if (nameComparison != 0) return nameComparison > 0

        val code = version.versionCode
        return code != null && code > 0 && code > app.versionCode
    }
}
