package ru.updatehub.app.data.checkers

import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource

/**
 * RuStore has a public app-info endpoint that also exposes versionCode and
 * the signing certificate hash. That lets us reject channel builds which
 * cannot be installed over the current app (e.g. a TikTok APK republished
 * with a different signature).
 *
 * The API occasionally stalls on mobile networks, so the HTML page and a
 * translation-proxy copy are kept as independent routes. Each has a short
 * timeout; one blocked host cannot consume the whole source budget.
 */
class RuStoreChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.RUSTORE

    override suspend fun check(packageName: String): CheckOutcome {
        val pageUrl = "https://www.rustore.ru/catalog/app/$packageName"
        val routes = listOf(
            FetchRoute(
                name = "RuStore API",
                url = "https://backapi.rustore.ru/applicationData/overallInfo/$packageName",
                headers = mapOf(
                    "ruStoreVerCode" to RUSTORE_VERSION_CODE,
                    "Accept" to "application/json"
                ),
                timeoutMs = 4_500L
            ),
            FetchRoute(
                name = "RuStore",
                url = pageUrl,
                timeoutMs = 4_500L
            ),
            FetchRoute(
                name = "RuStore alt",
                url = "https://rustore.ru/catalog/app/$packageName",
                timeoutMs = 3_500L,
                authoritative = false
            ),
            FetchRoute(
                name = "RuStore translate",
                url = translateUrl(pageUrl),
                timeoutMs = 5_000L,
                authoritative = false
            )
        )

        val result = tryRoutes(routes, budgetMs = 9_000L) { route, response ->
            when {
                response.code in setOf(400, 404, 410) && route.method == RouteMethod.GET ->
                    RouteReply.Missing

                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                route.name == "RuStore API" -> parseApi(response.body)

                else -> {
                    val version = versionRegex.find(response.body)?.groupValues?.get(1)
                        ?: microdataRegex.find(response.body)?.groupValues?.get(1)
                    if (version.isNullOrBlank()) {
                        RouteReply.Failed("не отдал номер версии")
                    } else {
                        RouteReply.Found(CheckOutcome.Found(versionName = version.trim()))
                    }
                }
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "RuStore: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parseApi(body: String): RouteReply<CheckOutcome.Found> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ API")

        // A non-OK code can mean a stale API version or a temporary block.
        // Do not treat it as "not found": the HTML page can still answer.
        val code = json.optString("code")
        if (code != "OK") return RouteReply.Failed("код $code")

        val app = json.optJSONObject("body")
            ?: return RouteReply.Failed("пустой ответ API")
        val version = app.optString("versionName").trim()
        if (version.isBlank()) return RouteReply.Failed("версия скрыта")

        return RouteReply.Found(
            CheckOutcome.Found(
                versionName = version,
                versionCode = app.optLong("versionCode", 0L).takeIf { it > 0 },
                title = app.optString("appName").takeIf { it.isNotBlank() },
                signature = app.optString("signature")
                    .takeIf { signatureRegex.matches(it) }
                    ?.lowercase()
            )
        )
    }

    private companion object {
        const val RUSTORE_VERSION_CODE = "12000"
        val versionRegex = Regex("\"softwareVersion\"\\s*:\\s*\"([^\"]+)\"")
        val microdataRegex = Regex("""itemProp=\"softwareVersion\"\s*>\s*([^<]+)<""")
        val signatureRegex = Regex("[0-9a-fA-F]{64}")
    }
}
