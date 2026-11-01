package ru.updatehub.app.data.checkers

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResult(
    val code: Int,
    val body: String,
    val finalUrl: String
)

data class HttpProbe(
    val code: Int,
    val latencyMs: Long
)

object Http {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val dns = StoreDns.resolver

    private val client = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        // Keep TLS/HTTP2 connections alive across probes, checks and the
        // single-app install flow. Reusing a warm connection removes a full
        // DNS + TCP + TLS handshake from every store request.
        .connectionPool(ConnectionPool(16, 10, TimeUnit.MINUTES))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // Google Play pages are large; a 15 second total timeout is too tight on
    // mobile networks and used to disable the source after a few failures.
    private val slowClient = client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    // Short-lived client for the connection indicators. The first HEAD is a
    // warm-up, so nine seconds are enough even for an overseas store. A store
    // that cannot answer either request is treated as unreachable.
    private val probeClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Some carriers block play.google.com by TLS SNI while the same Google
    // edge is reachable as www.google.com. Play pages are fetched through
    // that frontend and the Host header selects the Play virtual host.
    // HTTP/1.1 is required here: it sends exactly this Host, whereas HTTP/2
    // would derive :authority from the URL.
    private val frontedClient = slowClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        // The route chain cancels this call at its own nine-second limit.
        // Keeping the OkHttp limit slightly higher lets the chain decide.
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val frontedProbeClient = probeClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = execute(client, buildGetRequest(url, headers))

    /**
     * Called when the active network changes. DNS answers and TLS sockets
     * belong to the previous link; keeping them would make the first check
     * after switching Wi-Fi/LTE wait for dead connections and stale DNS.
     */
    fun resetNetworkState() {
        dns.clear()
        client.connectionPool.evictAll()
    }

    /**
     * HEAD is used to resolve a download URL without transferring the APK.
     * Some CDNs redirect to the final file only after following several hops;
     * [HttpResult.finalUrl] keeps that location for metadata extraction.
     */
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val request = buildGetRequest(url, headers).newBuilder()
            .method("HEAD", null)
            .build()
        return execute(client, request)
    }

    suspend fun getSlow(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = execute(slowClient, buildGetRequest(url, headers))

    /**
     * Fetches [url] through a different Google frontend while the Host header
     * selects the Play virtual host. HTTP/1.1 is required, because HTTP/2
     * would derive `:authority` from the URL and ignore the override.
     */
    suspend fun getFronted(
        url: String,
        host: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = execute(
        frontedClient,
        buildGetRequest(url, headers + ("Host" to host))
    )

    /**
     * Google Play page lookup that works on carriers blocking the
     * play.google.com SNI. The same Google edge is usually still reachable
     * as www.google.com, so the request goes there and the Host header
     * selects the Play virtual host.
     *
     * A 404/410 from the frontend is a real Play answer ("приложение не
     * найдено"), not a frontend failure. Returning it immediately matters on
     * a blocked network: the direct fallback would sit in the TLS handshake
     * until the source budget expires and turn "нет в Play" into a timeout.
     * Only transient frontend failures retry the direct URL.
     */
    suspend fun getPlay(url: String): HttpResult {
        val target = url.toHttpUrlOrNull()
        if (target != null && target.host == "play.google.com") {
            val frontedUrl = target.newBuilder()
                .host("www.google.com")
                .build()
                .toString()
            val fronted = try {
                execute(
                    frontedClient,
                    buildGetRequest(frontedUrl, mapOf("Host" to target.host))
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(
                    "UpdateHub",
                    "Play frontend failed for $url: ${t.javaClass.simpleName}: ${t.message}"
                )
                null
            }
            if (fronted != null) {
                if (fronted.code in 200..299 || fronted.code == 404 || fronted.code == 410) {
                    return fronted
                }
                Log.w(
                    "UpdateHub",
                    "Play frontend HTTP ${fronted.code} for $url, falling back to direct"
                )
            }
        }
        return getSlow(url)
    }

    /**
     * Measures how fast a store answers a HEAD request. The first request is
     * a warm-up: it pays for DNS, TCP and TLS and is not representative of
     * the store speed. The second request reuses the pooled connection and
     * gives the real response time.
     *
     * Google Play is tried through the frontend first and directly second,
     * so a carrier that filters only play.google.com does not make the
     * indicator red while checks still work through the other route.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpProbe? {
        val target = url.toHttpUrlOrNull()
        val playHost = target?.host == "play.google.com"
        val variants = if (playHost) listOf(true, false) else listOf(false)
        for (fronted in variants) {
            val warmup = probeOnce(url, headers, fronted) ?: continue
            if (!warmup.isHealthy) continue
            return probeOnce(url, headers, fronted)?.takeIf { it.isHealthy } ?: warmup
        }
        return null
    }

    private val HttpProbe.isHealthy: Boolean
        get() = code in 200..399

    private suspend fun probeOnce(
        url: String,
        headers: Map<String, String>,
        fronted: Boolean
    ): HttpProbe? =
        suspendCancellableCoroutine<HttpProbe?> { continuation ->
            val target = url.toHttpUrlOrNull()
            val requestUrl = if (fronted && target != null) {
                target.newBuilder().host("www.google.com").build().toString()
            } else {
                url
            }
            val builder = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            if (fronted && target != null) builder.header("Host", target.host)
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.head().build()
            val call = if (fronted) {
                frontedProbeClient.newCall(request)
            } else {
                probeClient.newCall(request)
            }
            val startedAt = System.nanoTime()
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val latency = (System.nanoTime() - startedAt) / 1_000_000
                        val probe = if (response.code >= 500) {
                            null
                        } else {
                            HttpProbe(code = response.code, latencyMs = latency)
                        }
                        if (continuation.isActive) continuation.resume(probe)
                    }
                }
            })
        }

    private fun buildGetRequest(url: String, headers: Map<String, String>): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept-Language",
                "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
            )
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()
    }

    /**
     * Form POST used by Google Play's batchexecute data endpoint. The same
     * reason as [getFronted] applies: on LTE only www.google.com is reachable,
     * so the request goes there while the Host header selects Play.
     */
    suspend fun postFormFronted(
        url: String,
        host: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = execute(
        frontedClient,
        buildFormPostRequest(url, body, headers + ("Host" to host))
    )

    suspend fun postForm(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = execute(client, buildFormPostRequest(url, body, headers))

    private fun buildFormPostRequest(
        url: String,
        body: String,
        headers: Map<String, String>
    ): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        .header("Accept", "*/*")
        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        .apply {
            headers.forEach { (name, value) -> header(name, value) }
        }
        .build()

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        return execute(client, request)
    }

    /**
     * Enqueues the call instead of blocking a thread. The coroutine timeout
     * used by the update scheduler then cancels the socket as well, so a slow
     * store cannot hold a network slot until its own 45 second timeout.
     */
    private suspend fun execute(client: OkHttpClient, request: Request): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { result ->
                            val value = HttpResult(
                                code = result.code,
                                body = result.body?.string().orEmpty(),
                                finalUrl = result.request.url.toString()
                            )
                            if (continuation.isActive) continuation.resume(value)
                        }
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            })
        }
}
