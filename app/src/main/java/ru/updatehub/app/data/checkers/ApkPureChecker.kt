package ru.updatehub.app.data.checkers

import ru.updatehub.app.data.UpdateSource
import java.net.URLDecoder

class ApkPureChecker : StoreChecker, VersionDetailsProvider {
    override val source: UpdateSource = UpdateSource.APKPURE

    override suspend fun check(packageName: String): CheckOutcome {
        val detailUrl = "https://apkpure.com/-/$packageName"
        val routes = listOf(
            FetchRoute(
                name = "APKPure",
                url = detailUrl,
                timeoutMs = 4_500L
            ),
            // The download endpoint lives on a different CDN and is often
            // reachable from networks that block the HTML page. HEAD resolves
            // the final filename without transferring the APK.
            FetchRoute(
                name = "APKPure APK",
                url = "https://d.apkpure.com/b/APK/$packageName?version=latest",
                timeoutMs = 4_500L,
                method = RouteMethod.HEAD
            ),
            FetchRoute(
                name = "APKPure XAPK",
                url = "https://d.apkpure.com/b/XAPK/$packageName?version=latest",
                timeoutMs = 4_000L,
                method = RouteMethod.HEAD
            ),
            // Google's translation proxy fetches the page from its own
            // infrastructure and works around regional blocks. It is slower,
            // so it runs only when both direct routes failed.
            FetchRoute(
                name = "APKPure translate",
                url = translateUrl(detailUrl),
                timeoutMs = 5_500L
            )
        )

        val result = tryRoutes(routes, budgetMs = 11_000L) { route, response ->
            when {
                // Only the HTML page can answer "this app does not exist".
                // A 404 from d.apkpure.com just means that this particular
                // APK/XAPK variant is unavailable.
                response.code == 404 && route.method == RouteMethod.GET ->
                    RouteReply.Missing

                response.code !in 200..399 -> RouteReply.Failed(
                    "HTTP ${response.code}",
                    retryable = response.code.isRetryableHttp()
                )

                route.method == RouteMethod.HEAD -> {
                    val version = versionFromApkPureUrl(response.finalUrl)
                    if (version.isNullOrBlank()) {
                        RouteReply.Failed("не удалось прочитать имя файла")
                    } else {
                        RouteReply.Found(CheckOutcome.Found(versionName = version))
                    }
                }

                else -> {
                    val version = versionFromPage(response.body)
                    if (version.isNullOrBlank()) {
                        // A Cloudflare challenge is served with HTTP 200 and
                        // no version. Let the next route try.
                        RouteReply.Failed("не отдал номер версии")
                    } else {
                        RouteReply.Found(CheckOutcome.Found(versionName = version))
                    }
                }
            }
        }

        return when (result) {
            is RouteResult.Found -> result.value
            RouteResult.NotFound -> CheckOutcome.NotFound
            is RouteResult.Failed -> CheckOutcome.Failed(
                "APKPure: ${result.message}",
                transient = true,
                retryable = result.retryable
            )
        }
    }

    /**
     * APKPure's download page contains the architecture and minimum Android
     * release of the current file. The app detail page does not, so it is only
     * requested for versions that look like real updates.
     */
    override suspend fun details(packageName: String): VersionDetails? {
        val downloadUrl = "https://apkpure.com/-/$packageName/download"
        val result = tryRoutes(
            routes = listOf(
                FetchRoute(
                    name = "APKPure details",
                    url = downloadUrl,
                    timeoutMs = 4_500L
                ),
                FetchRoute(
                    name = "APKPure details translate",
                    url = translateUrl(downloadUrl),
                    timeoutMs = 5_000L
                )
            ),
            budgetMs = 7_000L
        ) { _, response ->
            when {
                response.code == 404 -> RouteReply.Missing
                response.code !in 200..299 ->
                    RouteReply.Failed("HTTP ${response.code}")

                else -> RouteReply.Found(parseVersionDetails(response.body))
            }
        }
        return (result as? RouteResult.Found)?.value
    }
}

private val jsonVersionRegex = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"")
private val htmlVersionRegex = Regex("""class=\"version one-line\"[^>]*>\s*([^<]+)<""")
// The download page is localised (UniStore requests ru-RU), so both Russian
// and English labels are accepted. Each variant of an app has its own
// architecture/minSdk row; [parseVersionDetails] merges all of them.
private val architectureRegex = Regex(
    """(?:Архитектура|Architecture)\s*</(?:div|span)>\s*""" +
        """<(?:div|span) class="value[^"]*">([^<]*)<""",
    RegexOption.IGNORE_CASE
)
private val androidRegex = Regex(
    """(?:Требуемая версия Android|Requires Android)\s*</(?:div|span)>\s*""" +
        """<(?:div|span) class="value[^"]*">([^<]*)<""",
    RegexOption.IGNORE_CASE
)
private val apiRegex = Regex("""API\s*(\d+)""", RegexOption.IGNORE_CASE)
private val bundleTagRegex = Regex("""data-tag="XAPK"""", RegexOption.IGNORE_CASE)
private val filenameQueryRegex = Regex("""[?&]filename=([^&#]+)""")
private val apkPureFilenameRegex = Regex(
    """_([^_/]+)_APKPure\.(?:apk|xapk)$""",
    RegexOption.IGNORE_CASE
)

/**
 * Parses architecture and Android requirement metadata from an APKPure
 * download page. A page can list several variants (for example a 32-bit APK
 * and a 64-bit XAPK). Architectures are unioned and the lowest minSdk wins, so
 * a compatible variant is not hidden just because it is not listed first.
 */
internal fun parseVersionDetails(body: String): VersionDetails {
    val architectures = architectureRegex.findAll(body)
        .flatMap { match ->
            match.groupValues[1]
                .split(',', '/')
                .asSequence()
        }
        .map { it.trim().lowercase().replace('_', '-') }
        .filter { it.isNotBlank() && it !in setOf("universal", "all", "noarch") }
        .toSet()

    val minSdk = androidRegex.findAll(body)
        .mapNotNull { match ->
            apiRegex.find(match.groupValues[1])
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
        .minOrNull()

    return VersionDetails(
        minSdk = minSdk,
        architectures = architectures,
        isBundle = bundleTagRegex.containsMatchIn(body)
    )
}

/** Reads the version from the app page (JSON or the visible "version" tag). */
internal fun versionFromPage(body: String): String? {
    val raw = jsonVersionRegex.find(body)?.groupValues?.getOrNull(1)
        ?: htmlVersionRegex.find(body)?.groupValues?.getOrNull(1)
    return raw?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Extracts the version from an APKPure CDN URL. The final redirect looks like
 * `...?filename=App+Name_26.41.0_APKPure.xapk`. Returns null when the URL has
 * no filename or the filename does not follow APKPure's naming scheme.
 */
internal fun versionFromApkPureUrl(url: String): String? {
    val encoded = filenameQueryRegex.find(url)?.groupValues?.getOrNull(1)
        ?: return null
    // The String overload exists on every supported Android version; the
    // Charset overload requires API 33 unless desugaring is enabled.
    val filename = URLDecoder.decode(encoded, Charsets.UTF_8.name())
    return apkPureFilenameRegex.find(filename)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
