package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncompatibleUpdateKeyTest {

    private val packageName = "com.example.app"
    private val versionName = "3.9.7"

    @Test
    fun newKeyIncludesStore() {
        assertEquals(
            "com.example.app|3.9.7|apkpure",
            incompatibleUpdateKey(packageName, versionName, UpdateSource.APKPURE)
        )
        assertEquals(
            "com.example.app|3.9.7",
            incompatibleUpdateKey(packageName, versionName)
        )
    }

    @Test
    fun brokenApkPureBuildDoesNotHideGooglePlayUpdate() {
        // A repackaged APKPure XAPK can fail while the Play build of the same
        // version installs normally. Hiding both was the bug seen on device.
        val keys = setOf(
            incompatibleUpdateKey(packageName, versionName, UpdateSource.APKPURE)
        )

        assertTrue(
            isIncompatibleUpdate(
                keys,
                packageName,
                versionName,
                UpdateSource.APKPURE
            )
        )
        assertFalse(
            isIncompatibleUpdate(
                keys,
                packageName,
                versionName,
                UpdateSource.GOOGLE_PLAY
            )
        )
        assertFalse(
            isIncompatibleUpdate(
                keys,
                packageName,
                versionName,
                null
            )
        )
    }

    @Test
    fun legacyKeyStillHidesEverySource() {
        // Results saved by 1.1.9 and earlier have no store part. They must
        // keep working after the update instead of offering the same broken
        // version again.
        val keys = setOf(incompatibleUpdateKey(packageName, versionName))

        UpdateSource.entries.forEach { source ->
            assertTrue(
                "Legacy key must block ${source.key}",
                isIncompatibleUpdate(keys, packageName, versionName, source)
            )
        }
        assertTrue(isIncompatibleUpdate(keys, packageName, versionName, null))
    }

    @Test
    fun anotherVersionIsNotBlocked() {
        val keys = setOf(
            incompatibleUpdateKey(packageName, versionName, UpdateSource.APKPURE)
        )

        assertFalse(
            isIncompatibleUpdate(
                keys,
                packageName,
                "3.9.8",
                UpdateSource.APKPURE
            )
        )
        assertFalse(
            isIncompatibleUpdate(
                keys,
                "com.example.other",
                versionName,
                UpdateSource.APKPURE
            )
        )
    }
}
