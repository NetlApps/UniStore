package ru.updatehub.app.data

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

object SignatureUtils {

    /**
     * SHA-256 of the public AOSP "testkey" certificate. It is published in
     * the Android source tree, so anyone can sign an APK with it. Stores
     * such as GetApps and APKPure use it to repackage apps that are not
     * officially available for the user's region.
     */
    const val AOSP_TEST_KEY_SHA256 =
        "df08c9f2d809189d9d506497c15745a7395a41536efb433e3aee1aedbe11b261"

    private val hexChars = "0123456789abcdef".toCharArray()

    /**
     * True when the installed copy is signed with the public AOSP test key.
     * Such a copy has a different certificate than the official app, so no
     * store can update it: Google Play only shows "Open", while scraped
     * store pages keep advertising a newer version.
     */
    fun isPublicTestSignature(sha256: String?): Boolean {
        val normalized = sha256
            ?.replace(":", "")
            ?.replace(" ", "")
            ?.trim()
            ?.lowercase()
            ?: return false
        return normalized == AOSP_TEST_KEY_SHA256
    }

    /** SHA-256 of the first signing certificate, lowercase hex. */
    fun sha256(info: PackageInfo): String? = digest(info, "SHA-256")

    /** SHA-1 of the first signing certificate, lowercase hex without colons. */
    fun sha1(info: PackageInfo): String? = digest(info, "SHA-1")

    /**
     * Both fingerprints from one certificate lookup. Scanners use this to
     * avoid extracting and parsing the signer array twice per package.
     */
    fun hashes(info: PackageInfo): Pair<String?, String?> {
        val signature = signers(info).firstOrNull() ?: return null to null
        val bytes = signature.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
        return sha256 to sha1
    }

    private fun digest(info: PackageInfo, algorithm: String): String? {
        val signature = signers(info).firstOrNull() ?: return null
        return MessageDigest.getInstance(algorithm)
            .digest(signature.toByteArray())
            .toHex()
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            output[index * 2] = hexChars[value ushr 4]
            output[index * 2 + 1] = hexChars[value and 0x0F]
        }
        return String(output)
    }

    private fun signers(info: PackageInfo): Array<out Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signingInfo ->
                signingInfo.apkContentsSigners?.takeIf { it.isNotEmpty() }?.let { return it }
                signingInfo.signingCertificateHistory?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        @Suppress("DEPRECATION")
        return info.signatures.orEmpty()
    }
}
