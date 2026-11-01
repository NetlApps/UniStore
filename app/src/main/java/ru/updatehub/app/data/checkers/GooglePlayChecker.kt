package ru.updatehub.app.data.checkers

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import ru.updatehub.app.data.UpdateSource

/**
 * Google Play does not have a public version API. The web page still embeds
 * the app details in AF_initDataCallback blocks; version is at
 * ds:5 -> [1][2][140][0][0][0] (with a fallback path used by the page).
 *
 * Some carriers block the play.google.com SNI while other Google frontends
 * remain reachable, so the page is requested through www.google.com first and
 * two alternate regional frontends next. The direct request remains the last
 * route for networks where fronting is filtered.
 *
 * For some apps (YouTube, Spotify, ...) Google deliberately hides the version
 * ("Varies with device"). In that case the checker reports a friendly error
 * and other enabled sources can still supply a version.
 */
class GooglePlayChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.GOOGLE_PLAY

    // Play pages are large and concurrent requests are rate-limited. Four at
    // a time keeps bulk checks responsive without turning every app into 429.
    private val gate = Semaphore(4)

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
                if (result.message.contains("скрывает версию")) {
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
        val path = "/store/apps/details?id=$packageName&hl=ru&gl=RU"
        val routes = listOf(
            FetchRoute(
                name = "Play через google.com",
                url = "https://www.google.com$path",
                frontedHost = "play.google.com",
                timeoutMs = 5_000L
            ),
            FetchRoute(
                name = "Play через google.co.uk",
                url = "https://www.google.co.uk$path",
                frontedHost = "play.google.com",
                timeoutMs = 4_000L
            ),
            FetchRoute(
                name = "Play через google.de",
                url = "https://www.google.de$path",
                frontedHost = "play.google.com",
                timeoutMs = 4_000L
            ),
            FetchRoute(
                name = "Play напрямую",
                url = "https://play.google.com$path",
                timeoutMs = 6_000L,
                slow = true
            )
        )

        return tryRoutes(routes, budgetMs) { _, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> {
                    val root = extractDs5(response.body)
                    val version = root?.let(::extractVersion)
                    if (version.isNullOrBlank()) {
                        // Hiding the version is a property of the app, not of
                        // this route: every frontend returns the same page.
                        RouteReply.Failed("скрывает версию", terminal = true)
                    } else {
                        RouteReply.Found(
                            CheckOutcome.Found(
                                versionName = version,
                                title = root.let(::extractTitle)
                            )
                        )
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
        const val CHECK_BUDGET_MS = 18_000L
        const val RETRY_BUDGET_MS = 6_000L
    }
}
