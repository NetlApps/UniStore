package ru.updatehub.app.data.checkers

import ru.updatehub.app.data.UpdateSource

sealed interface CheckOutcome {
    data class Found(
        val versionName: String,
        val versionCode: Long? = null,
        val title: String? = null,
        /** SHA-256 of the signing certificate when the source exposes it. */
        val signature: String? = null,
        /** SHA-1 of the signing certificate when the source exposes it. */
        val signatureSha1: String? = null
    ) : CheckOutcome

    data object NotFound : CheckOutcome

    data class Failed(
        val message: String,
        /** Network/HTTP failure that may affect the whole source, not just this app. */
        val transient: Boolean = false,
        /** Worth one immediate retry, e.g. HTTP 429 or a 5xx response. */
        val retryable: Boolean = false
    ) : CheckOutcome
}

interface StoreChecker {
    val source: UpdateSource
    suspend fun check(packageName: String): CheckOutcome
}

/**
 * A source that can resolve several packages in one request.
 *
 * Obtainium uses the same approach (`prefetchUpdateInfo`): a check of dozens
 * of apps should pay for one request per source group, not one request per
 * app. Fewer round trips matter most on a mobile link, where every new
 * request competes for the same four connection slots.
 */
interface StorePrefetcher {
    /**
     * Fills the per-package cache. A failure is not fatal: every package that
     * was not cached is checked individually by [StoreChecker.check].
     *
     * [networkSlot] runs one request under the repository's concurrency limit,
     * so a prefetch can never take more slots than a normal check.
     */
    suspend fun prefetch(packages: Collection<String>, networkSlot: NetworkSlot)

    /**
     * Waits for the prefetch result of [packageName] at most [timeoutMs].
     *
     * A null result means the package is not part of a running prefetch or
     * the batch did not return a usable card; the caller then falls back to
     * the normal per-app check. Waiting happens outside the network slot, so
     * queued checks cannot starve the batch request that they are waiting for.
     */
    suspend fun awaitPrefetched(packageName: String, timeoutMs: Long): CheckOutcome?
}

/** Lets a checker borrow a shared network slot without knowing the repository. */
interface NetworkSlot {
    suspend fun <T> withSlot(block: suspend () -> T): T
}

/**
 * Extra metadata that some stores publish on the download page. It lets
 * UniStore reject a version before downloading when its native code or Android
 * requirement cannot work on this phone.
 */
data class VersionDetails(
    val minSdk: Int? = null,
    val architectures: Set<String> = emptySet(),
    val isBundle: Boolean = false
)

interface VersionDetailsProvider {
    suspend fun details(packageName: String): VersionDetails?
}

internal fun Throwable.friendlyMessage(): String = when (this) {
    is java.net.SocketTimeoutException -> "Тайм-аут соединения"
    is java.net.UnknownHostException -> "Нет подключения к интернету"
    is java.net.ConnectException -> "Не удалось подключиться"
    else -> message?.takeIf { it.isNotBlank() } ?: "Ошибка сети"
}

/**
 * Store checkers catch network errors to turn them into a user-facing
 * [CheckOutcome]. Cancellation is not a store error: swallowing it keeps a
 * cancelled source running and produced the "Timed out waiting for 20000 ms"
 * messages after the per-source timeout had already fired.
 */
internal fun Throwable.rethrowIfCancelled() {
    if (this is kotlinx.coroutines.CancellationException) throw this
}
