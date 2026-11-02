package ru.updatehub.app.data.install

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkArchiveInspectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsCertificateFromLittleEndianSigningBlock() {
        // Split APKs are often parsed only by the manual inspector, because
        // PackageManager rejects them. ZIP offsets and signing-block sizes are
        // little-endian; reading them as big-endian returned null for every
        // valid split and blocked XAPK installs.
        val certificate = ByteArray(96) { index -> (index * 7).toByte() }
        val apk = createSignedApk(certificate)

        val (sha256, sha1) = ApkArchiveInspector.certificateHashes(apk)

        assertEquals(digest("SHA-256", certificate), sha256)
        assertEquals(digest("SHA-1", certificate), sha1)
    }

    private fun createSignedApk(certificate: ByteArray): File {
        val unsigned = temporaryFolder.newFile("unsigned.apk")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }

        val original = unsigned.readBytes()
        val eocd = findEndOfCentralDirectory(original)
        val centralDirectoryOffset = readUInt32(original, eocd + 16).toInt()
        val pair = v2Pair(certificate)
        val blockSize = pair.size + 24L

        val block = ByteBuffer.allocate(8 + pair.size + 24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(blockSize)
            .put(pair)
            .putLong(blockSize)
            .put("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
            .array()

        val signed = ByteArray(original.size + block.size)
        System.arraycopy(original, 0, signed, 0, centralDirectoryOffset)
        System.arraycopy(block, 0, signed, centralDirectoryOffset, block.size)
        System.arraycopy(
            original,
            centralDirectoryOffset,
            signed,
            centralDirectoryOffset + block.size,
            original.size - centralDirectoryOffset
        )
        // The signing block sits between the entries and the central
        // directory, so the stored central directory offset moves forward.
        writeUInt32(
            signed,
            eocd + block.size + 16,
            (centralDirectoryOffset + block.size).toLong()
        )

        return temporaryFolder.newFile("signed.apk").apply { writeBytes(signed) }
    }

    private fun v2Pair(certificate: ByteArray): ByteArray {
        val digests = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .array()
        val certificates = ByteBuffer.allocate(8 + certificate.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4 + certificate.size)
            .putInt(certificate.size)
            .put(certificate)
            .array()
        val signedContent = digests + certificates
        val signedData = ByteBuffer.allocate(4 + signedContent.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(signedContent.size)
            .put(signedContent)
            .array()
        val signer = ByteBuffer.allocate(4 + signedData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(signedData.size)
            .put(signedData)
            .array()
        val value = ByteBuffer.allocate(4 + signer.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(signer.size)
            .put(signer)
            .array()

        return ByteBuffer.allocate(12 + value.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong((4 + value.size).toLong())
            .putInt(APK_SIGNATURE_SCHEME_V2)
            .put(value)
            .array()
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        var index = bytes.size - 22
        val minimum = maxOf(0, bytes.size - 65_557)
        while (index >= minimum) {
            if (bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x05.toByte() &&
                bytes[index + 3] == 0x06.toByte()
            ) {
                return index
            }
            index--
        }
        error("ZIP end of central directory not found")
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFFFFFFL

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value.toInt())
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val APK_SIGNATURE_SCHEME_V2 = 0x7109871a
    }
}
