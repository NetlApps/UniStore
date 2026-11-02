package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultSerializerTest {

    @Test
    fun sourceChecksSurviveSerialization() {
        val original = AppUpdateResult(
            packageName = "com.example.app",
            installedVersion = "1.0.0",
            status = UpdateStatus.UNCONFIRMED,
            checkedAt = 123L,
            message = "Google Play не публикует номер версии",
            sourceChecks = listOf(
                SourceCheck(
                    source = UpdateSource.GOOGLE_PLAY,
                    status = SourceCheckStatus.HIDDEN,
                    message = "Магазин не публикует номер версии"
                ),
                SourceCheck(
                    source = UpdateSource.APKPURE,
                    status = SourceCheckStatus.UP_TO_DATE,
                    versionName = "1.0.0",
                    signatureVerified = true
                ),
                SourceCheck(
                    source = UpdateSource.RUSTORE,
                    status = SourceCheckStatus.SUSPICIOUS,
                    versionName = "9.9.9",
                    message = "Подпись не совпадает",
                    signatureVerified = false
                )
            )
        )

        val restored = ResultSerializer
            .deserialize(ResultSerializer.serialize(listOf(original)))
            .getValue(original.packageName)

        assertEquals(UpdateStatus.UNCONFIRMED, restored.status)
        assertEquals(3, restored.sourceChecks.size)
        assertEquals(SourceCheckStatus.HIDDEN, restored.sourceChecks[0].status)
        assertEquals("1.0.0", restored.sourceChecks[1].versionName)
        assertEquals(true, restored.sourceChecks[1].signatureVerified)
        assertEquals(false, restored.sourceChecks[2].signatureVerified)
        assertNull(restored.versions.firstOrNull())
    }

    @Test
    fun oldCacheWithoutSourceChecksStillLoads() {
        val json = """
            [{"packageName":"com.example.app","installedVersion":"1.0.0",
              "status":"UP_TO_DATE","checkedAt":1,"versions":[]}]
        """.trimIndent()

        val restored = ResultSerializer.deserialize(json).getValue("com.example.app")

        assertEquals(UpdateStatus.UP_TO_DATE, restored.status)
        assertEquals(emptyList<SourceCheck>(), restored.sourceChecks)
    }
}
