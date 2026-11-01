package ru.updatehub.app.data.checkers

import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource
import java.net.URLEncoder

/**
 * V-Appstore (vivo / iQOO) returns a JSON search result for every package.
 * The public endpoint is fuzzy, so only an exact package match is accepted.
 *
 * HTTPS, plain HTTP and Google's translation proxy are independent routes;
 * on some networks one of them is filtered while another still answers.
 */
class VivoStoreChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.VIVO_STORE

    override suspend fun check(packageName: String): CheckOutcome {
        val key = URLEncoder.encode(packageName, "UTF-8")
        val path = "/port/packages/search?key=$key"
        val httpsUrl = "https://main.appstore.vivo.com.cn$path"
        val routes = listOf(
            FetchRoute(
                name = "V-Appstore",
                url = httpsUrl,
                timeoutMs = 4_500L,
                authoritative = false
            ),
            FetchRoute(
                name = "V-Appstore http",
                url = "http://main.appstore.vivo.com.cn$path",
                timeoutMs = 4_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "V-Appstore translate",
                url = translateUrl(httpsUrl),
                timeoutMs = 5_000L,
                authoritative = false
            )
        )

        val result = tryRoutes(routes, budgetMs = 8_000L) { _, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> parse(response.body, packageName)
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "V-Appstore: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parse(
        body: String,
        packageName: String
    ): RouteReply<CheckOutcome.Found> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ")

        if (!root.optBoolean("result", false)) return RouteReply.Missing

        val items = root.optJSONArray("value") ?: return RouteReply.Missing
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (!item.optString("package_name").equals(packageName, ignoreCase = true)) {
                continue
            }

            val version = item.optString("version_name").trim()
            if (version.isBlank()) return RouteReply.Failed("версия скрыта")

            return RouteReply.Found(
                CheckOutcome.Found(
                    versionName = version,
                    versionCode = item.optLong("version_code", 0L)
                        .takeIf { it > 0 },
                    title = item.optString("title_zh")
                        .takeIf { it.isNotBlank() }
                )
            )
        }
        return RouteReply.Missing
    }
}
