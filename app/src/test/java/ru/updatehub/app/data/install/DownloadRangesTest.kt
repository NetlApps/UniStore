package ru.updatehub.app.data.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRangesTest {

    private val mb = 1024L * 1024L

    @Test
    fun smallFileUsesOneConnection() {
        assertTrue(splitIntoRanges(3 * mb, maxParts = 4).isEmpty())
        assertTrue(splitIntoRanges(4 * mb, maxParts = 4).isEmpty())
        assertTrue(splitIntoRanges(0, maxParts = 4).isEmpty())
    }

    @Test
    fun eightMegabytesSplitIntoTwoEqualParts() {
        val ranges = splitIntoRanges(8 * mb, maxParts = 4)
        assertEquals(2, ranges.size)
        assertEquals(ByteRange(0, 4 * mb - 1), ranges[0])
        assertEquals(ByteRange(4 * mb, 8 * mb - 1), ranges[1])
    }

    @Test
    fun twelveMegabytesUseThreeParts() {
        val ranges = splitIntoRanges(12 * mb, maxParts = 4)
        assertEquals(3, ranges.size)
        assertEquals(12 * mb, ranges.sumOf { it.length })
        assertEquals(0L, ranges.first().start)
        assertEquals(12 * mb - 1, ranges.last().endInclusive)
    }

    @Test
    fun rangesCoverLargeFileWithoutGapsOrOverlaps() {
        val total = 1_851_145_031L
        val ranges = splitIntoRanges(total, maxParts = 4)
        assertEquals(4, ranges.size)
        assertEquals(total, ranges.sumOf { it.length })
        assertEquals(0L, ranges.first().start)
        assertEquals(total - 1, ranges.last().endInclusive)
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endInclusive + 1, right.start)
        }
    }

    @Test
    fun maxConnectionsOneDisablesParallelDownload() {
        assertTrue(splitIntoRanges(100 * mb, maxParts = 1).isEmpty())
    }

    @Test
    fun remainderIsDistributedAcrossRanges() {
        val total = 10 * mb + 1
        val ranges = splitIntoRanges(total, maxParts = 4, minPartBytes = 1)
        assertEquals(4, ranges.size)
        assertEquals(total, ranges.sumOf { it.length })
        ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endInclusive + 1, right.start)
        }
    }

    @Test
    fun parsesContentRangeWithAndWithoutTotal() {
        assertEquals(
            ContentRange(0, 1023, 1_851_145_031L),
            parseContentRange("bytes 0-1023/1851145031")
        )
        assertEquals(
            ContentRange(100, 199, null),
            parseContentRange("BYTES 100-199/*")
        )
    }

    @Test
    fun rejectsMalformedContentRange() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("items 0-10/20"))
        assertNull(parseContentRange("bytes 10-0/20"))
        assertNull(parseContentRange("bytes 0-10"))
    }

    @Test
    fun parallelDownloadNeedsLengthAndRangeSupport() {
        assertFalse(supportsRangeDownload(null, "bytes", 4))
        assertFalse(supportsRangeDownload(12 * mb, null, 4))
        assertFalse(supportsRangeDownload(12 * mb, "none", 4))
        assertFalse(supportsRangeDownload(1 * mb, "bytes", 4))
        assertTrue(supportsRangeDownload(12 * mb, "bytes", 4))
        assertTrue(supportsRangeDownload(12 * mb, "Bytes", 4))
    }
}
