package ru.updatehub.app.data.checkers

import android.os.Build
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A lightweight release description from APKPure's own JSON API.
 *
 * The web page is protected by a Cloudflare browser check and weighs about
 * 350 KB; the API answers with the same version, signature, ABI and download
 * metadata from a host that is not behind that challenge. It is therefore the
 * primary route for APKPure checks.
 */
internal data class ApkPureRelease(
    val versionName: String,
    val versionCode: Long?,
    val signature: String?,
    val signatureSha1: String?,
    val title: String?,
    val downloadUrl: String?,
    val details: VersionDetails
) {
    fun toOutcome(): CheckOutcome.Found = CheckOutcome.Found(
        versionName = versionName,
        versionCode = versionCode,
        title = title,
        signature = signature,
        signatureSha1 = signatureSha1
    )
}

internal sealed interface ApkPureApiResult {
    data class Found(val release: ApkPureRelease) : ApkPureApiResult
    data object Missing : ApkPureApiResult
    data class Failed(
        val message: String,
        val retryable: Boolean = false
    ) : ApkPureApiResult
}

internal object ApkPureApi {
    const val ROUTE_NAME = "APKPure API"

    private const val API_BASE = "https://tapi.pureapk.com/v3/get_app_his_version"
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    private val universalAbis = setOf("universal", "all", "noarch", "unlimited")

    // APKPure publishes ChromeOS builds in the same history list. They have
    // no native ABI, a tiny stub APK and version codes around 2,000,000,000,
    // so without this filter they look like the newest phone release.
    private val chromeOsMarker = Regex(
        """(^|[-_.\s])(cros|chromeos)([-_.\s]|$)""",
        RegexOption.IGNORE_CASE
    )

    private data class CachedRelease(
        val release: ApkPureRelease,
        val at: Long
    )

    // The checker and the install resolver run within a couple of minutes of
    // each other. Reusing the JSON response avoids downloading the same
    // 300–500 KB payload twice on a metered connection.
    private val cache = ConcurrentHashMap<String, CachedRelease>()

    /**
     * APKPure requires the Android-style headers for the JSON endpoint. A
     * plain request is answered with HTTP 400, even when the package exists.
     */
    fun headers(sdkInt: Int = Build.VERSION.SDK_INT): Map<String, String> = mapOf(
        "Ual-Access-Businessid" to "projecta",
        "Ual-Access-ProjectA" to """{"device_info":{"os_ver":"$sdkInt"}}"""
    )

    fun url(packageName: String): String = API_BASE.toHttpUrl()
        .newBuilder()
        .addQueryParameter("package_name", packageName)
        .addQueryParameter("hl", "en")
        .build()
        .toString()

    /**
     * Best-effort lookup for the download resolver. Network errors are turned
     * into null; check() uses [parse] directly so it can show a useful error.
     */
    suspend fun latest(
        packageName: String,
        deviceAbis: List<String> = emptyList(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ): ApkPureRelease? {
        cached(packageName, deviceAbis)?.let { return it }
        return try {
            val response = Http.get(url(packageName), headers(sdkInt))
            if (response.code !in 200..299) {
                null
            } else {
                (parse(response.body, deviceAbis) as? ApkPureApiResult.Found)
                    ?.release
                    ?.also { remember(packageName, deviceAbis, it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** Shares a release parsed by the checker with the install resolver. */
    fun remember(
        packageName: String,
        deviceAbis: List<String>,
        release: ApkPureRelease
    ) {
        if (cache.size > 64) {
            val now = System.currentTimeMillis()
            cache.entries.removeAll { now - it.value.at > CACHE_TTL_MS }
        }
        cache[cacheKey(packageName, deviceAbis)] = CachedRelease(
            release = release,
            at = System.currentTimeMillis()
        )
    }

    private fun cached(packageName: String, deviceAbis: List<String>): ApkPureRelease? {
        val key = cacheKey(packageName, deviceAbis)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.at > CACHE_TTL_MS) {
            cache.remove(key, entry)
            return null
        }
        return entry.release
    }

    private fun cacheKey(packageName: String, deviceAbis: List<String>): String =
        packageName + "|" + deviceAbis.joinToString(",")

    /**
     * Parses the API response and picks one installable variant of the newest
     * version. Newer APKPure listings contain several entries with the same
     * version code, one per ABI. Picking the first entry blindly marked a
     * 64-bit-only variant as the only option on a 32-bit phone.
     */
    fun parse(body: String, deviceAbis: List<String> = emptyList()): ApkPureApiResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return ApkPureApiResult.Failed("APKPure API вернул некорректный ответ")

        val code = root.optInt("retcode", -1)
        if (code != 0) {
            val message = root.optString("errmsg")
                .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            return ApkPureApiResult.Failed(
                message?.let { "APKPure API: $it" } ?: "APKPure API вернул код $code"
            )
        }

        val list = root.optJSONArray("version_list")
            ?: return ApkPureApiResult.Failed("APKPure API не вернул список версий")
        if (list.length() == 0) return ApkPureApiResult.Missing

        val entries = (0 until list.length()).mapNotNull { index ->
            list.optJSONObject(index)?.let(::readEntry)
        }.filter { it.versionName.isNotBlank() && !it.isChromeOsVariant }
        if (entries.isEmpty()) {
            // A ChromeOS build can have a huge version code and would win over
            // the phone build if it were kept. If nothing phone-compatible is
            // left, let the HTML/CDN routes decide instead of reporting a
            // bogus update.
            return ApkPureApiResult.Missing
        }

        val newestCode = entries.mapNotNull { it.versionCode }.maxOrNull()
        val newest = if (newestCode == null) {
            listOf(entries.first())
        } else {
            entries.filter { it.versionCode == newestCode }.ifEmpty { listOf(entries.first()) }
        }

        val device = deviceAbis.map(::normalizeAbi).toSet()
        // Prefer a variant that can actually run on this phone. When the ABI
        // list is unknown, a universal build is the safest choice.
        val chosen = newest.firstOrNull { entry ->
            entry.nativeCode.any { it in device }
        } ?: newest.firstOrNull { it.isUniversal } ?: newest.first()

        val hasUniversalVariant = newest.any { it.isUniversal }
        val architectures = if (hasUniversalVariant) {
            emptySet()
        } else {
            newest.flatMap { it.nativeCode }
                .map(::normalizeAbi)
                .filter { it.isNotBlank() && it !in universalAbis }
                .toSet()
        }
        val minSdk = newest.mapNotNull { it.minSdk }.minOrNull()

        val release = ApkPureRelease(
            versionName = chosen.versionName,
            versionCode = chosen.versionCode ?: newestCode,
            signature = chosen.signature,
            signatureSha1 = chosen.signatureSha1,
            title = chosen.title,
            downloadUrl = chosen.downloadUrl,
            details = VersionDetails(
                minSdk = minSdk,
                architectures = architectures,
                isBundle = chosen.isBundle
            )
        )
        return ApkPureApiResult.Found(release)
    }

    private data class Entry(
        val versionName: String,
        val versionCode: Long?,
        val nativeCode: List<String>,
        val minSdk: Int?,
        val title: String?,
        val downloadUrl: String?,
        val isBundle: Boolean,
        val signature: String?,
        val signatureSha1: String?
    ) {
        val isUniversal: Boolean
            get() = nativeCode.isEmpty() || nativeCode.any { it in universalAbis }

        val isChromeOsVariant: Boolean
            get() = chromeOsMarker.containsMatchIn(versionName)
    }

    private fun readEntry(item: JSONObject): Entry {
        val asset = item.optJSONObject("asset")
        val assetType = asset?.optString("type")
            ?.trim()
            ?.uppercase()
            .orEmpty()
        val nativeCode = item.stringList("native_code").map(::normalizeAbi)
        val hash = item.signatureHash()
        return Entry(
            versionName = firstNonBlank(
                item.optString("version_name"),
                item.optString("versionName")
            ).orEmpty(),
            versionCode = item.versionCode(),
            nativeCode = nativeCode,
            minSdk = item.optString("sdk_version").toIntOrNull(),
            title = firstNonBlank(item.optString("title"), item.optString("label")),
            downloadUrl = asset?.downloadUrl(),
            isBundle = item.optBoolean("is_a_p_ks", false) ||
                assetType == "XAPK" ||
                assetType == "APKS",
            signature = hash?.takeIf { it.length == 64 },
            signatureSha1 = hash?.takeIf { it.length == 40 }
        )
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { index ->
                array.optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
    }

    private fun JSONObject.versionCode(): Long? =
        optString("version_code").toLongOrNull()
            ?: optLong("version_code", -1L).takeIf { it >= 0 }

    private fun JSONObject.signatureHash(): String? {
        val value = when (val raw = opt("sign")) {
            is JSONArray -> raw.optString(0)
            is String -> raw
            else -> null
        } ?: return null
        return value.trim()
            .lowercase()
            .takeIf { it.length == 40 || it.length == 64 }
    }

    private fun JSONObject.downloadUrl(): String? {
        optString("url")
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let { return it }
        val urls = optJSONArray("urls") ?: return null
        return (0 until urls.length())
            .mapNotNull { index ->
                urls.optString(index)
                    .trim()
                    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
            .firstOrNull()
    }

    private fun firstNonBlank(vararg values: String): String? =
        values.asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun normalizeAbi(value: String): String =
        value.trim().lowercase().replace('_', '-')
}
