package ru.updatehub.app.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAppDetectorTest {

    private val setup = 1_700_000_000_000L // arbitrary setup timestamp

    @Test
    fun `system flag is always a system app`() {
        assertTrue(
            SystemAppDetector.isSystem(
                packageName = "com.example.user",
                flags = FLAG_SYSTEM,
                firstInstallTime = setup + 90L * 24 * 60 * 60 * 1000,
                setupAnchor = setup
            )
        )
    }

    @Test
    fun `updated system app stays system`() {
        assertTrue(
            SystemAppDetector.isSystem(
                packageName = "com.example.updated",
                flags = FLAG_UPDATED_SYSTEM_APP,
                firstInstallTime = setup,
                setupAnchor = setup
            )
        )
    }

    @Test
    fun `preinstall installed during setup is system without a flag`() {
        assertTrue(
            SystemAppDetector.isSystem(
                packageName = "com.xiaomi.scanner",
                flags = 0,
                firstInstallTime = setup + 3_000L,
                setupAnchor = setup
            )
        )
    }

    @Test
    fun `normal app installed after the setup window is not system`() {
        assertFalse(
            SystemAppDetector.isSystem(
                packageName = "com.github.android",
                flags = 0,
                firstInstallTime = setup + SystemAppDetector.SETUP_WINDOW_MS + 1L,
                setupAnchor = setup
            )
        )
    }

    @Test
    fun `known late system component is system without a flag`() {
        assertTrue(
            SystemAppDetector.isSystem(
                packageName = "com.google.android.safetycore",
                flags = 0,
                firstInstallTime = setup + 75L * 24 * 60 * 60 * 1000,
                setupAnchor = setup
            )
        )
    }

    @Test
    fun `key and developer verifier are system components on every vendor`() {
        listOf(
            "com.google.android.contactkeys",
            "com.google.android.verifier"
        ).forEach { packageName ->
            assertTrue(
                packageName,
                SystemAppDetector.isSystem(
                    packageName = packageName,
                    flags = 0,
                    firstInstallTime = setup + 150L * 24 * 60 * 60 * 1000,
                    setupAnchor = setup
                )
            )
        }
    }

    @Test
    fun `setup anchor prefers a timestamp shared by a factory batch`() {
        val anchor = setup
        val packages = listOf(
            packageInfo("com.example.old.overlay", FLAG_SYSTEM, anchor - 90L * 24 * 60 * 60 * 1000),
            packageInfo("com.example.system.one", FLAG_SYSTEM, anchor),
            packageInfo("com.example.system.two", FLAG_SYSTEM, anchor),
            packageInfo("com.example.system.three", FLAG_SYSTEM, anchor),
            packageInfo("com.example.user", 0, anchor + 60L * 24 * 60 * 60 * 1000)
        )

        assertEquals(anchor, SystemAppDetector.setupAnchor(packages))
    }

    @Test
    fun `setup anchor falls back to the earliest system install`() {
        val packages = listOf(
            packageInfo("com.example.system.one", FLAG_SYSTEM, setup),
            packageInfo("com.example.system.two", FLAG_SYSTEM, setup + 5_000L),
            packageInfo("com.example.user", 0, setup + 10_000L)
        )

        assertEquals(setup, SystemAppDetector.setupAnchor(packages))
    }

    @Test
    fun `no anchor means only flags and allowlist count`() {
        assertFalse(
            SystemAppDetector.isSystem(
                packageName = "com.example.user",
                flags = 0,
                firstInstallTime = setup,
                setupAnchor = 0L
            )
        )
    }

    private fun packageInfo(packageName: String, flags: Int, installedAt: Long): PackageInfo =
        PackageInfo().apply {
            this.packageName = packageName
            firstInstallTime = installedAt
            applicationInfo = ApplicationInfo().apply { this.flags = flags }
        }

    private companion object {
        const val FLAG_SYSTEM = 1
        const val FLAG_UPDATED_SYSTEM_APP = 1 shl 7
    }
}
