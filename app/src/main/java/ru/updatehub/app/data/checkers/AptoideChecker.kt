package ru.updatehub.app.data.checkers

import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource

/**
 * Aptoide exposes its catalogue through several API frontends. The same
 * `getMeta` call is served by ws75 and ws2; the search endpoint is a third
 * independent way to obtain the current file version. All three are tried in
 * order so a single failing host does not hide an update.
 */
class AptoideChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.APTOIDE

    override suspend fun check(packageName: String): CheckOutcome {
        val metaPath = "/api/7/app/getMeta?package_name=$packageName"
        val routes = listOf(
            FetchRoute(
                name = "Aptoide ws75",
                url = "https://ws75.aptoide.com$metaPath",
                timeoutMs = 4_500L
            ),
            FetchRoute(
                name = "Aptoide ws2",
                url = "https://ws2.aptoide.com$metaPath",
                timeoutMs = 4_500L
            ),
            FetchRoute(
                name = "Aptoide search",
                url = "https://ws75.aptoide.com/api/7/apps/search" +
                    "?query=$packageName&limit=10",
                timeoutMs = 4_500L,
                authoritative = false
            )
        )

        val result = tryRoutes(routes, budgetMs = 9_000L) { route, response ->
            when {
                response.code == 404 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                route.name == "Aptoide search" ->
                    parseSearch(response.body, packageName)

                else -> parseMeta(response.body, packageName)
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "Aptoide: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parseMeta(
        body: String,
        packageName: String
    ): RouteReply<CheckOutcome.Found> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ API")
        val data = root.optJSONObject("data")
            ?: return RouteReply.Failed("пустой ответ API")
        val returnedPackage = data.optString("package")
        if (!returnedPackage.isNullOrBlank() &&
            !returnedPackage.equals(packageName, ignoreCase = true)
        ) {
            return RouteReply.Missing
        }
        return found(data, data.optJSONObject("file"))
    }

    private fun parseSearch(
        body: String,
        packageName: String
    ): RouteReply<CheckOutcome.Found> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ поиска")
        val list = root.optJSONObject("datalist")
            ?.optJSONArray("list")
            ?: return RouteReply.Failed("пустой ответ поиска")
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (!item.optString("package").equals(packageName, ignoreCase = true)) {
                continue
            }
            return found(item, item.optJSONObject("file"))
        }
        return RouteReply.Missing
    }

    private fun found(
        data: JSONObject,
        file: JSONObject?
    ): RouteReply<CheckOutcome.Found> {
        val version = file?.optString("vername")
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?: return RouteReply.Failed("версия скрыта")
        return RouteReply.Found(
            CheckOutcome.Found(
                versionName = version,
                versionCode = file.optLong("vercode", 0L).takeIf { it > 0 },
                title = data.optString("name")
                    ?.takeIf { it.isNotBlank() && !it.equals("null", true) },
                signatureSha1 = file.optJSONObject("signature")
                    ?.optString("sha1")
                    ?.replace(":", "")
                    ?.lowercase()
                    ?.takeIf { it.matches(sha1Regex) }
            )
        )
    }

    private companion object {
        val sha1Regex = Regex("[0-9a-f]{40}")
    }
}
