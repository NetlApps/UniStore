package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreHealthTest {

    @Test
    fun fastResponseIsOnline() {
        val health = storeHealthForLatency(240)
        assertEquals(StoreConnection.ONLINE, health.connection)
        assertEquals(240L, health.latencyMs)
    }

    @Test
    fun responseJustBelowThresholdIsOnline() {
        assertEquals(
            StoreConnection.ONLINE,
            storeHealthForLatency(STORE_ONLINE_MAX_MS).connection
        )
    }

    @Test
    fun slowResponseIsWeak() {
        val health = storeHealthForLatency(STORE_ONLINE_MAX_MS + 1)
        assertEquals(StoreConnection.SLOW, health.connection)
    }

    @Test
    fun failedProbeHasNoConnection() {
        val health = storeHealthForLatency(null)
        assertEquals(StoreConnection.OFFLINE, health.connection)
        assertEquals(null, health.latencyMs)
    }

    @Test
    fun labelsDescribeEachState() {
        assertEquals("Проверка связи…", storeHealthLabel(StoreHealth(StoreConnection.CHECKING)))
        assertEquals(
            "Хорошая связь · 500 мс",
            storeHealthLabel(StoreHealth(StoreConnection.ONLINE, 500))
        )
        assertEquals(
            "Слабая связь · 3.4 с",
            storeHealthLabel(StoreHealth(StoreConnection.SLOW, 3_400))
        )
        assertEquals("Нет связи", storeHealthLabel(StoreHealth(StoreConnection.OFFLINE)))
    }

    @Test
    fun everyStoreHasReachableProbeAddress() {
        val urls = UpdateSource.entries.map { it.healthUrl }
        assertTrue(urls.all { it.startsWith("https://") })
        assertEquals(urls.size, urls.distinct().size)
        // The APKPure root answers HEAD with a Cloudflare 403, which made a
        // healthy catalogue look offline. The category page is a real 200.
        assertEquals("https://apkpure.com/app", UpdateSource.APKPURE.healthUrl)
    }

    @Test
    fun slowSourcesHaveBoundedLookupTime() {
        assertEquals(27_000L, sourceTimeoutMillis(UpdateSource.GOOGLE_PLAY))
        assertEquals(22_000L, sourceTimeoutMillis(UpdateSource.APKPURE))
        assertEquals(12_000L, sourceTimeoutMillis(UpdateSource.RUSTORE))
        assertEquals(12_000L, sourceTimeoutMillis(UpdateSource.GETAPPS))
        assertTrue(
            UpdateSource.entries.all { sourceTimeoutMillis(it) in 1_000L..30_000L }
        )
    }

    @Test
    fun overseasStoresAllowLongerWarmResponse() {
        assertEquals(3_000L, UpdateSource.GOOGLE_PLAY.healthOnlineMaxMs)
        assertEquals(2_000L, UpdateSource.APKPURE.healthOnlineMaxMs)
        assertEquals(1_200L, UpdateSource.RUSTORE.healthOnlineMaxMs)

        // A 2.5 s answer from Google Play through a VPN is still good, while
        // the same latency from a local store means a weak link.
        assertEquals(
            StoreConnection.ONLINE,
            storeHealthForLatency(2_500, UpdateSource.GOOGLE_PLAY.healthOnlineMaxMs).connection
        )
        assertEquals(
            StoreConnection.SLOW,
            storeHealthForLatency(2_500, UpdateSource.RUSTORE.healthOnlineMaxMs).connection
        )
        assertEquals(
            StoreConnection.SLOW,
            storeHealthForLatency(3_100, UpdateSource.GOOGLE_PLAY.healthOnlineMaxMs).connection
        )
    }
}
