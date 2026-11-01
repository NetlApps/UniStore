package ru.updatehub.app.data.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureVerificationTest {

    private val sha256 = "a".repeat(64)
    private val otherSha256 = "b".repeat(64)
    private val sha1 = "c".repeat(40)
    private val otherSha1 = "d".repeat(40)

    private val mismatch = "Подпись APK не совпадает с установленным приложением"
    private val unverified = "Не удалось проверить подпись APK"

    @Test
    fun matchingSha256IsAcceptedCaseInsensitively() {
        assertNull(
            signatureVerificationError(
                installedSha256 = sha256,
                installedSha1 = sha1,
                apkSha256 = sha256.uppercase(),
                apkSha1 = sha1.uppercase()
            )
        )
    }

    @Test
    fun realKeyMismatchIsRejected() {
        // Regression from an on-device install: Aegis 3.4.2 from GitHub was
        // offered an F-Droid 3.4.3 APK signed by a different key. The system
        // installer only said "Приложение не установлено".
        assertEquals(
            mismatch,
            signatureVerificationError(
                installedSha256 = sha256,
                installedSha1 = sha1,
                apkSha256 = otherSha256,
                apkSha1 = otherSha1
            )
        )
    }

    @Test
    fun sha1IsUsedWhenSha256IsMissing() {
        assertNull(
            signatureVerificationError(
                installedSha256 = null,
                installedSha1 = sha1,
                apkSha256 = otherSha256,
                apkSha1 = sha1
            )
        )
        assertEquals(
            mismatch,
            signatureVerificationError(
                installedSha256 = null,
                installedSha1 = sha1,
                apkSha256 = otherSha256,
                apkSha1 = otherSha1
            )
        )
    }

    @Test
    fun missingApkFingerprintFailsClosed() {
        assertEquals(
            unverified,
            signatureVerificationError(
                installedSha256 = sha256,
                installedSha1 = sha1,
                apkSha256 = null,
                apkSha1 = null
            )
        )
    }

    @Test
    fun missingInstalledFingerprintFailsClosed() {
        assertEquals(
            unverified,
            signatureVerificationError(
                installedSha256 = null,
                installedSha1 = null,
                apkSha256 = sha256,
                apkSha1 = sha1
            )
        )
    }
}
