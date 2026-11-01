package ru.updatehub.app.data.install

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/** Metadata read directly from an APK's compiled AndroidManifest.xml. */
internal data class ApkPartManifest(
    val packageName: String,
    val versionCode: Long,
    val splitName: String?
)

/**
 * Archive inspection that does not rely on PackageManager.
 *
 * Android 9 implements getPackageArchiveInfo() through
 * PackageParser.parseMonolithicPackage(), which rejects split APKs. Config
 * splits therefore have to be read manually: the binary manifest gives the
 * package/version/split identity and the signing block (or the v1 PKCS#7
 * signature) gives the certificate fingerprint.
 */
internal object ApkArchiveInspector {

    fun readManifest(file: File): ApkPartManifest? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@runCatching null
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            BinaryManifestParser(bytes).parse()
        }
    }.getOrNull()

    /** SHA-256/SHA-1 of the signing certificate, lowercase hex. */
    fun certificateHashes(file: File): Pair<String?, String?> {
        val certificate = certificateFromSigningBlock(file) ?: certificateFromV1(file)
        if (certificate == null) return null to null
        val sha256 = MessageDigest.getInstance("SHA-256").digest(certificate)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(certificate)
        return sha256.toHex() to sha1.toHex()
    }

    private fun certificateFromV1(file: File): ByteArray? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { candidate ->
                !candidate.isDirectory &&
                    candidate.name.startsWith("META-INF/") &&
                    candidate.name.substringAfterLast('.', "")
                        .lowercase() in V1_SIGNATURE_EXTENSIONS
            } ?: return@runCatching null
            zip.getInputStream(entry).use { input ->
                CertificateFactory.getInstance("X.509")
                    .generateCertificates(input)
                    .firstOrNull()
                    ?.encoded
            }
        }
    }.getOrNull()

    /**
     * Extracts the first signer certificate from the APK Signing Block.
     * Scheme v3 is checked before v2 because after a key rotation v2 still
     * carries the old signer while v3 carries the current one.
     */
    private fun certificateFromSigningBlock(file: File): ByteArray? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val fileSize = raf.length()
                if (fileSize < 32) return@runCatching null

                val tailSize = minOf(fileSize, 65_557L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(fileSize - tailSize)
                raf.readFully(tail)
                val eocdIndex = findEndOfCentralDirectory(tail) ?: return@runCatching null
                val eocdOffset = fileSize - tailSize + eocdIndex

                raf.seek(eocdOffset + 16)
                val centralDirectoryOffset = readUInt32(raf)
                if (centralDirectoryOffset < 32 || centralDirectoryOffset >= fileSize) {
                    return@runCatching null
                }

                raf.seek(centralDirectoryOffset - 24)
                val footerSize = readUInt64(raf) ?: return@runCatching null
                if (footerSize <= 0 || footerSize > centralDirectoryOffset - 8) {
                    return@runCatching null
                }
                val blockOffset = centralDirectoryOffset - footerSize - 8
                raf.seek(blockOffset)
                val headerSize = readUInt64(raf) ?: return@runCatching null
                if (headerSize != footerSize) return@runCatching null

                var position = blockOffset + 8
                val pairsEnd = centralDirectoryOffset - 24
                val schemes = linkedMapOf<Int, ByteArray>()
                while (position + 12 <= pairsEnd) {
                    raf.seek(position)
                    val pairSize = readUInt64(raf) ?: break
                    if (pairSize < 4 || position + 8 + pairSize > pairsEnd) break
                    val id = readUInt32(raf).toInt()
                    if (id in SIGNATURE_SCHEME_IDS) {
                        val value = ByteArray((pairSize - 4).toInt())
                        raf.readFully(value)
                        schemes[id] = value
                    }
                    position += 8 + pairSize
                }

                for (id in SIGNATURE_SCHEME_PRIORITY) {
                    val value = schemes[id] ?: continue
                    firstCertificate(value)?.let { return@runCatching it }
                }
                null
            }
        }.getOrNull()
    }

    private fun firstCertificate(schemeValue: ByteArray): ByteArray? = runCatching {
        val buffer = ByteBuffer.wrap(schemeValue).order(ByteOrder.LITTLE_ENDIAN)
        val signersLength = buffer.int
        if (signersLength <= 0 || signersLength > buffer.remaining()) return@runCatching null
        val signersEnd = buffer.position() + signersLength
        if (buffer.position() + 4 > signersEnd) return@runCatching null

        val signerLength = buffer.int
        if (signerLength <= 0 || signerLength > signersEnd - buffer.position()) {
            return@runCatching null
        }
        val signerEnd = buffer.position() + signerLength
        if (buffer.position() + 4 > signerEnd) return@runCatching null

        val signedDataLength = buffer.int
        if (signedDataLength <= 0 || signedDataLength > signerEnd - buffer.position()) {
            return@runCatching null
        }
        val signedDataEnd = buffer.position() + signedDataLength

        val digestsLength = buffer.int
        if (digestsLength < 0 || digestsLength > signedDataEnd - buffer.position()) {
            return@runCatching null
        }
        buffer.position(buffer.position() + digestsLength)

        val certificatesLength = buffer.int
        if (certificatesLength <= 0 ||
            certificatesLength > signedDataEnd - buffer.position()
        ) {
            return@runCatching null
        }
        val certificatesEnd = buffer.position() + certificatesLength
        if (buffer.position() + 4 > certificatesEnd) return@runCatching null
        val certificateLength = buffer.int
        if (certificateLength <= 0 ||
            certificateLength > certificatesEnd - buffer.position()
        ) {
            return@runCatching null
        }
        ByteArray(certificateLength).also { buffer.get(it) }
    }.getOrNull()

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
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
        return null
    }

    private fun readUInt32(raf: RandomAccessFile): Long {
        val value = raf.readInt().toLong() and 0xFFFFFFFFL
        return value
    }

    private fun readUInt64(raf: RandomAccessFile): Long? {
        val high = raf.readInt().toLong() and 0xFFFFFFFFL
        val low = raf.readInt().toLong() and 0xFFFFFFFFL
        if (high > 0x7FFFFFFFL) return null
        return (high shl 32) or low
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private val V1_SIGNATURE_EXTENSIONS = setOf("rsa", "dsa", "ec")

    private const val APK_SIGNATURE_SCHEME_V2 = 0x7109871a
    private const val APK_SIGNATURE_SCHEME_V3 = 0xf05368c0.toInt()
    private const val APK_SIGNATURE_SCHEME_V31 = 0x1b93ad61

    private val SIGNATURE_SCHEME_IDS = setOf(
        APK_SIGNATURE_SCHEME_V2,
        APK_SIGNATURE_SCHEME_V3,
        APK_SIGNATURE_SCHEME_V31
    )

    /** Newest scheme first: it carries the currently active signer. */
    private val SIGNATURE_SCHEME_PRIORITY = listOf(
        APK_SIGNATURE_SCHEME_V31,
        APK_SIGNATURE_SCHEME_V3,
        APK_SIGNATURE_SCHEME_V2
    )
}

/**
 * Minimal reader for Android's compiled (binary) XML manifests. Only the
 * first <manifest> element is needed, which contains package, split and
 * versionCode.
 */
private class BinaryManifestParser(private val data: ByteArray) {

    private val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()

    fun parse(): ApkPartManifest? {
        if (data.size < 8) return null
        if (buffer.getShort(0).toInt() and 0xFFFF != RES_XML_TYPE) return null
        val totalSize = buffer.getInt(4)
        if (totalSize <= 8 || totalSize > data.size) return null

        var offset = 8
        while (offset + 8 <= totalSize) {
            val type = buffer.getShort(offset).toInt() and 0xFFFF
            val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
            val size = buffer.getInt(offset + 4)
            if (size < 8 || offset + size > totalSize) return null
            when (type) {
                RES_STRING_POOL_TYPE -> {
                    strings = readStringPool(offset, headerSize)
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    val element = readStartElement(offset, headerSize) ?: return null
                    if (element.name == "manifest") {
                        val packageName = element.string("package") ?: return null
                        val versionCode = element.integer("versionCode") ?: 0L
                        val versionCodeMajor = element.integer("versionCodeMajor") ?: 0L
                        return ApkPartManifest(
                            packageName = packageName,
                            versionCode = (versionCodeMajor shl 32) or
                                (versionCode and 0xFFFFFFFFL),
                            splitName = element.string("split")
                        )
                    }
                }
            }
            offset += size
        }
        return null
    }

    private fun readStringPool(offset: Int, headerSize: Int): List<String> {
        val stringCount = buffer.getInt(offset + 8)
        if (stringCount <= 0 || stringCount > MAX_STRINGS) return emptyList()
        val flags = buffer.getInt(offset + 16)
        val stringsStart = buffer.getInt(offset + 20)
        val utf8 = flags and FLAG_UTF8 != 0
        val offsetsStart = offset + headerSize
        if (offsetsStart + stringCount * 4 > data.size) return emptyList()

        return List(stringCount) { index ->
            val relative = buffer.getInt(offsetsStart + index * 4)
            val position = offset + stringsStart + relative
            if (position < 0 || position >= data.size) return@List ""
            if (utf8) readUtf8(position) else readUtf16(position)
        }
    }

    private fun readUtf8(start: Int): String {
        var position = start
        var length = data[position].toInt() and 0xFF
        position++
        if (length and 0x80 != 0) {
            length = ((length and 0x7F) shl 8) or (data[position].toInt() and 0xFF)
            position++
        }
        var byteLength = data[position].toInt() and 0xFF
        position++
        if (byteLength and 0x80 != 0) {
            byteLength = ((byteLength and 0x7F) shl 8) or
                (data[position].toInt() and 0xFF)
            position++
        }
        if (byteLength < 0 || position + byteLength > data.size) return ""
        return String(data, position, byteLength, Charsets.UTF_8)
    }

    private fun readUtf16(start: Int): String {
        var position = start
        var length = buffer.getShort(position).toInt() and 0xFFFF
        position += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or
                (buffer.getShort(position).toInt() and 0xFFFF)
            position += 2
        }
        if (length < 0 || position + length * 2 > data.size) return ""
        val chars = CharArray(length)
        for (index in 0 until length) {
            chars[index] = buffer.getShort(position + index * 2).toChar()
        }
        return String(chars)
    }

    private fun readStartElement(offset: Int, headerSize: Int): Element? {
        val extension = offset + headerSize
        if (extension + 20 > data.size) return null
        val nameIndex = buffer.getInt(extension + 4)
        val attributeStart = buffer.getShort(extension + 8).toInt() and 0xFFFF
        val attributeSize = buffer.getShort(extension + 10).toInt() and 0xFFFF
        val attributeCount = buffer.getShort(extension + 12).toInt() and 0xFFFF
        if (attributeSize < 20) return null

        val attributes = HashMap<String, Attribute>(attributeCount)
        // attributeStart is relative to the start of ResXMLTree_attrExt,
        // which itself begins at the end of the node header.
        var position = extension + attributeStart
        repeat(attributeCount) {
            if (position + 20 > data.size) return null
            val attributeName = stringAt(buffer.getInt(position + 4))
            val rawValue = buffer.getInt(position + 8)
            val dataType = data[position + 15].toInt() and 0xFF
            val dataValue = buffer.getInt(position + 16)
            if (attributeName != null) {
                attributes[attributeName] = Attribute(
                    text = when {
                        rawValue >= 0 -> stringAt(rawValue)
                        dataType == TYPE_STRING -> stringAt(dataValue)
                        else -> null
                    },
                    integer = when (dataType) {
                        TYPE_INT_DEC, TYPE_INT_HEX -> dataValue.toLong() and 0xFFFFFFFFL
                        else -> null
                    }
                )
            }
            position += attributeSize
        }
        return Element(stringAt(nameIndex), attributes)
    }

    private fun stringAt(index: Int): String? = strings.getOrNull(index)

    private data class Attribute(val text: String?, val integer: Long?)

    private data class Element(
        val name: String?,
        private val attributes: Map<String, Attribute>
    ) {
        fun string(name: String): String? = attributes[name]?.text

        fun integer(name: String): Long? = attributes[name]?.integer
    }

    private companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val FLAG_UTF8 = 0x100
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val MAX_STRINGS = 500_000
    }
}
