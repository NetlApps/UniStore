package ru.updatehub.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.updatehub.app.BuildConfig
import ru.updatehub.app.data.checkers.Http
import ru.updatehub.app.data.install.ApkDownloader
import ru.updatehub.app.data.install.ApkDownloadSpec
import ru.updatehub.app.data.install.DownloadedApkBundle
import java.io.File
import java.security.MessageDigest

/**
 * Description of the newest UniStore release published on GitHub.
 *
 * The JSON API is used instead of scraping the release page: it returns the
 * asset URL, size and a SHA-256 digest in one 5 KB answer. The APK is accepted
 * only when its download URL belongs to this repository and, when GitHub
 * provides a digest, when the downloaded file matches that digest.
 */
data class SelfUpdateInfo(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val assetName: String? = null,
    val assetSize: Long? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val releaseUrl: String = RELEASE_PAGE,
    val publishedAt: String? = null,
    val notes: String? = null,
    val error: String? = null
) {
    companion object {
        const val RELEASE_PAGE = "https://github.com/NetlApps/UniStore/releases/latest"
        private const val API =
            "https://api.github.com/repos/NetlApps/UniStore/releases/latest"
        private const val DOWNLOAD_PREFIX =
            "https://github.com/NetlApps/UniStore/releases/download/"

        fun current(): SelfUpdateInfo = SelfUpdateInfo(
            currentVersion = BuildConfig.VERSION_NAME
        )

        /** Reads the latest release. Network and API errors become [error]. */
        suspend fun load(): SelfUpdateInfo = withContext(Dispatchers.IO) {
            val currentVersion = BuildConfig.VERSION_NAME
            try {
                val response = Http.get(
                    API,
                    mapOf(
                        "Accept" to "application/vnd.github+json",
                        "X-GitHub-Api-Version" to "2022-11-28"
                    )
                )
                if (response.code !in 200..299) {
                    return@withContext SelfUpdateInfo(
                        currentVersion = currentVersion,
                        error = when (response.code) {
                            403, 429 -> "GitHub временно ограничил запросы"
                            404 -> "Релизы на GitHub не найдены"
                            else -> "GitHub ответил HTTP ${response.code}"
                        }
                    )
                }

                val root = JSONObject(response.body)
                val tag = root.optString("tag_name").trim().removePrefix("v")
                val releasesPage = root.optString("html_url")
                    .takeIf { it.startsWith("https://github.com/NetlApps/UniStore/") }
                    ?: RELEASE_PAGE
                if (tag.isBlank()) {
                    return@withContext SelfUpdateInfo(
                        currentVersion = currentVersion,
                        releaseUrl = releasesPage,
                        error = "В релизе не указана версия"
                    )
                }

                val assets = root.optJSONArray("assets")
                var chosen: JSONObject? = null
                if (assets != null) {
                    for (index in 0 until assets.length()) {
                        val asset = assets.optJSONObject(index) ?: continue
                        val name = asset.optString("name")
                        if (!name.endsWith(".apk", ignoreCase = true)) continue
                        val url = asset.optString("browser_download_url")
                        if (!url.startsWith(DOWNLOAD_PREFIX)) continue
                        if (chosen == null || name.startsWith("UniStore-")) chosen = asset
                    }
                }

                val updateAvailable =
                    VersionComparator.compare(tag, currentVersion) > 0
                SelfUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = tag,
                    updateAvailable = updateAvailable,
                    assetName = chosen?.optString("name")?.takeIf { it.isNotBlank() },
                    assetSize = chosen?.optLong("size", 0L)?.takeIf { it > 0 },
                    downloadUrl = chosen?.optString("browser_download_url")
                        ?.takeIf { it.startsWith(DOWNLOAD_PREFIX) },
                    sha256 = chosen?.optString("digest")
                        ?.removePrefix("sha256:")
                        ?.takeIf { it.length == 64 },
                    releaseUrl = releasesPage,
                    publishedAt = root.optString("published_at").takeIf { it.isNotBlank() },
                    notes = root.optString("body").takeIf { it.isNotBlank() }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                SelfUpdateInfo(
                    currentVersion = currentVersion,
                    error = throwable.message?.takeIf { it.isNotBlank() }
                        ?: "Не удалось связаться с GitHub"
                )
            }
        }
    }
}

/** Downloads and verifies the GitHub APK without installing it. */
class SelfUpdateDownloader(private val context: Context) {

    private val downloader = ApkDownloader(context)

    suspend fun download(
        info: SelfUpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadedApkBundle {
        val url = info.downloadUrl
            ?: error("В релизе нет APK-файла")
        if (!url.startsWith("https://github.com/NetlApps/UniStore/releases/download/")) {
            error("Ссылка на APK ведёт не в релизы UniStore")
        }

        val bundle = downloader.download(
            spec = ApkDownloadSpec(
                source = null,
                url = url,
                pageUrl = info.releaseUrl
            ),
            packageName = UNISTORE_PACKAGE,
            maxConnections = 3,
            onProgress = onProgress
        )

        val expected = info.sha256
        if (!expected.isNullOrBlank()) {
            val actual = fileSha256(bundle.baseApk)
            if (!actual.equals(expected, ignoreCase = true)) {
                bundle.delete()
                error("SHA-256 скачанного APK не совпал с релизом")
            }
        }
        return bundle
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
