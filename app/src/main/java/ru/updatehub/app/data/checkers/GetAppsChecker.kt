package ru.updatehub.app.data.checkers

import ru.updatehub.app.data.DeviceProfileDetector
import ru.updatehub.app.data.UpdateSource

/**
 * GetApps has a global catalogue and a Chinese catalogue. They do not always
 * list the same packages or the same versions, so both pages are tried.
 *
 * The global page is also reachable through Google's translation proxy on
 * networks where the direct host is filtered. All routes are non-final:
 * a 404 in the global catalogue does not mean the app is missing from the
 * Chinese one.
 */
class GetAppsChecker(
    /**
     * Chinese catalogue pages use a different version scheme and can point to
     * a locally published edition of the same package (for example the
     * Chinese Subway Surfers build 7.x next to the global 3.x build). They are
     * only useful on Chinese firmware; on a global ROM they produce updates
     * that the installed GetApps cannot deliver.
     *
     * The value is resolved lazily: reading the MIUI region spawns `getprop`,
     * and doing that in Application.onCreate delayed the first frame for a
     * flag that only a check needs.
     */
    chineseCatalog: () -> Boolean = { DeviceProfileDetector.isChineseMarket() }
) : StoreChecker {
    override val source: UpdateSource = UpdateSource.GETAPPS

    private val includeChineseCatalog: Boolean by lazy(chineseCatalog)

    override suspend fun check(packageName: String): CheckOutcome {
        val globalUrl = "https://global.app.mi.com/details?id=$packageName"
        val routes = buildList {
            add(
                FetchRoute(
                    name = "GetApps",
                    url = globalUrl,
                    timeoutMs = 4_500L,
                    authoritative = false
                )
            )
            add(
                FetchRoute(
                    name = "GetApps RU",
                    url = "$globalUrl&lo=RU&la=ru",
                    timeoutMs = 3_500L,
                    authoritative = false
                )
            )
            if (includeChineseCatalog) {
                add(
                    FetchRoute(
                        name = "GetApps CN",
                        url = "https://app.mi.com/details?id=$packageName",
                        timeoutMs = 4_000L,
                        authoritative = false
                    )
                )
                add(
                    FetchRoute(
                        name = "GetApps CN mobile",
                        url = "https://m.app.mi.com/details?id=$packageName",
                        timeoutMs = 3_500L,
                        authoritative = false
                    )
                )
            }
            add(
                FetchRoute(
                    name = "GetApps translate",
                    url = translateUrl(globalUrl),
                    timeoutMs = 5_000L,
                    authoritative = false
                )
            )
        }

        val result = tryRoutes(routes, budgetMs = 10_000L) { route, response ->
            when {
                response.code == 404 || response.code == 410 -> RouteReply.Missing
                response.code !in 200..299 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                else -> parse(
                    body = response.body,
                    packageName = packageName,
                    // Chinese pages do not expose the package name. Global
                    // pages do, so a redirect to a featured app can be caught.
                    requirePackage = !route.name.startsWith("GetApps CN")
                )
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "GetApps: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parse(
        body: String,
        packageName: String,
        requirePackage: Boolean
    ): RouteReply<CheckOutcome.Found> {
        val pagePackage = packageNameRegex.find(body)?.groupValues?.get(1)
        if (pagePackage != null && pagePackage != packageName) {
            return RouteReply.Failed("страница другого приложения")
        }
        if (requirePackage && pagePackage == null) {
            return RouteReply.Failed("не удалось определить приложение")
        }

        val version = versionRegex.find(body)?.groupValues?.get(1)
            ?: cnInlineVersionRegex.find(body)?.groupValues?.get(1)
            ?: cnBlockVersionRegex.find(body)?.groupValues?.get(1)
        if (version.isNullOrBlank()) return RouteReply.Failed("не отдал номер версии")

        val code = codeRegex.find(body)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
        return RouteReply.Found(
            CheckOutcome.Found(
                versionName = version.trim(),
                versionCode = code,
                title = titleRegex.find(body)?.groupValues?.get(1)
            )
        )
    }

    private companion object {
        val packageNameRegex = Regex("""packageName\s*:\s*"([^"]+)"""")
        val versionRegex = Regex("versionName\\s*:\\s*\"([^\"]+)\"")
        val codeRegex = Regex("""versionCode\s*:\s*(\d+)""")
        val titleRegex = Regex("displayName\\s*:\\s*\"([^\"]+)\"")
        // Chinese pages render the version into the page instead of JSON:
        // "版本号：15.9.65" on mobile and a two-column div on desktop.
        val cnInlineVersionRegex = Regex("""版本号[：:]\s*([0-9][0-9A-Za-z._()+\-]*)""")
        val cnBlockVersionRegex = Regex("""版本号\s*</div>\s*<div[^>]*>\s*([0-9][^<]*)<""")
    }
}
