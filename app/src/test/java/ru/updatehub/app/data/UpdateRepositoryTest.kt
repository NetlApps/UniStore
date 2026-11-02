package ru.updatehub.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.updatehub.app.data.checkers.CheckOutcome
import ru.updatehub.app.data.checkers.StoreChecker
import ru.updatehub.app.data.checkers.VersionDetails
import ru.updatehub.app.data.checkers.VersionDetailsProvider

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

    private class UnverifiedApkPureChecker : StoreChecker, VersionDetailsProvider {
        override val source: UpdateSource = UpdateSource.APKPURE

        override suspend fun check(packageName: String): CheckOutcome =
            CheckOutcome.Found(versionName = "2.0.0")

        override suspend fun details(packageName: String): VersionDetails? = null
    }

    private class FixedChecker(
        override val source: UpdateSource,
        private val outcome: CheckOutcome
    ) : StoreChecker {
        override suspend fun check(packageName: String): CheckOutcome = outcome
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

    private fun repository(
        checker: StoreChecker,
        supportedAbis: List<String> = listOf("arm64-v8a")
    ) = UpdateRepository(
        checkers = listOf(checker),
        deviceSdk = 35,
        supportedAbis = supportedAbis
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

    @Test
    fun apkPureVersionWithoutAbiMetadataIsWithheldOn64BitOnlyPhone() = runBlocking {
        val checker = UnverifiedApkPureChecker()
        val app = installed("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")

        val result = repository(checker).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.APKPURE),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(UpdateStatus.INCOMPATIBLE, result.status)
        assertTrue(result.message.orEmpty().contains("Не удалось проверить совместимость"))
    }

    @Test
    fun apkPureVersionWithoutAbiMetadataIsOfferedOn32BitCapablePhone() = runBlocking {
        val checker = UnverifiedApkPureChecker()
        val app = installed("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")

        val result = repository(
            checker,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        ).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.APKPURE),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status)
        assertNotNull(result.best)
    }

    @Test
    fun hiddenPlayVersionIsUnconfirmedInsteadOfUpToDate() = runBlocking {
        val app = installed("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
        val checker = FixedChecker(UpdateSource.GOOGLE_PLAY, CheckOutcome.HiddenVersion)

        val result = repository(checker).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.GOOGLE_PLAY),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(UpdateStatus.UNCONFIRMED, result.status)
        assertEquals(SourceCheckStatus.HIDDEN, result.sourceChecks.single().status)
        assertTrue(result.message.orEmpty().contains("не публикует номер версии"))
    }

    @Test
    fun hiddenPlayVersionDoesNotHideARealApkPureUpdate() = runBlocking {
        val app = installed("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
        val repository = UpdateRepository(
            checkers = listOf(
                FixedChecker(UpdateSource.GOOGLE_PLAY, CheckOutcome.HiddenVersion),
                FixedChecker(UpdateSource.APKPURE, CheckOutcome.Found("2.0.0"))
            ),
            deviceSdk = 35,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        val result = repository.checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.GOOGLE_PLAY, UpdateSource.APKPURE),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, result.status)
        assertEquals(UpdateSource.APKPURE, result.best?.source)
        assertEquals(
            SourceCheckStatus.UPDATE_FOUND,
            result.sourceChecks.first { it.source == UpdateSource.APKPURE }.status
        )
        assertEquals(
            SourceCheckStatus.HIDDEN,
            result.sourceChecks.first { it.source == UpdateSource.GOOGLE_PLAY }.status
        )
    }

    @Test
    fun signatureMismatchIsReportedAsSuspiciousSource() = runBlocking {
        val app = installed("aaaa000000000000000000000000000000000000000000000000000000000000")
        val checker = FixedChecker(
            UpdateSource.RUSTORE,
            CheckOutcome.Found(
                versionName = "9.0.0",
                signature = "bbbb000000000000000000000000000000000000000000000000000000000000"
            )
        )

        val result = repository(checker).checkAll(
            packages = setOf(app.packageName),
            sources = setOf(UpdateSource.RUSTORE),
            installed = mapOf(app.packageName to app)
        ).getValue(app.packageName)

        assertEquals(UpdateStatus.UP_TO_DATE, result.status)
        val check = result.sourceChecks.single()
        assertEquals(SourceCheckStatus.SUSPICIOUS, check.status)
        assertEquals(false, check.signatureVerified)
        assertTrue(check.message.orEmpty().contains("Подпись"))
    }
}
