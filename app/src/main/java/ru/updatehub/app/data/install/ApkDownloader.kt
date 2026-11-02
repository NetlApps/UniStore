package ru.updatehub.app.data.install

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** Base APK plus all split APKs extracted from an XAPK/APKS archive. */
data class DownloadedApkBundle(
    val packageName: String,
    val directory: File,
    val baseApk: File,
    val splitApks: List<File>
) {
    val allApks: List<File> get() = listOf(baseApk) + splitApks

    fun delete() {
        directory.deleteRecursively()
    }
}

class ApkDownloader(private val context: Context) {

    private val cookies = CopyOnWriteArrayList<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this@ApkDownloader.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this@ApkDownloader.cookies += cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.matches(url) }
    }

    private val baseClient = OkHttpClient.Builder()
        .dns(ru.updatehub.app.data.checkers.StoreDns.resolver)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Large APKs must not be killed by a total call timeout.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .cookieJar(cookieJar)
        .build()

    private val browserResolver = BrowserDownloadResolver()

    private fun Request.Builder.browserHeaders(referer: String) = apply {
        header("User-Agent", USER_AGENT)
        header("Accept", "*/*")
        header("Referer", referer)
        header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    suspend fun download(
        spec: ApkDownloadSpec,
        packageName: String,
        progressIntervalMs: Long = 200L,
        maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadedApkBundle = withContext(Dispatchers.IO) {
        val directory = updatesDirectory(context).apply { mkdirs() }
        cleanup(directory)

        val packageDirectory = File(directory, packageName).apply {
            deleteRecursively()
            mkdirs()
        }
        val temporary = File(directory, "$packageName.download")
        temporary.delete()

        val pageUrl = spec.pageUrl ?: "https://apkpure.com/ru/x/$packageName/download"
        var downloadUrl = spec.url
        if (spec.url.contains("apkpure.com")) {
            // OkHttp is blocked by APKPure's TLS fingerprint check, so the
            // signed data.winudf.com link is obtained from a real WebView.
            val resolved = browserResolver.resolve(pageUrl)
            if (!resolved.isNullOrBlank()) {
                downloadUrl = resolved
            } else {
                Log.w(TAG, "WebView не выдал ссылку, пробуем прямой запрос")
                warmUpApkPure(packageName)
            }
        }
        val request = Request.Builder()
            .url(downloadUrl)
            .browserHeaders(pageUrl)
            .header("Accept-Encoding", "identity")
            .build()

        try {
            Log.i(TAG, "Скачивание ${spec.source?.key ?: "GitHub"}: $downloadUrl")
            val probe = probeDownload(request)
            val ranges = if (probe != null &&
                supportsRangeDownload(
                    length = probe.length,
                    acceptRanges = probe.acceptRanges,
                    maxParts = maxConnections
                )
            ) {
                splitIntoRanges(probe.length, maxConnections)
            } else {
                emptyList()
            }

            if (ranges.size > 1 && probe != null) {
                Log.i(
                    TAG,
                    "Параллельная загрузка: ${ranges.size} частей, ${probe.length} байт"
                )
                try {
                    downloadParallel(
                        request = request,
                        temporary = temporary,
                        total = probe.length,
                        ranges = ranges,
                        directory = directory,
                        progressIntervalMs = progressIntervalMs,
                        onProgress = onProgress
                    )
                } catch (t: Throwable) {
                    // A CDN may ignore Range, expire the signed URL on a
                    // second request or return a different file. Falling back
                    // to the normal single stream keeps the update working.
                    Log.w(
                        TAG,
                        "Параллельная загрузка не удалась (${t.message}), " +
                            "повторяю одним соединением",
                        t
                    )
                    temporary.delete()
                    downloadSingle(
                        request = request,
                        temporary = temporary,
                        directory = directory,
                        progressIntervalMs = progressIntervalMs,
                        onProgress = onProgress
                    )
                }
            } else {
                downloadSingle(
                    request = request,
                    temporary = temporary,
                    directory = directory,
                    progressIntervalMs = progressIntervalMs,
                    onProgress = onProgress
                )
            }

            if (temporary.length() < 1024) {
                error("Скачанный файл слишком мал")
            }
            if (!isZip(temporary)) {
                error("Сервер вернул файл, который не является APK")
            }

            val bundle = extractBundle(temporary, packageDirectory, packageName)
            temporary.delete()
            bundle
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось скачать APK ${spec.source?.key ?: "GitHub"}: ${t.message}", t)
            temporary.delete()
            packageDirectory.deleteRecursively()
            throw t
        }
    }

    private fun probeDownload(request: Request): DownloadProbe? {
        val headRequest = request.newBuilder()
            .head()
            .build()
        return runCatching {
            baseClient.newCall(headRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("text/html", ignoreCase = true)) {
                    return@use null
                }
                val length = response.header("Content-Length")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: return@use null
                DownloadProbe(
                    length = length,
                    acceptRanges = response.header("Accept-Ranges")
                )
            }
        }.getOrNull()
    }

    private suspend fun downloadSingle(
        request: Request,
        temporary: File,
        directory: File,
        progressIntervalMs: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val response = baseClient.newCall(request).execute()
        response.use { result ->
            Log.i(
                TAG,
                "Ответ ${result.code}: ${result.request.url}; " +
                    "type=${result.header("Content-Type")}; " +
                    "length=${result.body?.contentLength()}"
            )
            if (!result.isSuccessful) {
                val detail = runCatching {
                    result.body?.source()?.let { source ->
                        source.request(512)
                        source.buffer.snapshot().utf8()
                    }
                }.getOrNull().orEmpty().replace(Regex("\\s+"), " ").take(300)
                Log.w(TAG, "Тело ответа: $detail")
                error("Сервер вернул ${result.code}")
            }
            val body = result.body ?: error("Сервер вернул пустой ответ")
            val contentType = result.header("Content-Type").orEmpty()
            if (contentType.contains("text/html", ignoreCase = true)) {
                error("Вместо APK сервер вернул веб-страницу")
            }

            val total = body.contentLength()
            checkSpace(directory, total)

            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloaded = 0L
                    var lastReport = 0L
                    val reportInterval = progressIntervalMs.coerceAtLeast(100L)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= reportInterval || downloaded == total) {
                            lastReport = now
                            onProgress(downloaded, total)
                        }
                    }
                }
            }

            if (total == 0L) {
                onProgress(temporary.length(), temporary.length())
            }
        }
    }

    private suspend fun downloadParallel(
        request: Request,
        temporary: File,
        total: Long,
        ranges: List<ByteRange>,
        directory: File,
        progressIntervalMs: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = coroutineScope {
        checkSpace(directory, total)

        val downloaded = AtomicLong(0L)
        val lastReport = AtomicLong(0L)
        val reportInterval = progressIntervalMs.coerceAtLeast(100L)

        fun report(force: Boolean = false) {
            if (force) {
                onProgress(downloaded.get(), total)
                return
            }
            val now = System.currentTimeMillis()
            val previous = lastReport.get()
            if (now - previous >= reportInterval &&
                lastReport.compareAndSet(previous, now)
            ) {
                onProgress(downloaded.get(), total)
            }
        }

        try {
            RandomAccessFile(temporary, "rw").use { it.setLength(total) }

            ranges.map { range ->
                async(Dispatchers.IO) {
                    // A single CDN reset should not throw away a 300 MB
                    // download. Retry the failed part a few times and remove
                    // only its already counted bytes; the other parts keep
                    // their data in the preallocated file.
                    var attempt = 0
                    while (true) {
                        var written = 0L
                        try {
                            val partRequest = request.newBuilder()
                                .header("Range", "bytes=${range.start}-${range.endInclusive}")
                                .build()
                            baseClient.newCall(partRequest).execute().use { response ->
                                if (response.code != 206) {
                                    error("Сервер не поддержал Range: ${response.code}")
                                }
                                val contentRange = parseContentRange(
                                    response.header("Content-Range")
                                ) ?: error("Сервер не вернул Content-Range")
                                if (contentRange.start != range.start ||
                                    contentRange.endInclusive != range.endInclusive
                                ) {
                                    error("Сервер вернул неверный Content-Range")
                                }
                                if (contentRange.total != null && contentRange.total != total) {
                                    error("Размер файла изменился во время загрузки")
                                }
                                if (response.header("Content-Encoding")?.let {
                                        !it.equals("identity", ignoreCase = true)
                                    } == true
                                ) {
                                    error("Сервер сжал часть диапазона")
                                }
                                val body = response.body ?: error("Пустой ответ")
                                RandomAccessFile(temporary, "rw").use { file ->
                                    file.seek(range.start)
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            file.write(buffer, 0, read)
                                            written += read
                                            downloaded.addAndGet(read.toLong())
                                            report()
                                        }
                                    }
                                }
                            }
                            if (written != range.length) {
                                error(
                                    "Часть ${range.start}-${range.endInclusive} " +
                                        "недокачана: $written из ${range.length}"
                                )
                            }
                            break
                        } catch (t: Throwable) {
                            downloaded.addAndGet(-written)
                            if (t is CancellationException || attempt >= MAX_PART_RETRIES) {
                                throw t
                            }
                            attempt++
                            Log.w(
                                TAG,
                                "Часть ${range.start}-${range.endInclusive} прервана " +
                                    "на $written байт, повтор $attempt",
                                t
                            )
                            delay(PART_RETRY_DELAY_MS * attempt)
                        }
                    }
                }
            }.awaitAll()

            if (temporary.length() != total) {
                error("Размер скачанного файла не совпал с ожидаемым")
            }
            report(force = true)
        } catch (t: Throwable) {
            temporary.delete()
            throw t
        }
    }

    private fun checkSpace(directory: File, total: Long) {
        if (total <= 0) return
        val required = total + total / 2
        if (directory.usableSpace < required) {
            error("Недостаточно свободного места для загрузки")
        }
    }

    /**
     * Opens the APKPure app page before the file request. The download host
     * expects the cookies a browser would have received (`download_id`,
     * `_apk_uid` and friends); without them OkHttp is sent back to the HTML
     * home page even though the file URL itself is valid.
     */
    private fun warmUpApkPure(packageName: String) {
        val pageUrl = "https://apkpure.com/ru/x/$packageName/download"
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        runCatching {
            baseClient.newCall(request).execute().use { response ->
                Log.i(TAG, "Подготовка APKPure: ${response.code}")
                response.body?.close()
            }
        }.onFailure {
            Log.w(TAG, "Не удалось открыть страницу APKPure: ${it.message}")
        }
    }

    /**
     * A plain APK is copied as one file. An XAPK contains the base APK and
     * optional split APKs; every `.apk` entry is kept, because installing only
     * the base triggers Android's "несовместимо с телефоном" screen when the
     * manifest requires ABI or density splits.
     */
    private fun extractBundle(
        downloaded: File,
        directory: File,
        packageName: String
    ): DownloadedApkBundle {
        val archiveApks = runCatching {
            ZipFile(downloaded).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .map { it.name }
                    .toList()
            }
        }.getOrDefault(emptyList())

        if (archiveApks.isEmpty()) {
            val target = File(directory, "$packageName.apk")
            downloaded.copyTo(target, overwrite = true)
            return DownloadedApkBundle(
                packageName = packageName,
                directory = directory,
                baseApk = target,
                splitApks = emptyList()
            )
        }

        val baseEntryName = readBaseEntryName(downloaded)
        val extracted = mutableListOf<File>()
        ZipFile(downloaded).use { zip ->
            val usedNames = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue
                val fileName = entry.name.substringAfterLast('/')
                if (fileName.isBlank() || !usedNames.add(fileName)) continue

                val target = File(directory, fileName)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                extracted += target
            }
        }
        if (extracted.isEmpty()) error("APK не найден в архиве")

        val base = extracted.firstOrNull { it.name.equals(baseEntryName, ignoreCase = true) }
            ?: extracted.firstOrNull { looksLikeBaseApk(it, packageName) }
            ?: extracted.maxByOrNull { it.length() }
            ?: error("Не удалось определить основной APK")

        val splits = extracted
            .filter { it.canonicalFile != base.canonicalFile }
            .sortedBy { it.name.lowercase() }

        return DownloadedApkBundle(
            packageName = packageName,
            directory = directory,
            baseApk = base,
            splitApks = splits
        )
    }

    private fun readBaseEntryName(downloaded: File): String? = runCatching {
        ZipFile(downloaded).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@runCatching null
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val parts = JSONObject(json).optJSONArray("split_apks") ?: return@runCatching null
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("id").equals("base", ignoreCase = true)) {
                    return@runCatching part.optString("file").takeIf { it.isNotBlank() }
                }
            }
            null
        }
    }.getOrNull()

    private fun looksLikeBaseApk(file: File, packageName: String): Boolean {
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return false
        return info.packageName == packageName &&
            info.applicationInfo != null &&
            info.splitNames.isNullOrEmpty()
    }

    private fun isZip(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val first = input.read()
                val second = input.read()
                first == 'P'.code && second == 'K'.code
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun cleanup(directory: File) {
        val threshold = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        directory.listFiles()?.forEach { entry ->
            if (entry.lastModified() >= threshold) return@forEach
            if (entry.isDirectory) {
                entry.deleteRecursively()
            } else {
                entry.delete()
            }
        }
    }

    private companion object {
        const val TAG = "UpdateHub"
        const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        const val DEFAULT_MAX_CONNECTIONS = 4
        const val MAX_PART_RETRIES = 2
        const val PART_RETRY_DELAY_MS = 700L
        // APKPure sits behind Cloudflare. Its bot check lets the desktop
        // Chrome signature used by the metadata checkers through and answers
        // mobile user agents with a "Just a moment..." challenge.
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private data class DownloadProbe(
    val length: Long,
    val acceptRanges: String?
)
