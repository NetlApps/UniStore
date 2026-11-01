package ru.updatehub.app.data.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedPackageRemovesOnlyItsApkPartialDownloadAndBundle() {
        val directory = temporaryFolder.newFolder("updates")
        val apk = File(directory, "com.example.app.apk").apply { writeText("apk") }
        val partial = File(directory, "com.example.app.download").apply { writeText("part") }
        val bundle = File(directory, "com.example.app").apply {
            mkdirs()
            File(this, "base.apk").writeText("base")
            File(this, "config.arm64_v8a.apk").writeText("split")
        }
        val other = File(directory, "com.other.app.apk").apply { writeText("other") }

        deleteDownloadedPackage(directory, "com.example.app")

        assertFalse(apk.exists())
        assertFalse(partial.exists())
        assertFalse(bundle.exists())
        assertTrue(other.exists())
    }

    @Test
    fun startupCleanupRemovesEveryCachedPackage() {
        val directory = temporaryFolder.newFolder("updates")
        val first = File(directory, "com.example.app.apk").apply { writeText("apk") }
        val second = File(directory, "com.other.app.download").apply { writeText("part") }
        val bundle = File(directory, "com.third.app").apply {
            mkdirs()
            File(this, "base.apk").writeText("base")
        }

        clearDownloadedApks(directory)

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertFalse(bundle.exists())
    }
}
