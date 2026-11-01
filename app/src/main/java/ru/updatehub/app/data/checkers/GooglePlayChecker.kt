package ru.updatehub.app.data.checkers

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource
import java.net.URLEncoder

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
class GooglePlayChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.GOOGLE_PLAY

    // The compact data endpoint is small (~15 KB); four concurrent requests
    // are safe even on LTE and cut a bulk check to under half a minute. The
    // full-page fallback is rare and still protected by the per-route
    // timeouts, so it does not need a smaller gate anymore.
    @Volatile
    private var gate = Semaphore(4)

    @Volatile
    private var metered = false

    fun setMeteredNetwork(enabled: Boolean) {
        metered = enabled
        gate = Semaphore(4)
    }

    override suspend fun check(packageName: String): CheckOutcome = gate.withPermit {
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
                    CheckOutcome.Failed(
                        "Google Play скрывает версию для этого приложения"
                    )
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

    private fun extractVersion(root: JSONArray): String? {
        val details = root.optJSONArray(1)?.optJSONArray(2) ?: return null

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

    private fun extractTitle(root: JSONArray): String? =
        root.optJSONArray(1)
            ?.optJSONArray(2)
            ?.optJSONArray(0)
            ?.optString(0)
            ?.takeIf { it.isNotBlank() }

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
        const val PLAY_DATA_RPC_ID = "Ws7gDc"
        const val PACKAGE_TOKEN = "__PACKAGE__"

        /**
         * Field mask used by the Play web app for ds:5. The package id is the
         * only part that changes between apps. Keeping the exact server-side
         * shape avoids an extra 1.2 MB page download just to read this value.
         */
        const val PLAY_DATA_REQUEST_TEMPLATE =
            """[null,null,[[1,9,10,11,13,14,19,20,38,43,47,49,52,58,59,63,69,70,73,74,75,78,79,80,91,92,95,96,97,100,101,103,106,112,119,129,137,140,139,141,145,146,149,151,155,169,183,187,193]],[[[1,null,1],null,[[[]]],null,null,null,null,[null,2],null,null,null,null,null,null,null,null,null,null,null,null,null,null,[1]],[null,[[[]]],null,null,[1]],[null,[[[]]],null,[1]],[null,[[[]]]],null,null,null,null,[[[[]]]],[[[[]]]]],null,[["__PACKAGE__",7]]]"""
    }
}
