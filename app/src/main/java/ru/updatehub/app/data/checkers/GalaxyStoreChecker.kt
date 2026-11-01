package ru.updatehub.app.data.checkers

import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource

/**
 * Galaxy Store exposes a small JSON endpoint for every app detail page:
 * https://galaxystore.samsung.com/api/detail/<package>
 *
 * For a published app the response contains a `DetailMain` object with
 * `contentBinaryVersion` and `contentName`. For unknown or suspended apps
 * Samsung returns a tiny JSON with `errCode` 9900 / 9901 instead.
 *
 * Samsung chooses the catalogue by the caller's country. The API also honours
 * an X-Forwarded-For header, so a second request with a US address can reveal
 * apps that are hidden in the Russian catalogue. Google's translation proxy
 * is the third independent route: it fetches the API from Google's network.
 */
class GalaxyStoreChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.GALAXY_STORE

    override suspend fun check(packageName: String): CheckOutcome {
        val apiUrl = "https://galaxystore.samsung.com/api/detail/$packageName"
        val routes = listOf(
            FetchRoute(
                name = "Galaxy Store",
                url = apiUrl,
                timeoutMs = 4_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "Galaxy Store US",
                url = apiUrl,
                headers = mapOf("X-Forwarded-For" to "8.8.8.8"),
                timeoutMs = 4_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "Galaxy Store translate",
                url = translateUrl(apiUrl),
                timeoutMs = 5_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "Galaxy Store US 2",
                url = apiUrl,
                headers = mapOf("X-Forwarded-For" to "1.1.1.1"),
                timeoutMs = 3_500L,
                authoritative = false
            )
        )

        val result = tryRoutes(routes, budgetMs = 8_500L) { _, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> parse(response.body)
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "Galaxy Store: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parse(body: String): RouteReply<CheckOutcome.Found> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ")

        when (json.optString("errCode")) {
            "9900", "9901", "9904" -> return RouteReply.Missing
            "" -> Unit
            else -> return RouteReply.Failed("код ${json.optString("errCode")}")
        }

        val main = json.optJSONObject("DetailMain") ?: return RouteReply.Missing
        val version = main.optString("contentBinaryVersion").trim()
        if (version.isBlank()) return RouteReply.Failed("версия скрыта")

        val title = main.optString("contentName").trim().takeIf { it.isNotBlank() }
        return RouteReply.Found(
            CheckOutcome.Found(versionName = version, title = title)
        )
    }
}
