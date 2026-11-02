package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DiagnosticsLogTest {

    @Test
    fun shortReportIsEncodedWithoutTrimming() {
        val report = "Короткий отчёт: приложение закрылось."

        val url = DiagnosticsLog.githubIssueUrl(FeedbackCategory.BUG, report)

        assertEquals(report, decodeBody(url))
        assertFalse(decodeBody(url).contains("Отчёт сокращён"))
    }

    @Test
    fun longRussianReportFitsGithubRequestLine() {
        val report = buildString {
            repeat(800) { index ->
                appendLine(
                    "#$index Проверка отчёта: после проверки обновлений " +
                        "приложение закрылось, карточка WhatsApp не открылась."
                )
            }
        }

        val url = DiagnosticsLog.githubIssueUrl(FeedbackCategory.INSTABILITY, report)

        assertTrue("URL длиной ${url.length} не должен превышать лимит GitHub", url.length <= 6_800)
        assertTrue(url.startsWith("https://github.com/NetlApps/UniStore/issues/new?"))
        assertTrue(url.contains("&labels=bug"))
        val body = decodeBody(url)
        assertTrue(body.contains("Отчёт сокращён"))
        // The start and the newest actions survive; the middle is dropped.
        assertTrue(body.startsWith("#0 Проверка отчёта"))
        assertTrue(body.contains("#799 Проверка отчёта"))
    }

    private fun decodeBody(url: String): String {
        val encoded = url.substringAfter("&body=").substringBefore("&labels=")
        return URLDecoder.decode(encoded, Charsets.UTF_8.name())
    }
}
