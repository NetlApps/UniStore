package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionVersionTest {

    private val installed = InstalledApp(
        packageName = "com.example.app",
        label = "Example",
        versionName = "3.70.3",
        versionCode = 97303,
        isSystem = false,
        installedAt = 0L
    )

    private fun version(
        source: UpdateSource,
        versionName: String,
        versionCode: Long = 1L
    ) = StoreVersion(
        source = source,
        versionName = versionName,
        versionCode = versionCode,
        storeUrl = "https://example.com"
    )

    @Test
    fun directDownloadWinsOverNewerStoreVersion() {
        // The single "Обновить" button installs APKPure, so the card must show
        // APKPure's 3.71.0 even when Google Play already reports 3.72.0.
        val result = AppUpdateResult(
            packageName = installed.packageName,
            installedVersion = installed.versionName,
            status = UpdateStatus.UPDATE_AVAILABLE,
            best = version(UpdateSource.GOOGLE_PLAY, "3.72.0"),
            versions = listOf(
                version(UpdateSource.GOOGLE_PLAY, "3.72.0"),
                version(UpdateSource.APKPURE, "3.71.0", 97327)
            )
        )

        val action = result.actionVersion(installed)

        assertEquals(UpdateSource.APKPURE, action?.source)
        assertEquals("3.71.0", action?.versionName)
    }

    @Test
    fun newestDirectDownloadWinsBetweenStores() {
        val result = AppUpdateResult(
            packageName = installed.packageName,
            installedVersion = installed.versionName,
            status = UpdateStatus.UPDATE_AVAILABLE,
            versions = listOf(
                version(UpdateSource.APKPURE, "3.71.0"),
                version(UpdateSource.RUSTORE, "3.72.0")
            )
        )

        assertEquals("3.72.0", result.actionVersion(installed)?.versionName)
        assertEquals(UpdateSource.RUSTORE, result.actionVersion(installed)?.source)
    }

    @Test
    fun storeOnlyUpdateFallsBackToRedirectTarget() {
        val result = AppUpdateResult(
            packageName = installed.packageName,
            installedVersion = installed.versionName,
            status = UpdateStatus.UPDATE_AVAILABLE,
            best = version(UpdateSource.GOOGLE_PLAY, "3.72.0"),
            versions = listOf(version(UpdateSource.GOOGLE_PLAY, "3.72.0"))
        )

        val action = result.actionVersion(installed)

        assertEquals(UpdateSource.GOOGLE_PLAY, action?.source)
        assertEquals("3.72.0", action?.versionName)
        assertNull(result.directVersion(installed))
    }

    @Test
    fun actualInstalledVersionHasNoAction() {
        val result = AppUpdateResult(
            packageName = installed.packageName,
            installedVersion = installed.versionName,
            status = UpdateStatus.UP_TO_DATE,
            best = version(UpdateSource.APKPURE, "3.70.3"),
            versions = listOf(version(UpdateSource.APKPURE, "3.70.3"))
        )

        assertNull(result.actionVersion(installed))
    }

    @Test
    fun missingInstalledAppHasNoAction() {
        val result = AppUpdateResult(
            packageName = "com.example.app",
            installedVersion = "",
            status = UpdateStatus.NOT_INSTALLED,
            versions = listOf(version(UpdateSource.APKPURE, "3.71.0"))
        )

        assertNull(result.actionVersion(null))
    }
}
