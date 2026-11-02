package ru.updatehub.app.data.checkers

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Play does not have a public version API. The web app loads details
 * from `/_/PlayStoreUi/data/batchexecute`; that answer carries the same ds:5
 * block as the page but is about 15 KB instead of 1.2 MB. Version is at
 * ds:5 -> [1][2][140][0][0][0] (with a fallback path used by the page).
 *
 * If the compact endpoint is unavailable, the full page is requested through
 * www.google.com first, then directly, then through Google's translation
 * proxy and finally through a regional frontend. The last successful route is
 * tried first on the next app, which avoids paying for the same blocked host
 * for every item in a bulk check.
 *
 * For some apps (YouTube, Spotify, ...) Google deliberately hides the version
 * ("Varies with device"). In that case the checker reports a friendly error
 * and other enabled sources can still supply a version.
 */
class GooglePlayChecker : StoreChecker, StorePrefetcher {
    override val source: UpdateSource = UpdateSource.GOOGLE_PLAY

    // The compact data endpoint is small (~15 KB); four concurrent requests
    // are safe even on LTE and cut a bulk check to under half a minute. The
    // full-page fallback is rare and still protected by the per-route
    // timeouts, so it does not need a smaller gate anymore.
    @Volatile
    private var gate = Semaphore(4)

    @Volatile
    private var metered = false

    /** A release resolved by [prefetch], reused by the per-app check. */
    private data class PrefetchedOutcome(
        val outcome: CheckOutcome,
        val at: Long
    )

    private val prefetched = ConcurrentHashMap<String, PrefetchedOutcome>()

    /**
     * Batches that are still running, keyed by package. A per-app check waits
     * on its entry instead of sending a duplicate Play request for the same
     * package. Completed entries are removed by [prefetch].
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<CheckOutcome?>>()

    // Play decides per app whether it exposes a version. Apps such as
    // WhatsApp or Telegram answer with the full card but no version at all.
    // Remembering that for the session stops repeated 15 KB requests for a
    // value Play is not going to return.
    private val hiddenUntil = ConcurrentHashMap<String, Long>()

    fun setMeteredNetwork(enabled: Boolean) {
        metered = enabled
        gate = Semaphore(4)
        // A prefetched card belongs to the old link. Hidden-version knowledge
        // is a property of the app, so it survives a network switch.
        prefetched.clear()
        // Wake checks that were waiting for the old link; they will use the
        // normal per-app route on the new one.
        inFlight.values.forEach { it.complete(null) }
        inFlight.clear()
    }

    override suspend fun check(packageName: String): CheckOutcome {
        prefetchedOutcome(packageName)?.let { return it }
        if (isKnownHidden(packageName)) return hiddenVersionOutcome()

        val outcome = gate.withPermit {
            var result = checkRoutes(packageName, CHECK_BUDGET_MS)
            if (result is RouteResult.Failed && result.retryable) {
                // Retry only rate limits and server errors. A timeout already
                // consumed the per-source budget, so a second full chain would
                // just keep the app card waiting.
                delay(450)
                result = checkRoutes(packageName, RETRY_BUDGET_MS)
            }
            when (result) {
                is RouteResult.Found -> result.value
                RouteResult.NotFound -> CheckOutcome.NotFound
                is RouteResult.Failed -> {
                    if (result.message.contains(HIDDEN_VERSION)) {
                        hiddenUntil[packageName] =
                            System.currentTimeMillis() + HIDDEN_TTL_MS
                        CheckOutcome.HiddenVersion
                    } else {
                        Log.w("UpdateHub", "Google Play $packageName: ${result.message}")
                        CheckOutcome.Failed(
                            result.message,
                            transient = true,
                            retryable = result.retryable
                        )
                    }
                }
            }
        }
        return outcome
    }

    /**
     * Resolves packages through the same batchexecute endpoint, one chunk at
     * a time. Play answers with one `wrb.fr` block per package; the blocks
     * arrive in completion order, so each one is mapped back by the package id
     * embedded in `ds:5` instead of by position.
     *
     * Chunks are sent sequentially on purpose. If the first one fails, the
     * remaining seven would repeat the same blocked route for every chunk and
     * hold the network slots of apps that could finish normally. Per-app
     * checks take over as soon as a chunk cannot be delivered.
     */
    override suspend fun prefetch(
        packages: Collection<String>,
        networkSlot: NetworkSlot
    ) {
        val now = System.currentTimeMillis()
        val targets = packages.asSequence()
            .filter { packageName ->
                val cached = prefetched[packageName]
                (cached == null || now - cached.at >= PREFETCH_TTL_MS) &&
                    !isKnownHidden(packageName)
            }
            .distinct()
            .toList()
        if (targets.size < PREFETCH_MIN_PACKAGES) return

        // Register every target before the first request. A per-app check that
        // starts while the batch is running then waits for its entry instead
        // of sending a duplicate Play request. The finally block always wakes
        // the waiters, including on timeout and cancellation.
        val pending = LinkedHashMap<String, CompletableDeferred<CheckOutcome?>>()
        targets.forEach { packageName ->
            val fresh = CompletableDeferred<CheckOutcome?>()
            val existing = inFlight.putIfAbsent(packageName, fresh)
            pending[packageName] = existing ?: fresh
        }

        try {
            withTimeoutOrNull(PREFETCH_TOTAL_BUDGET_MS) {
                for (chunk in targets.chunked(PREFETCH_BATCH_SIZE)) {
                    val delivered = networkSlot.withSlot { prefetchChunk(chunk) }
                    if (delivered == null) break

                    val answeredAt = System.currentTimeMillis()
                    delivered.forEach { (packageName, entry) ->
                        val outcome = when (entry) {
                            is PlayBatchEntry.Found -> {
                                val found = CheckOutcome.Found(
                                    entry.versionName,
                                    title = entry.title
                                )
                                prefetched[packageName] =
                                    PrefetchedOutcome(found, answeredAt)
                                found
                            }

                            PlayBatchEntry.Hidden -> {
                                hiddenUntil[packageName] = answeredAt + HIDDEN_TTL_MS
                                hiddenVersionOutcome()
                            }
                        }
                        pending[packageName]?.complete(outcome)
                    }
                    // Play leaves a package out of the batch when it has no card
                    // for it. Those waiters must not block until the next chunk.
                    chunk.forEach { packageName ->
                        pending[packageName]?.complete(null)
                    }
                }
            }
        } finally {
            pending.forEach { (packageName, deferred) ->
                inFlight.remove(packageName, deferred)
                deferred.complete(null)
            }
        }
    }

    /**
     * Waits for the batch that contains [packageName]. The wait happens
     * before the caller takes a network slot, so a full queue of per-app
     * checks cannot starve the prefetch request they are waiting for.
     */
    override suspend fun awaitPrefetched(
        packageName: String,
        timeoutMs: Long
    ): CheckOutcome? {
        prefetchedOutcome(packageName)?.let { return it }
        if (isKnownHidden(packageName)) return hiddenVersionOutcome()
        val deferred = inFlight[packageName] ?: return null
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private suspend fun prefetchChunk(
        packages: List<String>
    ): Map<String, PlayBatchEntry>? {
        val body = playBatchBody(packages)
        val headers = mapOf(
            "X-Same-Domain" to "1",
            "Origin" to "https://play.google.com",
            "Referer" to
                "https://play.google.com/store/apps/details?id=${packages.first()}"
        )
        val routes = listOf(
            FetchRoute(
                name = "Play data через google.com",
                url = "https://www.google.com/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 9_000L,
                method = RouteMethod.POST_FORM,
                body = body,
                frontedHost = "play.google.com"
            ),
            FetchRoute(
                name = "Play data через google.co.uk",
                url = "https://www.google.co.uk/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 6_000L,
                method = RouteMethod.POST_FORM,
                body = body,
                frontedHost = "play.google.com"
            ),
            FetchRoute(
                name = "Play data напрямую",
                url = "https://play.google.com/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 5_000L,
                method = RouteMethod.POST_FORM,
                body = body
            )
        )

        val result = tryRoutes(routes, PREFETCH_BUDGET_MS) { _, response ->
            when {
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> {
                    val batch = parsePlayBatch(response.body)
                    if (batch.isEmpty()) {
                        RouteReply.Failed("ответ без данных Play")
                    } else {
                        RouteReply.Found(batch)
                    }
                }
            }
        }
        val delivered = (result as? RouteResult.Found)?.value ?: return null
        Log.d(
            "UpdateHub",
            "Play prefetch: ${packages.size} приложений, ответов ${delivered.size}"
        )
        return delivered
    }

    private fun prefetchedOutcome(packageName: String): CheckOutcome? {
        val entry = prefetched[packageName] ?: return null
        if (System.currentTimeMillis() - entry.at >= PREFETCH_TTL_MS) {
            prefetched.remove(packageName, entry)
            return null
        }
        return entry.outcome
    }

    private fun isKnownHidden(packageName: String): Boolean {
        val until = hiddenUntil[packageName] ?: return false
        if (until <= System.currentTimeMillis()) {
            hiddenUntil.remove(packageName, until)
            return false
        }
        return true
    }

    private fun hiddenVersionOutcome() =
        CheckOutcome.HiddenVersion

    private suspend fun checkRoutes(
        packageName: String,
        budgetMs: Long
    ): RouteResult<CheckOutcome.Found> {
        val startedAt = SystemClock.elapsedRealtime()
        val compact = checkCompact(packageName)
        when {
            compact is RouteResult.Found -> return compact
            compact is RouteResult.NotFound -> return compact
            compact is RouteResult.Failed && compact.message.contains(HIDDEN_VERSION) ->
                return compact
        }

        // The compact endpoint failed for a network reason. On LTE the full
        // page is 1.2 MB and almost never finishes, so downloading it only
        // blocks the link that the next apps need. Retry the compact request
        // instead; the checker's retry path gets a fresh short budget.
        if (metered) return compact
        val remaining = (budgetMs - (SystemClock.elapsedRealtime() - startedAt))
            .coerceAtLeast(MIN_PAGE_BUDGET_MS)
        return checkPageRoutes(packageName, remaining)
    }

    /**
     * Google Play's web frontend fetches the app details from
     * `/_/PlayStoreUi/data/batchexecute` (RPC Ws7gDc). That answer is about
     * 15 KB, while the HTML page around it is roughly 1.2 MB (200 KB gzipped).
     * On a throttled LTE link the page never finishes, but this request does
     * in about 1.5 seconds. The response also contains the same ds:5 data as
     * the page, so version and title parsing are shared.
     *
     * The request is sent to www.google.com with `Host: play.google.com`,
     * because the direct play.google.com TLS handshake is blocked on some
     * carriers while the www frontend stays reachable.
     */
    private suspend fun checkCompact(
        packageName: String
    ): RouteResult<CheckOutcome.Found> {
        val requestJson = PLAY_DATA_REQUEST_TEMPLATE
            .replace(PACKAGE_TOKEN, packageName)
        val rpc = "[[[\"Ws7gDc\",${JSONObject.quote(requestJson)},null,\"generic\"]]]"
        val body = "f.req=" + URLEncoder.encode(rpc, "UTF-8")
        val headers = mapOf(
            "X-Same-Domain" to "1",
            "Origin" to "https://play.google.com",
            "Referer" to
                "https://play.google.com/store/apps/details?id=$packageName"
        )
        val routes = listOf(
            FetchRoute(
                name = "Play data через google.com",
                url = "https://www.google.com/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 6_000L,
                method = RouteMethod.POST_FORM,
                body = body,
                frontedHost = "play.google.com"
            ),
            FetchRoute(
                name = "Play data через google.co.uk",
                url = "https://www.google.co.uk/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 4_000L,
                method = RouteMethod.POST_FORM,
                body = body,
                frontedHost = "play.google.com"
            ),
            FetchRoute(
                name = "Play data напрямую",
                url = "https://play.google.com/_/PlayStoreUi/data/batchexecute",
                headers = headers,
                timeoutMs = 3_000L,
                method = RouteMethod.POST_FORM,
                body = body
            )
        )

        return tryRoutes(routes, COMPACT_BUDGET_MS) { _, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> {
                    val compact = parseCompactDs5(response.body)
                    when {
                        compact.missing -> RouteReply.Missing
                        compact.root == null -> {
                            Log.w(
                                "UpdateHub",
                                "Play data $packageName: " +
                                    response.body.take(140).replace('\n', ' ')
                            )
                            RouteReply.Failed("ответ без данных Play")
                        }
                        else -> {
                            val version = extractVersion(compact.root)
                            if (version.isNullOrBlank()) {
                                RouteReply.Failed(HIDDEN_VERSION, terminal = true)
                            } else {
                                RouteReply.Found(
                                    CheckOutcome.Found(
                                        versionName = version,
                                        title = extractTitle(compact.root)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun checkPageRoutes(
        packageName: String,
        budgetMs: Long
    ): RouteResult<CheckOutcome.Found> {
        val path = "/store/apps/details?id=$packageName&hl=ru&gl=RU"
        val routes = listOf(
            // The page is about 200 KB compressed. Five seconds was enough
            // for a fast Wi-Fi link but cut a healthy LTE page off in the
            // middle, so the first route now gets a realistic budget.
            FetchRoute(
                name = "Play через google.com",
                url = "https://www.google.com$path",
                frontedHost = "play.google.com",
                timeoutMs = 9_000L
            ),
            // Put the direct host second: when a carrier filters the
            // frontend, the next useful route should not wait for two more
            // aliases of the same blocked Google edge.
            FetchRoute(
                name = "Play напрямую",
                url = "https://play.google.com$path",
                timeoutMs = 8_500L,
                slow = true
            ),
            // Google's translation proxy fetches the page from its own
            // infrastructure. It is slower and occasionally returns 502, so
            // it runs after the direct routes and only gets the remaining
            // budget. When it works, it bypasses SNI blocks on both Play
            // hosts without a VPN.
            FetchRoute(
                name = "Play translate",
                url = translateUrl("https://play.google.com$path"),
                timeoutMs = 7_000L,
                slow = true
            ),
            FetchRoute(
                name = "Play через google.co.uk",
                url = "https://www.google.co.uk$path",
                frontedHost = "play.google.com",
                timeoutMs = 5_500L
            )
        )

        return tryRoutes(routes, budgetMs) { route, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    // A 502 from the translation proxy says nothing about
                    // Play itself; retrying the whole chain would only add
                    // another eight seconds on a blocked link.
                    retryable = response.code.isRetryableHttp() &&
                        route.name != "Play translate"
                )

                else -> {
                    val root = extractDs5(response.body)
                    if (root == null) {
                        // A block/captcha page can also be served with HTTP
                        // 200. Only a page with Play's ds:5 block is a real
                        // answer; otherwise let the next route try.
                        RouteReply.Failed("страница без данных Play")
                    } else {
                        val version = extractVersion(root)
                        if (version.isNullOrBlank()) {
                            // Hiding the version is a property of the app, not
                            // of this route: every frontend returns the same
                            // page. Stop instead of downloading four copies.
                            RouteReply.Failed("скрывает версию", terminal = true)
                        } else {
                            RouteReply.Found(
                                CheckOutcome.Found(
                                    versionName = version,
                                    title = extractTitle(root)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun extractDs5(html: String): JSONArray? {
        val marker = "AF_initDataCallback({key: 'ds:5'"
        val markerIndex = html.indexOf(marker)
        if (markerIndex >= 0) {
            val dataIndex = html.indexOf("data:", markerIndex)
            val endIndex = html.indexOf(", sideChannel:", dataIndex)
            if (dataIndex >= 0 && endIndex > dataIndex) {
                runCatching {
                    JSONArray(html.substring(dataIndex + "data:".length, endIndex))
                }.getOrNull()?.let { return it }
            }
        }

        val blockRegex = Regex(
            """AF_initDataCallback\(\{key:\s*'ds:5'.*?data:(\[.*?\]), sideChannel:\s*\{\}\);</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = blockRegex.find(html) ?: return null
        return runCatching { JSONArray(match.groupValues[1]) }.getOrNull()
    }

    private data class CompactData(
        val root: JSONArray?,
        /** Play answered “not in this catalogue” (data is null). */
        val missing: Boolean
    )

    private fun parseCompactDs5(body: String): CompactData {
        val start = body.indexOf('[')
        if (start < 0) return CompactData(null, false)
        val outer = runCatching { JSONArray(body.substring(start)) }.getOrNull()
            ?: return CompactData(null, false)
        for (index in 0 until outer.length()) {
            val entry = outer.optJSONArray(index) ?: continue
            if (entry.optString(0) != "wrb.fr") continue
            if (entry.optString(1) != PLAY_DATA_RPC_ID) continue
            if (entry.isNull(2)) return CompactData(null, true)
            val data = entry.optString(2)
            // A null payload with a present RPC result means the package is
            // not available in this country's catalogue (Sber, VK, ...).
            // It is a final answer, not a reason to download the full page.
            if (data.isBlank() || data == "null") {
                return CompactData(null, true)
            }
            return CompactData(
                runCatching { JSONArray(data) }.getOrNull(),
                false
            )
        }
        return CompactData(null, false)
    }

    private fun extractVersion(root: JSONArray): String? =
        playVersion(root.optJSONArray(1)?.optJSONArray(2))

    private fun extractTitle(root: JSONArray): String? =
        playTitle(root.optJSONArray(1)?.optJSONArray(2))

    private companion object {
        const val CHECK_BUDGET_MS = 25_000L
        const val RETRY_BUDGET_MS = 8_000L

        /**
         * The compact endpoint answers in 1–2 seconds even on LTE. The
         * budget covers both routes; the HTML fallback gets whatever is left.
         */
        const val COMPACT_BUDGET_MS = 14_000L
        const val MIN_PAGE_BUDGET_MS = 8_000L

        const val HIDDEN_VERSION = "скрывает версию"

        /** One Play request resolves this many packages during a check. */
        const val PREFETCH_BATCH_SIZE = 10
        const val PREFETCH_MIN_PACKAGES = 3
        const val PREFETCH_TTL_MS = 5 * 60 * 1000L
        const val PREFETCH_BUDGET_MS = 15_000L
        const val PREFETCH_TOTAL_BUDGET_MS = 20_000L
        const val HIDDEN_TTL_MS = 6 * 60 * 60 * 1000L
    }
}

/** One package from a batched Play answer. */
internal sealed interface PlayBatchEntry {
    data class Found(val versionName: String, val title: String?) : PlayBatchEntry

    /** Play returned the card but deliberately omitted the version. */
    data object Hidden : PlayBatchEntry
}

/**
 * Builds the URL-encoded `f.req` body for one Play batch. The outer array is
 * the RPC group; each item is a `Ws7gDc` call with the same field mask, only
 * the package id changes. This is the same shape the Play web app sends.
 */
internal fun playBatchBody(packages: List<String>): String {
    require(packages.isNotEmpty()) { "Play batch cannot be empty" }
    val rpcEntries = packages.joinToString(",") { packageName ->
        val requestJson = PLAY_DATA_REQUEST_TEMPLATE
            .replace(PACKAGE_TOKEN, packageName)
        """["Ws7gDc",${JSONObject.quote(requestJson)},null,"generic"]"""
    }
    return "f.req=" + URLEncoder.encode("[[$rpcEntries]]", "UTF-8")
}

/**
 * Parses a multi-RPC `batchexecute` answer. Play does not preserve request
 * order in the response, so the package is read from the `ds:5` metadata
 * (`[1][3]["12"][0][0]`) instead of relying on the position. Entries without
 * a readable package id, and `null` payloads for missing apps, are skipped:
 * the caller leaves those packages to the regular per-app check.
 */
internal fun parsePlayBatch(body: String): Map<String, PlayBatchEntry> {
    val start = body.indexOf('[')
    if (start < 0) return emptyMap()
    val outer = runCatching { JSONArray(body.substring(start)) }.getOrNull()
        ?: return emptyMap()

    val result = LinkedHashMap<String, PlayBatchEntry>()
    for (index in 0 until outer.length()) {
        val entry = outer.optJSONArray(index) ?: continue
        if (entry.optString(0) != "wrb.fr") continue
        if (entry.optString(1) != PLAY_DATA_RPC_ID) continue
        val payload = entry.optString(2)
        if (payload.isBlank() || payload == "null") continue

        val root = runCatching { JSONArray(payload) }.getOrNull() ?: continue
        val packageName = root.optJSONArray(1)
            ?.optJSONObject(3)
            ?.optJSONArray("12")
            ?.optJSONArray(0)
            ?.optString(0)
            ?.trim()
            .orEmpty()
        if (packageName.isBlank()) continue

        val details = root.optJSONArray(1)?.optJSONArray(2)
        val version = playVersion(details)
        result[packageName] = if (version.isNullOrBlank()) {
            PlayBatchEntry.Hidden
        } else {
            PlayBatchEntry.Found(version, playTitle(details))
        }
    }
    return result
}

private fun playVersion(details: JSONArray?): String? {
    if (details == null) return null

    details.optJSONArray(140)
        ?.optJSONArray(0)
        ?.optJSONArray(0)
        ?.optString(0)
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        ?.let { return it }

    val last = details.optJSONObject(details.length() - 1) ?: return null
    return last.optJSONArray("141")
        ?.optJSONArray(0)
        ?.optJSONArray(0)
        ?.optString(0)
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
}

private fun playTitle(details: JSONArray?): String? =
    details?.optJSONArray(0)
        ?.optString(0)
        ?.takeIf { it.isNotBlank() }

private const val PLAY_DATA_RPC_ID = "Ws7gDc"

private const val PACKAGE_TOKEN = "__PACKAGE__"

/**
 * Field mask used by the Play web app for ds:5. The package id is the only
 * part that changes between apps. Keeping the exact server-side shape avoids
 * an extra 1.2 MB page download just to read this value.
 */
private const val PLAY_DATA_REQUEST_TEMPLATE =
    """[null,null,[[1,9,10,11,13,14,19,20,38,43,47,49,52,58,59,63,69,70,73,74,75,78,79,80,91,92,95,96,97,100,101,103,106,112,119,129,137,140,139,141,145,146,149,151,155,169,183,187,193]],[[[1,null,1],null,[[[]]],null,null,null,null,[null,2],null,null,null,null,null,null,null,null,null,null,null,null,null,null,[1]],[null,[[[]]],null,null,[1]],[null,[[[]]],null,[1]],[null,[[[]]]],null,null,null,null,[[[[]]]],[[[[]]]]],null,[["__PACKAGE__",7]]]"""
