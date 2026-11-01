package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun equalVersionsWithDifferentPartCounts() {
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("2.26.41.77", "2.26.41.77"))
    }

    @Test
    fun numericPartsAreComparedAsNumbers() {
        assertTrue(VersionComparator.isNewer("1.2.10", "1.2.9"))
        assertTrue(VersionComparator.isNewer("8.201", "8.99"))
        assertTrue(VersionComparator.isNewer("2.26.41.77", "2.26.41.7"))
        assertFalse(VersionComparator.isNewer("1.2.9", "1.2.10"))
    }

    @Test
    fun largeNumbersDoNotOverflow() {
        assertTrue(
            VersionComparator.isNewer(
                "2026.123456789012345678901234567890",
                "2026.123456789012345678901234567889"
            )
        )
    }

    @Test
    fun channelSuffixesAreIgnored() {
        assertEquals(0, VersionComparator.compare("1.0", "1.0-beta"))
        assertEquals(0, VersionComparator.compare("1.0", "1.0-rc"))
        assertEquals(
            0,
            VersionComparator.compare("28.0.0.9905-gms", "28.0.0.9905-rustore")
        )
    }

    @Test
    fun numericSuffixPartsStillCount() {
        assertEquals(0, VersionComparator.compare("1.0-Beta", "1.0-beta"))
        assertTrue(VersionComparator.isNewer("1.0-rc2", "1.0-rc1"))
    }

    @Test
    fun numericIsNewerThanAlpha() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.beta"))
    }

    @Test
    fun blankInstalledVersionIsOlderThanAnyRemote() {
        assertTrue(VersionComparator.isNewer("1.0", ""))
    }
}
