package ru.updatehub.app.data.checkers

import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource

internal data class FdroidVersion(
    val name: String,
    val code: Long?
)

/**
 * F-Droid lists versions by name descending (2.0.2, 2.0.1, ... 1.23.2), which
 * puts a prerelease first even when [suggested] points at an older stable
 * build. Prefer the exact suggested version and only fall back to the first
 * usable entry when the API does not contain it.
 */
internal fun pickFdroidVersion(
    suggested: Long,
    packages: List<FdroidVersion>
): FdroidVersion? {
    if (suggested > 0) {
        packages.firstOrNull { it.code == suggested && it.name.isNotBlank() }
            ?.let { return it }
    }
    return packages.firstOrNull { it.name.isNotBlank() }
}

class FdroidChecker : StoreChecker {
    override val source: UpdateSource = UpdateSource.FDROID

    override suspend fun check(packageName: String): CheckOutcome {
        val apiUrl = "https://f-droid.org/api/v1/packages/$packageName"
        val pageUrl = "https://f-droid.org/packages/$packageName/"
        val routes = listOf(
            FetchRoute(
                name = "F-Droid API",
                url = apiUrl,
                timeoutMs = 4_500L
            ),
            FetchRoute(
                name = "F-Droid API translate",
                url = translateUrl(apiUrl),
                timeoutMs = 5_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "F-Droid page",
                url = pageUrl,
                timeoutMs = 4_000L,
                authoritative = false
            ),
            FetchRoute(
                name = "F-Droid page translate",
                url = translateUrl(pageUrl),
                timeoutMs = 5_000L,
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

                route.name.startsWith("F-Droid API") -> parseApi(response.body)
                else -> parsePage(response.body)
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "F-Droid: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    private fun parseApi(body: String): RouteReply<CheckOutcome.Found> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RouteReply.Failed("неожиданный ответ API")
        val suggested = json.optLong("suggestedVersionCode", -1L)
        val packages = json.optJSONArray("packages")
        if (packages == null || packages.length() == 0) return RouteReply.Missing

        val parsed = buildList {
            for (i in 0 until packages.length()) {
                val item = packages.optJSONObject(i) ?: continue
                val name = item.optString("versionName")
                if (name.isBlank()) continue
                add(
                    FdroidVersion(
                        name = name,
                        code = item.optLong("versionCode", -1L)
                            .takeIf { it > 0 }
                    )
                )
            }
        }
        val chosen = pickFdroidVersion(suggested, parsed)
            ?: return RouteReply.Missing
        return RouteReply.Found(
            CheckOutcome.Found(chosen.name, chosen.code)
        )
    }

    private fun parsePage(body: String): RouteReply<CheckOutcome.Found> {
        val versions = parseFdroidPageVersions(body)
        val chosen = versions.firstOrNull { !it.beta && it.version.isNotBlank() }
            ?: versions.firstOrNull { it.version.isNotBlank() }
            ?: return RouteReply.Failed("не отдал номер версии")
        return RouteReply.Found(
            CheckOutcome.Found(
                versionName = chosen.version,
                versionCode = chosen.code
            )
        )
    }
}

internal data class FdroidPageVersion(
    val version: String,
    val code: Long?,
    val beta: Boolean
)

/**
 * Extracts version blocks from an F-Droid package page. The page marks the
 * newest build as `latest`, which may be a beta even when a stable build is
 * available; the beta badge is therefore kept so the caller can prefer a
 * stable version and avoid offering a false prerelease update.
 */
internal fun parseFdroidPageVersions(body: String): List<FdroidPageVersion> {
    val versions = mutableListOf<FdroidPageVersion>()
    val chunks = body.split("<li class=\"package-version\"")
    for (chunk in chunks.drop(1)) {
        val match = FdroidPageHeaderRegex.find(chunk) ?: continue
        val tailStart = match.range.last + 1
        val tailEnd = minOf(chunk.length, tailStart + 400)
        val beta = chunk.substring(tailStart, tailEnd).contains("beta-badge")
        versions += FdroidPageVersion(
            version = match.groupValues[1].trim(),
            code = match.groupValues[2].toLongOrNull(),
            beta = beta
        )
    }
    return versions
}

private val FdroidPageHeaderRegex = Regex(
    """<b>\s*Version\s+([^<]+?)\s*</b>\s*\((\d+)\)""",
    RegexOption.IGNORE_CASE
)
