package ru.updatehub.app.data

object VersionRules {

    /**
     * Characters that appear in real Android version names: numbers, build
     * flavours, channel suffixes and occasional parentheses. Anything else
     * (HTML fragments, spaces in the middle, quotes) is never a version.
     */
    private val allowedCharacters = Regex("^[0-9A-Za-z][0-9A-Za-z0-9._+()\\- ]{0,47}$")

    // APKPure republishes ChromeOS builds in the same history list. They use
    // a synthetic version such as "2000000000.0.0-CrOS" that would otherwise
    // look like the newest release for every app.
    private val chromeOsMarker = Regex(
        """(^|[-_.\s])(cros|chromeos|chromebook)([-_.\s]|$)""",
        RegexOption.IGNORE_CASE
    )

    // Scraper placeholders that occasionally leak into store metadata.
    private val junkMarker = Regex(
        """(?i)(^|[-_.\s])(varies|unknown|undefined|null|not[ _-]?available)([-_.\s]|$)"""
    )

    private val numericPart = Regex("""\d+""")

    /**
     * Rejects version strings that cannot be a normal phone release. The
     * strongest guard is the installed version: a store that suddenly jumps
     * from 2.x to 2000000000.x is serving another platform or junk metadata,
     * not an update. Applied to every source, not only APKPure.
     */
    fun isPlausible(version: StoreVersion, app: InstalledApp): Boolean {
        val name = version.versionName.trim()
        if (name.isBlank() || !allowedCharacters.matches(name)) return false
        if (chromeOsMarker.containsMatchIn(name)) return false
        if (junkMarker.containsMatchIn(name)) return false

        val candidateNumbers = numericPart.findAll(name)
            .mapNotNull { it.value.toLongOrNull() }
            .take(7)
            .toList()
        if (candidateNumbers.isEmpty() || candidateNumbers.size > 6) return false

        // 2,000,000,000 is the synthetic code APKPure uses for ChromeOS
        // packages (and the highest value Play accepts), never a regular APK.
        if (version.versionCode != null && version.versionCode >= 2_000_000_000L) {
            return false
        }

        val installedNumbers = numericPart.findAll(app.versionName)
            .mapNotNull { it.value.toLongOrNull() }
            .take(7)
            .toList()
        if (installedNumbers.isNotEmpty()) {
            val installedLead = installedNumbers.first()
            val candidateLead = candidateNumbers.first()
            // Allow calendar-style jumps (2023 -> 2024) but reject a leading
            // component two orders of magnitude above the installed build.
            val ceiling = maxOf(1_000L, installedLead * 100 + 100)
            if (candidateLead > ceiling) return false
        }

        return true
    }

    /**
     * The numeric version name decides whether an update exists. versionCode is
     * only a tie-breaker for the same version name (for example build 100 vs
     * build 101). Stores sometimes use different versionCode schemes for the
     * same app, so comparing codes first creates fake updates.
     */
    fun isNewer(version: StoreVersion, app: InstalledApp): Boolean {
        if (!isPlausible(version, app)) return false

        val nameComparison = VersionComparator.compare(version.versionName, app.versionName)
        if (nameComparison != 0) return nameComparison > 0

        val code = version.versionCode
        return code != null && code > 0 && code > app.versionCode
    }

    /**
     * Keeps a cached per-store check consistent after the app itself changed.
     * Results stay on disk until the next check, so a store that offered the
     * version the user has just installed must not keep advertising it.
     */
    fun reconcileSourceCheck(
        check: SourceCheck,
        app: InstalledApp,
        versions: List<StoreVersion>,
        isBlocked: (StoreVersion) -> Boolean = { false }
    ): SourceCheck {
        if (check.status != SourceCheckStatus.UPDATE_FOUND) return check
        val candidate = versions.firstOrNull { version ->
            version.source == check.source &&
                (check.versionName == null || version.versionName == check.versionName)
        }
        if (candidate == null || !isNewer(candidate, app)) {
            return check.copy(
                status = SourceCheckStatus.UP_TO_DATE,
                message = "Не новее установленной версии"
            )
        }
        if (isBlocked(candidate)) {
            return check.copy(
                status = SourceCheckStatus.INCOMPATIBLE,
                message = "Android отклонил установку этой версии"
            )
        }
        return check
    }
}
