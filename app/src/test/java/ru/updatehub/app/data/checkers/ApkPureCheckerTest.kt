package ru.updatehub.app.data.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkPureCheckerTest {

    @Test
    fun `extracts version from Auto ru CDN url`() {
        val url =
            "https://data.winudf.com/XAPK/cnUuYXV0by5hcmFfMTAxMTgyXzQ4ZWY4ODIx" +
                "?_p=cnUuYXV0by5hcmE%3D" +
                "&filename=%D0%90%D0%B2%D1%82%D0%BE.%D1%80%D1%83%3A+" +
                "%D0%BA%D1%83%D0%BF%D0%B8%D1%82%D1%8C+%D0%B8+" +
                "%D0%BF%D1%80%D0%BE%D0%B4%D0%B0%D1%82%D1%8C+" +
                "%D0%B0%D0%B2%D1%82%D0%BE_26.41.0_APKPure.xapk" +
                "&full_size=106383439&package_name=ru.auto.ara"

        assertEquals("26.41.0", versionFromApkPureUrl(url))
    }

    @Test
    fun `extracts version from ordinary apk filename`() {
        val url = "https://data.winudf.com/APK/example?filename=WhatsApp+Messenger_2.25.4.75_APKPure.apk"

        assertEquals("2.25.4.75", versionFromApkPureUrl(url))
    }

    @Test
    fun `extracts version with hyphens and plus signs`() {
        val url = "https://data.winudf.com/APK/example?filename=F-Droid_1.19.1-beta%2B200_APKPure.apk"

        assertEquals("1.19.1-beta+200", versionFromApkPureUrl(url))
    }

    @Test
    fun `ignores url without filename`() {
        assertNull(versionFromApkPureUrl("https://d.apkpure.com/b/XAPK/ru.auto.ara?version=latest"))
    }

    @Test
    fun `ignores filename without APKPure suffix`() {
        val url = "https://data.winudf.com/APK/example?filename=application.apk"

        assertNull(versionFromApkPureUrl(url))
    }

    @Test
    fun `reads version from page json`() {
        val body = """<script>window.data={"versionName":"26.41.0","packageName":"ru.auto.ara"}</script>"""

        assertEquals("26.41.0", versionFromPage(body))
    }

    @Test
    fun `reads version from visible page tag`() {
        val body = """<span class="version one-line"> 1.281.0 </span>"""

        assertEquals("1.281.0", versionFromPage(body))
    }

    @Test
    fun `returns null when page has no version`() {
        assertNull(versionFromPage("<html><body>Application not found</body></html>"))
    }

    @Test
    fun `parses all variants from localized download page`() {
        val body = """
            <span class="label">Архитектура</span><span class="value">armeabi-v7a</span>
            <span class="label">Требуемая версия Android</span>
            <span class="value">Android 6.0+ (M, API 23)</span>
            <span class="label">Архитектура</span><span class="value">arm64-v8a</span>
            <span class="label">Требуемая версия Android</span><span class="value"></span>
        """.trimIndent()

        val details = parseVersionDetails(body)

        assertEquals(setOf("armeabi-v7a", "arm64-v8a"), details.architectures)
        assertEquals(23, details.minSdk)
    }

    @Test
    fun `parses 32 bit only metadata`() {
        val body = """
            <div class="label one-line">Архитектура</div>
            <div class="value double-lines">armeabi_v7a</div>
            <div class="label one-line">Требуемая версия Android</div>
            <div class="value double-lines">Android 5.0+ (L, API 21)</div>
            <span class="tag one-line" data-tag="XAPK">XAPK</span>
        """.trimIndent()

        val details = parseVersionDetails(body)

        assertEquals(setOf("armeabi-v7a"), details.architectures)
        assertEquals(21, details.minSdk)
        assertEquals(true, details.isBundle)
    }

    @Test
    fun `ignores non device architectures`() {
        val body = """
            <span class="label">Архитектура</span><span class="value">universal</span>
            <span class="label">Архитектура</span><span class="value">all</span>
        """.trimIndent()

        assertEquals(emptySet<String>(), parseVersionDetails(body).architectures)
    }
}
