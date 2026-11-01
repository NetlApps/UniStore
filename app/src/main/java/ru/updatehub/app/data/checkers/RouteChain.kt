package ru.updatehub.app.data.checkers

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

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

internal enum class RouteMethod { GET, HEAD }

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

    for (route in routes) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val remaining = budgetMs - elapsed
        if (remaining < 250L) break

        val timeout = minOf(route.timeoutMs, remaining)
        val reply = try {
            withTimeout(timeout) {
                parse(route, fetch(route))
            }
        } catch (e: TimeoutCancellationException) {
            lastError = "${route.name}: тайм-аут"
            Log.w(TAG, "${route.name}: тайм-аут ${timeout} мс")
            continue
        } catch (e: CancellationException) {
            // The caller cancelled the whole check (screen left, new check
            // started, source budget expired). Never turn it into a store
            // failure: later routes must not run after cancellation.
            throw e
        } catch (e: IOException) {
            lastError = "${route.name}: ${e.friendlyMessage()}"
            Log.w(TAG, "$lastError (${e.javaClass.simpleName})")
            continue
        } catch (e: Exception) {
            lastError = "${route.name}: ${e.friendlyMessage()}"
            Log.w(TAG, "$lastError (${e.javaClass.simpleName})")
            continue
        }

        when (reply) {
            is RouteReply.Found -> {
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
                    Log.w(TAG, "${route.name}: ${reply.message}")
                    return RouteResult.Failed(
                        message = "${route.name}: ${reply.message}",
                        retryable = reply.retryable
                    )
                }
                retryable = retryable || reply.retryable
                lastError = "${route.name}: ${reply.message}"
                Log.w(TAG, lastError)
            }
        }
    }

    return if (sawMissing) {
        RouteResult.NotFound
    } else {
        RouteResult.Failed(
            message = lastError ?: "Все маршруты недоступны",
            retryable = retryable
        )
    }
}

private suspend fun fetch(route: FetchRoute): HttpResult = when {
    route.method == RouteMethod.HEAD -> Http.head(route.url, route.headers)
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
