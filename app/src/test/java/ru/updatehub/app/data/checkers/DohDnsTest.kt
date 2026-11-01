package ru.updatehub.app.data.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DohDnsTest {

    @Test
    fun parsesCloudflareIpv4Answer() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                {"name": "play.google.com.", "type": 1, "TTL": 227,
                 "data": "142.251.157.7"},
                {"name": "play.google.com.", "type": 1, "TTL": 120,
                 "data": "142.251.157.8"}
              ]
            }
        """.trimIndent()

        val answer = parseDohAnswers(body, 1)

        assertEquals(listOf("142.251.157.7", "142.251.157.8"), answer?.addresses)
        assertEquals(120L, answer?.ttlSeconds)
    }

    @Test
    fun parsesGoogleIpv6Answer() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                {"name": "play.google.com.", "type": 28, "TTL": 300,
                 "data": "2a00:1450:4010:c0f::64"}
              ]
            }
        """.trimIndent()

        val answer = parseDohAnswers(body, 28)

        assertEquals(listOf("2a00:1450:4010:c0f::64"), answer?.addresses)
    }

    @Test
    fun ignoresAnswersOfAnotherType() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                {"name": "example.com.", "type": 5, "TTL": 300,
                 "data": "example.net."}
              ]
            }
        """.trimIndent()

        assertNull(parseDohAnswers(body, 1))
    }

    @Test
    fun rejectsNxDomain() {
        val body = """{"Status": 3, "Answer": []}"""

        assertNull(parseDohAnswers(body, 1))
    }

    @Test
    fun rejectsMalformedBody() {
        assertNull(parseDohAnswers("<html>blocked</html>", 1))
    }

    @Test
    fun rejectsNonIpData() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                {"name": "example.com.", "type": 1, "TTL": 60,
                 "data": "not-an-ip"}
              ]
            }
        """.trimIndent()

        assertNull(parseDohAnswers(body, 1))
    }
}
