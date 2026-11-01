package ru.updatehub.app.data.checkers

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.updatehub.app.BuildConfig
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolver that uses the system DNS for the normal case and falls back to
 * DNS-over-HTTPS when the carrier resolver is slow, broken or returns a
 * hijacked address.
 *
 * Some mobile networks answer store hostnames with a captive-portal/block
 * address instead of NXDOMAIN, and others simply time out. DoH is queried
 * against hardcoded IP addresses, so it cannot be broken by a bad resolver
 * configuration and does not need a VPN. Only the DNS question leaves the
 * device; the store request itself still goes to the store.
 */
internal class DohDns(
    private val system: Dns = Dns.SYSTEM,
    private val providers: List<DohProvider> = DEFAULT_PROVIDERS,
    private val dohClient: OkHttpClient = defaultDohClient()
) : Dns {

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val providerCooldown = ConcurrentHashMap<String, Long>()

    // After DoH succeeds once while the system resolver was failing, prefer
    // DoH for a few minutes. This avoids paying the system resolver timeout
    // for every new host on a network with a broken DNS server.
    @Volatile
    private var preferDohUntil = 0L

    // System DNS is blocking and cannot be interrupted reliably. Running it on
    // a daemon pool lets a lookup give up after a short wait and try DoH
    // instead of holding a store request until the carrier resolver decides
    // to answer.
    private val lookupPool = ThreadPoolExecutor(
        4,
        4,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        DaemonThreadFactory
    ).apply { allowCoreThreadTimeOut(true) }

    override fun lookup(hostname: String): List<InetAddress> {
        cached(hostname)?.let { return it }
        if (isIpLiteral(hostname)) return system.lookup(hostname)

        var dohAttempted = false
        if (System.currentTimeMillis() < preferDohUntil) {
            dohAttempted = true
            val answer = dohLookup(hostname)
            val addresses = answer?.addresses
                ?.mapNotNull { value ->
                    runCatching { InetAddress.getByName(value) }.getOrNull()
                }
                .orEmpty()
            if (addresses.isNotEmpty()) {
                return remember(hostname, addresses, answer?.ttlSeconds ?: DEFAULT_TTL_SECONDS)
            }
            // DoH stopped working; go back to the system resolver first.
            preferDohUntil = 0L
        }

        val systemAddresses = systemLookup(hostname)
        if (systemAddresses.isNotEmpty() && systemAddresses.none { it.isSuspicious() }) {
            return remember(hostname, systemAddresses, DEFAULT_TTL_SECONDS)
        }

        val dohAnswer = if (dohAttempted) null else dohLookup(hostname)
        if (dohAnswer != null && dohAnswer.addresses.isNotEmpty()) {
            Log.i(TAG, "$hostname: разрешён через DoH")
            val addresses = dohAnswer.addresses.mapNotNull { value ->
                runCatching { InetAddress.getByName(value) }.getOrNull()
            }
            if (addresses.isNotEmpty()) {
                preferDohUntil = System.currentTimeMillis() + PREFER_DOH_MS
                return remember(hostname, addresses, dohAnswer.ttlSeconds)
            }
        }

        // DoH may be blocked while the carrier resolver still returns a usable
        // (if unusual) address. A private or loopback answer is never useful
        // for a public store, so it is rejected above.
        if (systemAddresses.isNotEmpty()) {
            Log.w(TAG, "$hostname: DoH недоступен, используется системный DNS")
            return remember(hostname, systemAddresses, DEFAULT_TTL_SECONDS)
        }

        throw UnknownHostException(
            "$hostname: не удалось разрешить ни через системный DNS, ни через DoH"
        )
    }

    /** Drops cached answers after a Wi-Fi/cellular switch. */
    fun clear() {
        cache.clear()
        providerCooldown.clear()
        preferDohUntil = 0L
    }

    private fun cached(hostname: String): List<InetAddress>? {
        val entry = cache[hostname] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(hostname, entry)
            return null
        }
        return entry.addresses
    }

    private fun remember(
        hostname: String,
        addresses: List<InetAddress>,
        ttlSeconds: Long
    ): List<InetAddress> {
        if (addresses.isEmpty()) return addresses
        val ttl = ttlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        val sorted = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
        cache[hostname] = CacheEntry(
            addresses = sorted,
            expiresAt = System.currentTimeMillis() + ttl * 1000L
        )
        if (cache.size > MAX_CACHE_ENTRIES) {
            val now = System.currentTimeMillis()
            cache.entries.removeAll { it.value.expiresAt <= now }
        }
        return sorted
    }

    private fun systemLookup(hostname: String): List<InetAddress> {
        return try {
            val task = lookupPool.submit<List<InetAddress>> { system.lookup(hostname) }
            try {
                task.get(SYSTEM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .filter { !it.isSuspicious() }
            } catch (_: TimeoutException) {
                task.cancel(true)
                Log.w(TAG, "$hostname: системный DNS не ответил за ${SYSTEM_TIMEOUT_MS} мс")
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Queries A records first and AAAA only when no IPv4 answer arrived. Most
     * mobile carriers still route IPv4 more reliably, and using only the
     * first working family avoids doubling the lookup time.
     */
    private fun dohLookup(hostname: String): DohAnswer? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DOH_BUDGET_MS)
        for (type in intArrayOf(TYPE_A, TYPE_AAAA)) {
            for (provider in providers) {
                if (System.nanoTime() >= deadline) return null
                if ((providerCooldown[provider.name] ?: 0L) > System.currentTimeMillis()) {
                    continue
                }
                val answer = query(provider, hostname, type)
                if (answer != null && answer.addresses.isNotEmpty()) return answer
            }
        }
        return null
    }

    private fun query(provider: DohProvider, hostname: String, type: Int): DohAnswer? {
        val url = provider.base.toHttpUrl()
            .newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", if (type == TYPE_AAAA) "AAAA" else "A")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .header("User-Agent", DOH_USER_AGENT)
            .build()

        // newBuilder shares the connection pool and dispatcher with the
        // bootstrap client, so repeated queries reuse the TLS connection.
        val client = dohClient.newBuilder()
            .callTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code >= 500 || response.code == 403 || response.code == 429) {
                        providerCooldown[provider.name] =
                            System.currentTimeMillis() + COOLDOWN_MS
                    }
                    Log.w(TAG, "${provider.name}: HTTP ${response.code}")
                    return null
                }
                parseDohAnswers(response.body?.string().orEmpty(), type)
            }
        } catch (e: IOException) {
            providerCooldown[provider.name] = System.currentTimeMillis() + COOLDOWN_MS
            Log.w(TAG, "${provider.name}: ${e.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "${provider.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun InetAddress.isSuspicious(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isMulticastAddress) {
            return true
        }
        if (this !is Inet4Address) return false
        val bytes = address
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 198 && (second == 18 || second == 19)) ||
            // 100.64.0.0/10 is carrier-grade NAT; a public store must not
            // resolve into it through the carrier's private DNS.
            (first == 100 && second in 64..127)
    }

    private fun isIpLiteral(hostname: String): Boolean =
        ipv4Literal.matches(hostname) || hostname.contains(':')

    private object DaemonThreadFactory : ThreadFactory {
        private val count = AtomicInteger(1)

        override fun newThread(task: Runnable): Thread =
            Thread(task, "UniStore-dns-${count.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
            }
    }

    companion object {
        private const val TAG = "UpdateHub"
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private const val SYSTEM_TIMEOUT_MS = 1_500L
        private const val PROVIDER_TIMEOUT_MS = 2_000L
        private const val DOH_BUDGET_MS = 4_500L
        private const val DEFAULT_TTL_SECONDS = 300L
        private const val MIN_TTL_SECONDS = 60L
        private const val MAX_TTL_SECONDS = 1_800L
        private const val MAX_CACHE_ENTRIES = 128
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private const val PREFER_DOH_MS = 5 * 60 * 1000L
        private val DOH_USER_AGENT = "UniStore/${BuildConfig.VERSION_NAME}"

        private val ipv4Literal =
            Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

        /**
         * Hardcoded IP literals avoid bootstraping DoH through the very
         * resolver that may be broken. Both endpoints have IP addresses in
         * their TLS certificate, so normal certificate validation still
         * applies.
         */
        val DEFAULT_PROVIDERS = listOf(
            DohProvider("Cloudflare", "https://1.1.1.1/dns-query"),
            DohProvider("Google", "https://8.8.8.8/resolve")
        )

        private fun defaultDohClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal data class DohProvider(
    val name: String,
    val base: String
)

/**
 * Shared resolver for store checks, direct download resolution and APK
 * downloads. One instance keeps the DoH TLS connection and the DNS cache warm
 * across the whole update flow.
 */
internal object StoreDns {
    val resolver: DohDns by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DohDns() }
}

internal data class DohAnswer(
    val addresses: List<String>,
    val ttlSeconds: Long
)

/**
 * Parses the JSON answer shared by Cloudflare's `/dns-query` and Google's
 * `/resolve` endpoints. Returns null for NXDOMAIN, malformed responses and
 * answers of the wrong record type.
 */
internal fun parseDohAnswers(body: String, type: Int): DohAnswer? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (root.optInt("Status", -1) != 0) return null
    val answers = root.optJSONArray("Answer") ?: return null

    val addresses = mutableListOf<String>()
    var minTtl = Long.MAX_VALUE
    for (index in 0 until answers.length()) {
        val answer = answers.optJSONObject(index) ?: continue
        if (answer.optInt("type", -1) != type) continue
        val value = answer.optString("data").trim()
        val valid = when (type) {
            1 -> value.matches(ipv4Literal)
            28 -> value.contains(':') &&
                value.all { it.isDigit() || it in "abcdefABCDEF:" }
            else -> false
        }
        if (!valid) continue
        addresses += value
        minTtl = minOf(minTtl, answer.optLong("TTL", 300L))
    }
    if (addresses.isEmpty()) return null
    return DohAnswer(
        addresses = addresses.distinct(),
        ttlSeconds = if (minTtl == Long.MAX_VALUE) 300L else minTtl
    )
}

private val ipv4Literal = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
