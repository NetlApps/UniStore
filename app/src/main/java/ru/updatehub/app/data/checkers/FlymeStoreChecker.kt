package ru.updatehub.app.data.checkers

import ru.updatehub.app.data.UpdateSource

/**
 * Flyme Store (Meizu) renders the app version directly into its public detail
 * page. Unknown packages return the same error page without a version block.
 *
 * HTTPS, plain HTTP and Google's translation proxy are tried in order; the
 * Chinese host is occasionally filtered on foreign mobile networks.
 */
class FlymeStoreChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.FLYME_STORE

    override suspend fun check(packageName: String): CheckOutcome {
        val path = "/apps/public/detail?package_name=$packageName"
        val httpsUrl = "https://app.flyme.cn$path"
        val routes = listOf(
            FetchRoute(
                name = "Flyme Store",
                url = httpsUrl,
                headers = mapOf(
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6"
                ),
                timeoutMs = 4_500L,
                authoritative = false
            ),
            FetchRoute(
                name = "Flyme Store http",
                url = "http://app.flyme.cn$path",
                headers = mapOf(
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6"
                ),
                timeoutMs = 4_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "Flyme Store translate",
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

                else -> parse(response.body)
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "Flyme Store: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parse(body: String): RouteReply<CheckOutcome.Found> {
        if (body.contains("error_page")) return RouteReply.Missing
        val version = versionRegex.find(body)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.matches(versionPattern) }
            ?: return RouteReply.Failed("не отдал номер версии")
        return RouteReply.Found(CheckOutcome.Found(versionName = version))
    }

    private companion object {
        val versionRegex = Regex(
            """<div\s+class="app_content\s+ellipsis\s+noPointer">\s*([^<]+?)\s*</div>"""
        )
        val versionPattern = Regex("""[0-9][0-9A-Za-z._()+\- ]*""")
    }
}
