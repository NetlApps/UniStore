package ru.updatehub.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.updatehub.app.data.checkers.CheckOutcome
import ru.updatehub.app.data.checkers.StoreChecker

class UpdateRepositoryTest {

    private class CountingChecker(
        private val versionName: String
    ) : StoreChecker {
        override val source: UpdateSource = UpdateSource.GOOGLE_PLAY
        var calls = 0

        override suspend fun check(packageName: String): CheckOutcome {
            calls++
            return CheckOutcome.Found(versionName = versionName)
        }
    }

    private fun installed(signature: String?) = InstalledApp(
        packageName = "com.example.app",
        label = "Example",
        versionName = "1.0.0",
        versionCode = 10_000L,
        isSystem = false,
        installedAt = 0L,
        signature = signature
    )

    private fun repository(checker: StoreChecker) = UpdateRepository(
        checkers = listOf(checker),
        deviceSdk = 35,
        supportedAbis = listOf("arm64-v8a")
    )

    @Test
    fun testKeySignedAppSkipsStoresAndExplainsWhy() = runBlocking {
        val checker = CountingChecker("9.0.0")
        val app = installed(SignatureUtils.AOSP_TEST_KEY_SHA256)

        val result = repository(checker).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.GOOGLE_PLAY),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        // A store can never update this copy, so no request is made at all.
        assertEquals(0, checker.calls)
        assertEquals(UpdateStatus.UP_TO_DATE, result.status)
        assertTrue(result.message.orEmpty().contains("Тестовая подпись"))
    }

    @Test
    fun appWithOfficialSignatureIsStillChecked() = runBlocking {
        val checker = CountingChecker("9.0.0")
        val app = installed("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")

        val result = repository(checker).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.GOOGLE_PLAY),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(1, checker.calls)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status)
        assertNotNull(result.best)
    }
}
