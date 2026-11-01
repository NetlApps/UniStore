package ru.updatehub.app.data.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.updatehub.app.data.UpdateSource
import ru.updatehub.app.data.checkers.VersionDetails
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkCompatibilityTest {

    @Test
    fun newerAndroidRequirementIsRejectedWithBothVersions() {
        assertEquals(
            "APK требует Android 15, а на телефоне Android 14",
            compatibilityError(
                apkMinSdk = 35,
                apkTargetSdk = 35,
                apkAbis = emptySet(),
                deviceSdk = 34,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
            )
        )
    }

    @Test
    fun matchingAndroidRequirementIsAccepted() {
        assertNull(
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 34,
                apkAbis = emptySet(),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun nativeAbiMismatchIsRejected() {
        assertEquals(
            "APK несовместим с процессором телефона",
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 35,
                apkAbis = setOf("arm64-v8a", "armeabi-v7a"),
                deviceSdk = 35,
                supportedAbis = listOf("x86_64", "x86")
            )
        )
    }

    @Test
    fun oneSupportedAbiIsEnough() {
        assertNull(
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 35,
                apkAbis = setOf("arm64-v8a", "x86_64"),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
            )
        )
    }

    @Test
    fun thirtyTwoBitApkIsRejectedOnSixtyFourBitOnlyPhone() {
        assertEquals(
            "APK собран только для 32-битных процессоров, а телефон работает только с 64-битными",
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 35,
                apkAbis = setOf("armeabi-v7a"),
                deviceSdk = 36,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun storeMetadataRejectsThirtyTwoBitBuildBeforeDownload() {
        assertEquals(
            "APK собран только для 32-битных процессоров, а телефон работает только с 64-битными",
            metadataCompatibilityError(
                details = VersionDetails(
                    minSdk = 26,
                    architectures = setOf("armeabi-v7a"),
                    isBundle = false
                ),
                deviceSdk = 36,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun storeMetadataAcceptsMatchingBundleAndRejectsNewAndroid() {
        assertNull(
            metadataCompatibilityError(
                details = VersionDetails(
                    minSdk = 26,
                    architectures = setOf("arm64-v8a", "armeabi-v7a"),
                    isBundle = true
                ),
                deviceSdk = 36,
                supportedAbis = listOf("arm64-v8a")
            )
        )
        assertEquals(
            "APK требует Android 16, а на телефоне Android 15",
            metadataCompatibilityError(
                details = VersionDetails(minSdk = 36),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun unverifiedMetadataIsRequiredOnlyForApkPureOnSixtyFourBitOnlyPhones() {
        assertTrue(storeMetadataMustBeVerified(UpdateSource.APKPURE, listOf("arm64-v8a")))
        assertFalse(
            storeMetadataMustBeVerified(
                UpdateSource.APKPURE,
                listOf("arm64-v8a", "armeabi-v7a")
            )
        )
        assertFalse(storeMetadataMustBeVerified(UpdateSource.RUSTORE, listOf("arm64-v8a")))
        assertFalse(storeMetadataMustBeVerified(UpdateSource.APKPURE, emptyList()))
    }

    @Test
    fun sixtyFourBitOnlyDeviceNeedsNoThirtyTwoBitAbi() {
        assertTrue(is64BitOnlyDevice(listOf("arm64-v8a")))
        assertFalse(is64BitOnlyDevice(listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(is64BitOnlyDevice(listOf("x86_64", "x86")))
        assertFalse(is64BitOnlyDevice(emptyList()))
    }

    @Test
    fun apkWithoutNativeLibrariesIsAccepted() {
        assertNull(
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 35,
                apkAbis = emptySet(),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun oldTargetSdkIsRejectedOnAndroid14() {
        assertEquals(
            "APK устарел: Android 14 не устанавливает приложения ниже Android 6.0",
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 22,
                apkAbis = emptySet(),
                deviceSdk = 34,
                supportedAbis = listOf("arm64-v8a")
            )
        )
        assertNull(
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 23,
                apkAbis = emptySet(),
                deviceSdk = 34,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun oldTargetSdkIsRejectedOnAndroid15() {
        assertEquals(
            "APK устарел: Android 15 не устанавливает приложения ниже Android 7.0",
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 23,
                apkAbis = emptySet(),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        )
        assertNull(
            compatibilityError(
                apkMinSdk = 26,
                apkTargetSdk = 24,
                apkAbis = emptySet(),
                deviceSdk = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        )
    }

    @Test
    fun androidReleaseNamesAreReadable() {
        assertEquals("8.0", androidVersionName(26))
        assertEquals("12L", androidVersionName(32))
        assertEquals("14", androidVersionName(34))
        assertEquals("16", androidVersionName(36))
        assertEquals("API 99", androidVersionName(99))
    }

    @Test
    fun nativeAbisAreReadFromApkArchive() {
        val apk = File.createTempFile("compat-test", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                listOf(
                    "lib/arm64-v8a/libexample.so",
                    "lib/armeabi-v7a/libexample.so",
                    "classes.dex",
                    "AndroidManifest.xml"
                ).forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }

            assertEquals(
                setOf("arm64-v8a", "armeabi-v7a"),
                nativeAbis(apk)
            )
        } finally {
            apk.delete()
        }
    }
}
