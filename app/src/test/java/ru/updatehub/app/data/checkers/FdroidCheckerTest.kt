package ru.updatehub.app.data.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FdroidCheckerTest {

    private val packages = listOf(
        FdroidVersion("2.0.2", 2000052),
        FdroidVersion("2.0.1", 2000051),
        FdroidVersion("1.23.2", 1023052),
        FdroidVersion("1.22.0", 1022050)
    )

    @Test
    fun `prefers suggested stable version over the first prerelease`() {
        assertEquals(
            FdroidVersion("1.23.2", 1023052),
            pickFdroidVersion(suggested = 1023052, packages = packages)
        )
    }

    @Test
    fun `falls back to first usable entry when suggested is missing`() {
        assertEquals(
            FdroidVersion("2.0.2", 2000052),
            pickFdroidVersion(suggested = 9999999, packages = packages)
        )
        assertEquals(
            FdroidVersion("2.0.2", 2000052),
            pickFdroidVersion(suggested = -1, packages = packages)
        )
    }

    @Test
    fun `skips blank names`() {
        val list = listOf(
            FdroidVersion("", 3000000),
            FdroidVersion("1.23.2", 1023052)
        )
        assertEquals(
            FdroidVersion("1.23.2", 1023052),
            pickFdroidVersion(suggested = 1023052, packages = list)
        )
        assertEquals(
            FdroidVersion("1.23.2", 1023052),
            pickFdroidVersion(suggested = 1, packages = list)
        )
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(pickFdroidVersion(suggested = 1023052, packages = emptyList()))
    }

    @Test
    fun `page parser marks the latest beta build`() {
        val html = """
            <ul class="package-versions-list">
            <li class="package-version" id="latest">
                <div class="package-version-header">
                    <b>Version 2.0.2</b> (2000052)
                    <span class="beta-badge">beta</span>
                </div>
            </li>
            <li class="package-version">
                <div class="package-version-header">
                    <b>Version 1.23.2</b> (1023052)
                </div>
            </li>
            </ul>
        """.trimIndent()

        assertEquals(
            listOf(
                FdroidPageVersion("2.0.2", 2000052, beta = true),
                FdroidPageVersion("1.23.2", 1023052, beta = false)
            ),
            parseFdroidPageVersions(html)
        )
    }
}
