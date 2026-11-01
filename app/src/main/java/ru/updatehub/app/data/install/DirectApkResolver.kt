package ru.updatehub.app.data.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.updatehub.app.data.UpdateSource
import java.util.concurrent.TimeUnit

data class ApkDownloadSpec(
    val source: UpdateSource,
    val url: String
)

/**
 * Resolves direct APK URLs for stores that expose them. Google Play, GetApps
 * and Galaxy Store do not publish direct APK links, so those keep using the
 * normal store handoff.
 */
class DirectApkResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun resolve(source: UpdateSource, packageName: String): ApkDownloadSpec =
        withContext(Dispatchers.IO) {
            when (source) {
                UpdateSource.RUSTORE -> resolveRuStore(packageName)
                UpdateSource.FDROID -> resolveFdroid(packageName)
                UpdateSource.APKPURE -> ApkDownloadSpec(
                    source = source,
                    // The XAPK endpoint returns the full bundle. For apps
                    // published as a single APK it redirects to that APK.
                    // The global host is used instead of the regional
                    // d-ru.apkpure.com mirror: the mirror now redirects every
                    // file request to the HTML home page, while the global
                    // host serves the file once the page cookies are warmed.
                    url = "https://d.apkpure.com/b/XAPK/$packageName?version=latest"
                )

                UpdateSource.APTOIDE -> resolveAptoide(packageName)
                else -> error("Для ${source.title} нет прямой ссылки на APK")
            }
        }

    private fun resolveRuStore(packageName: String): ApkDownloadSpec {
        val infoBody = get(
            "https://backapi.rustore.ru/applicationData/overallInfo/$packageName",
            mapOf(
                "ruStoreVerCode" to RUSTORE_VERSION_CODE,
                "Accept" to "application/json"
            )
        )
        val info = JSONObject(infoBody)
        if (info.optString("code") != "OK") {
            error("Приложение не найдено в RuStore")
        }
        val app = info.optJSONObject("body") ?: error("RuStore вернул пустой ответ")
        val appId = app.optLong("appId", 0L)
        if (appId <= 0) error("RuStore не указал идентификатор приложения")

        val payload = JSONObject()
            .put("appId", appId)
            .put("firstInstall", false)
            .toString()
        val downloadBody = postJson(
            "https://backapi.rustore.ru/applicationData/download-link",
            payload,
            mapOf("ruStoreVerCode" to RUSTORE_VERSION_CODE)
        )
        val download = JSONObject(downloadBody)
        if (download.optString("code") != "OK") {
            error("RuStore не выдал ссылку на APK")
        }
        val url = download.optJSONObject("body")
            ?.optString("apkUrl")
            .orEmpty()
        if (url.isBlank()) error("RuStore не выдал ссылку на APK")

        return ApkDownloadSpec(source = UpdateSource.RUSTORE, url = url)
    }

    private fun resolveFdroid(packageName: String): ApkDownloadSpec {
        val body = get("https://f-droid.org/api/v1/packages/$packageName")
        val json = JSONObject(body)
        val versionCode = json.optLong("suggestedVersionCode", 0L)
        if (versionCode <= 0) error("F-Droid не указал версию приложения")

        return ApkDownloadSpec(
            source = UpdateSource.FDROID,
            url = "https://f-droid.org/repo/${packageName}_$versionCode.apk"
        )
    }

    private fun resolveAptoide(packageName: String): ApkDownloadSpec {
        val body = get("https://ws75.aptoide.com/api/7/app/get/package_name=$packageName")
        val json = JSONObject(body)
        val data = json.optJSONObject("nodes")
            ?.optJSONObject("meta")
            ?.optJSONObject("data")
            ?: json.optJSONObject("data")
        val path = data?.optJSONObject("file")
            ?.optString("path")
            .orEmpty()
        if (path.isBlank()) error("Aptoide не выдал ссылку на APK")

        return ApkDownloadSpec(source = UpdateSource.APTOIDE, url = path)
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        return execute(request)
    }

    private fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        return execute(request)
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Сервер вернул ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val RUSTORE_VERSION_CODE = "12000"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
