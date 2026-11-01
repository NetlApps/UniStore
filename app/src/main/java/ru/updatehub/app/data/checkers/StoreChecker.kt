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
