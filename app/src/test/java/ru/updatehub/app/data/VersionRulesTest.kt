package ru.updatehub.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionRulesTest {

    private fun app(
        versionName: String,
        versionCode: Long
    ) = InstalledApp(
        packageName = "com.example.app",
        label = "Example",
        versionName = versionName,
        versionCode = versionCode,
        isSystem = false,
        installedAt = 0L
    )

    private fun store(
        versionName: String,
        versionCode: Long? = null
    ) = StoreVersion(
        source = UpdateSource.GETAPPS,
        versionName = versionName,
        versionCode = versionCode,
        storeUrl = ""
    )

    @Test
    fun differentCodeSchemesDoNotCreateFakeUpdates() {
        // Real GetApps case: 12.3.7.3 has a much larger versionCode than 17.0.3.26.
        assertFalse(
            VersionRules.isNewer(
                store("G-12.3.7.3-HD", 120_307_003L),
                app("G-17.0.3.26", 17_000_326L)
            )
        )
        assertFalse(
            VersionRules.isNewer(
                store("1.232.0", 1_232_000L),
                app("1.282.0", 1_282_000L)
            )
        )
    }

    @Test
    fun newerNameWinsEvenWithSmallerCode() {
        assertTrue(
            VersionRules.isNewer(
                store("2.0.0", 10L),
                app("1.9.9", 999L)
            )
        )
    }

    @Test
    fun codeBreaksTiesForSameName() {
        assertTrue(
            VersionRules.isNewer(store("1.0.0", 101L), app("1.0.0", 100L))
        )
        assertFalse(
            VersionRules.isNewer(store("1.0.0", 100L), app("1.0.0", 100L))
        )
    }

    @Test
    fun olderStoreVersionIsNeverAnUpdate() {
        // Real regression: the installed build was 3.4 while a store or a
        // cached result still advertised 3.3/3.3.4.
        assertFalse(
            VersionRules.isNewer(store("3.3", 30300L), app("3.4", 30400L))
        )
        assertFalse(
            VersionRules.isNewer(store("3.3.4", 30304L), app("3.4", 30400L))
        )
        assertTrue(
            VersionRules.isNewer(store("3.5", 30500L), app("3.4", 30400L))
        )
    }

    @Test
    fun channelSuffixOnlyIsNotAnUpdate() {
        assertFalse(
            VersionRules.isNewer(
                store("28.0.0.9905-rustore", 9905L),
                app("28.0.0.9905-gms", 9905L)
            )
        )
    }
}
