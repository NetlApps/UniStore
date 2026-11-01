package ru.updatehub.app.data.checkers

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One way of reaching a store. Routes are tried in order until one of them
 * produces a version. Each route has its own short timeout so a blocked host
 * cannot consume the whole per-source budget.
 *
 * The same checker can therefore use a regional frontend, a mirror, a public
 * API and a translation proxy without any of them starving the others.
 */
internal data class FetchRoute(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 4_500L,
    val method: RouteMethod = RouteMethod.GET,
    /** Form body for [RouteMethod.POST_FORM]. */
    val body: String? = null,
    /** Uses the client with longer read timeouts (large store pages). */
    val slow: Boolean = false,
    /**
     * Sends the request to [url] but overrides the Host header. Used for
     * Google Play, whose SNI is blocked by some carriers while other Google
     * frontends remain reachable.
     */
    val frontedHost: String? = null,
    /**
     * Whether a "not found" answer from this route is final. A non-final
     * route can report missing and let the next route look in another
     * region or catalogue.
     */
    val authoritative: Boolean = true
)

internal enum class RouteMethod { GET, HEAD, POST_FORM }

internal sealed interface RouteReply<out T> {
    data class Found<T>(val value: T) : RouteReply<T>

    data object Missing : RouteReply<Nothing>

    data class Failed(
        val message: String,
        val retryable: Boolean = false,
        /** A final answer (for example "the store hides the version"). */
        val terminal: Boolean = false
    ) : RouteReply<Nothing>
}

internal sealed interface RouteResult<out T> {
    data class Found<T>(val value: T) : RouteResult<T>
    data object NotFound : RouteResult<Nothing>
    data class Failed(
        val message: String,
        val retryable: Boolean = false
    ) : RouteResult<Nothing>
}

/**
 * Runs [routes] sequentially inside [budgetMs]. The first successful parse
 * wins. A timeout or a network error moves to the next route; an authoritative
 * "not found" stops immediately. Overall time never exceeds [budgetMs], which
 * keeps the outer per-source timeout from swallowing a useful fallback.
 */
internal suspend fun <T> tryRoutes(
    routes: List<FetchRoute>,
    budgetMs: Long,
    parse: (FetchRoute, HttpResult) -> RouteReply<T>
): RouteResult<T> {
    if (routes.isEmpty()) return RouteResult.Failed("Маршруты не настроены")
    val startedAt = SystemClock.elapsedRealtime()
    var lastError: String? = null
    var retryable = false
    var sawMissing = false
    var sawFailure = false

    for (route in orderRoutes(routes)) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val remaining = budgetMs - elapsed
        if (remaining < 250L) break

        val timeout = minOf(route.timeoutMs, remaining)
        val reply = try {
            withTimeout(timeout) {
                parse(route, fetch(route))
            }
        } catch (e: TimeoutCancellationException) {
            forgetRoute(route.name)
            sawFailure = true
            lastError = "${route.name}: тайм-аут"
            Log.w(TAG, "${route.name}: тайм-аут ${timeout} мс")
            continue
        } catch (e: CancellationException) {
            // The caller cancelled the whole check (screen left, new check
            // started, source budget expired). Never turn it into a store
            // failure: later routes must not run after cancellation.
            throw e
        } catch (e: IOException) {
            forgetRoute(route.name)
            sawFailure = true
            lastError = "${route.name}: ${e.friendlyMessage()}"
            Log.w(TAG, "$lastError (${e.javaClass.simpleName})")
            continue
        } catch (e: Exception) {
            forgetRoute(route.name)
            sawFailure = true
            lastError = "${route.name}: ${e.friendlyMessage()}"
            Log.w(TAG, "$lastError (${e.javaClass.simpleName})")
            continue
        }

        when (reply) {
            is RouteReply.Found -> {
                rememberRoute(route.name)
                Log.d(TAG, "${route.name}: версия найдена")
                return RouteResult.Found(reply.value)
            }

            RouteReply.Missing -> {
                if (route.authoritative) {
                    Log.d(TAG, "${route.name}: приложение не найдено")
                    return RouteResult.NotFound
                }
                sawMissing = true
                lastError = "${route.name}: не найдено"
            }

            is RouteReply.Failed -> {
                if (reply.terminal) {
                    forgetRoute(route.name)
                    Log.w(TAG, "${route.name}: ${reply.message}")
                    return RouteResult.Failed(
                        message = "${route.name}: ${reply.message}",
                        retryable = reply.retryable
                    )
                }
                forgetRoute(route.name)
                if (reply.retryable) coolDownRoute(route.name)
                retryable = retryable || reply.retryable
                lastError = "${route.name}: ${reply.message}"
                Log.w(TAG, lastError)
            }
        }
    }

    return if (sawMissing && !sawFailure) {
        RouteResult.NotFound
    } else {
        RouteResult.Failed(
            message = lastError ?: "Все маршруты недоступны",
            retryable = retryable
        )
    }
}

/**
 * Remembers which route answered last on this network. On a carrier that
 * blocks Play's direct hostname, the first app still pays for the frontend
 * timeout, but every following app starts with the route that actually
 * worked instead of repeating the same dead end.
 */
private val routePreferences = ConcurrentHashMap<String, Long>()

/**
 * Routes that recently answered 429/5xx. Repeating a rate-limited or broken
 * route for every app in a check is what turns one bad response into minutes
 * of timeouts; Obtainium applies the same per-host pause. The block is short
 * so a route recovers quickly on the next check.
 */
private val blockedUntil = ConcurrentHashMap<String, Long>()

private const val ROUTE_MEMORY_MS = 4 * 60 * 1000L
private const val ROUTE_COOLDOWN_MS = 60 * 1000L

private fun orderRoutes(routes: List<FetchRoute>): List<FetchRoute> {
    if (routes.size < 2) return routes

    val now = SystemClock.elapsedRealtime()
    val available = routes.filter { route ->
        val until = blockedUntil[route.name] ?: return@filter true
        if (until <= now) {
            blockedUntil.remove(route.name)
            true
        } else {
            false
        }
    }
    // If every route is cooling down, keep the normal chain instead of
    // failing the app without trying.
    if (available.isEmpty()) return routes

    var preferred: FetchRoute? = null
    var latestExpiry = Long.MIN_VALUE
    available.forEach { route ->
        val expires = routePreferences[route.name] ?: return@forEach
        if (expires <= now) {
            routePreferences.remove(route.name)
            return@forEach
        }
        if (expires > latestExpiry) {
            latestExpiry = expires
            preferred = route
        }
    }
    val first = preferred ?: return available
    return listOf(first) + available.filterNot { it.name == first.name }
}

private fun rememberRoute(name: String) {
    if (name.isBlank()) return
    routePreferences[name] = SystemClock.elapsedRealtime() + ROUTE_MEMORY_MS
}

private fun forgetRoute(name: String) {
    routePreferences.remove(name)
}

private fun coolDownRoute(name: String) {
    if (name.isBlank()) return
    blockedUntil[name] = SystemClock.elapsedRealtime() + ROUTE_COOLDOWN_MS
}

/** Clears learned route order after a Wi-Fi/cellular switch. */
internal fun clearRouteMemory() {
    routePreferences.clear()
    blockedUntil.clear()
}

private suspend fun fetch(route: FetchRoute): HttpResult = when {
    route.method == RouteMethod.HEAD -> Http.head(route.url, route.headers)
    route.method == RouteMethod.POST_FORM -> {
        val body = requireNotNull(route.body) {
            "POST_FORM route ${route.name} has no body"
        }
        if (route.frontedHost != null) {
            Http.postFormFronted(route.url, route.frontedHost, body, route.headers)
        } else {
            Http.postForm(route.url, body, route.headers)
        }
    }
    route.frontedHost != null ->
        Http.getFronted(route.url, route.frontedHost, route.headers)
    route.slow -> Http.getSlow(route.url, route.headers)
    else -> Http.get(route.url, route.headers)
}

internal fun Int.isRetryableHttp(): Boolean = this == 429 || this >= 500

/**
 * Rewrites a page URL to Google's translation proxy. The proxy fetches the
 * page from Google's infrastructure, which works around regional blocks on
 * the phone's own IP while preserving scripts (and therefore embedded
 * version JSON).
 */
internal fun translateUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    if (parsed.host.endsWith(".translate.goog")) return url
    return parsed.newBuilder()
        .host(parsed.host.replace('.', '-') + ".translate.goog")
        .addQueryParameter("_x_tr_sl", "auto")
        .addQueryParameter("_x_tr_tl", "ru")
        .addQueryParameter("_x_tr_hl", "ru")
        .build()
        .toString()
}

private const val TAG = "UpdateHub"
