package ru.updatehub.app.data

import android.content.Context
import android.os.Build
import ru.updatehub.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class FeedbackCategory(val title: String, val issueLabel: String) {
    BUG("Ошибка", "bug"),
    INSTABILITY("Нестабильная работа", "bug"),
    IDEA("Пожелание", "enhancement"),
    OTHER("Другое", "question")
}

/**
 * A small in-memory breadcrumb log. It records what the user did before an
 * instability instead of asking them to reproduce it. The ring never grows
 * past [MAX_ENTRIES]; formatting happens only when a report is built.
 */
object DiagnosticsLog {

    private const val MAX_ENTRIES = 250
    private const val CRASH_FILE = "last-crash.txt"
    private const val MAX_CRASH_CHARS = 12_000

    private val entries = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val fileFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @Volatile
    private var installed = false

    fun record(tag: String, message: String) {
        val line = "${formatTime(Date())} [$tag] ${message.take(300)}"
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(line)
        }
        android.util.Log.d("UniStoreDiag", line)
    }

    fun snapshot(): String = synchronized(entries) {
        if (entries.isEmpty()) {
            "Действия не записаны"
        } else {
            entries.joinToString("\n")
        }
    }

    fun installCrashHandler(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = buildCrashReport(appContext, thread, throwable)
                File(appContext.filesDir, CRASH_FILE)
                    .writeText(report, Charsets.UTF_8)
            }
            android.util.Log.e("UniStoreDiag", "Необработанное исключение", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readCrashReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        return runCatching {
            file.readText(Charsets.UTF_8).take(MAX_CRASH_CHARS)
        }.getOrNull()
    }

    fun clearCrashReport(context: Context) {
        runCatching { File(context.applicationContext.filesDir, CRASH_FILE).delete() }
    }

    fun buildReport(
        context: Context,
        category: FeedbackCategory,
        description: String
    ): String = buildString {
        appendLine("# Сообщение из UniStore")
        appendLine()
        appendLine("**Категория:** ${category.title}")
        appendLine()
        appendLine("## Что произошло")
        appendLine()
        appendLine(description.trim().ifBlank { "Пользователь не добавил описание." })
        appendLine()
        appendLine("## Устройство")
        appendLine()
        appendLine("- Модель: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("- ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine()
        appendLine("## Приложение")
        appendLine()
        appendLine("- UniStore ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("- Время отчёта: ${formatFileTime(Date())}")
        val crash = readCrashReport(context)
        if (crash != null) {
            appendLine()
            appendLine("## Последняя ошибка")
            appendLine()
            appendLine("```")
            appendLine(crash.take(MAX_CRASH_CHARS))
            appendLine("```")
        }
        appendLine()
        appendLine("## Последние действия")
        appendLine()
        appendLine("```")
        appendLine(snapshot())
        appendLine("```")
    }

    /**
     * Prefilled GitHub issue. GitHub rejects request lines above roughly
     * 8 KB, and one Cyrillic character becomes six URL characters, so a long
     * Russian report used to end with HTTP 414. The body is measured after
     * encoding and trimmed to fit; the full report is copied to the clipboard
     * by the feedback screen before this URL is opened.
     */
    fun githubIssueUrl(category: FeedbackCategory, report: String): String {
        val title = "[${BuildConfig.VERSION_NAME}] ${category.title}"
        val prefix = "https://github.com/NetlApps/UniStore/issues/new" +
            "?title=${encode(title)}&body="
        val suffix = "&labels=${encode(category.issueLabel)}"
        val bodyBudget = (MAX_ISSUE_URL_LENGTH - prefix.length - suffix.length)
            .coerceAtLeast(512)
        val marker = "\n\n…Отчёт сокращён, чтобы поместиться в ссылку. " +
            "Полная версия скопирована кнопкой «Открыть на GitHub»."
        val body = fitForUrl(report, bodyBudget, marker)
        return prefix + encode(body) + suffix
    }

    /**
     * Keeps the beginning (description and device data) and the end (latest
     * actions) of a long report. The middle is cut because the newest
     * breadcrumbs are usually more useful for reproducing a failure.
     */
    private fun fitForUrl(text: String, budget: Int, marker: String): String {
        if (encode(text).length <= budget) return text
        val available = budget - encode(marker).length
        if (available <= 0) return marker.trim()

        var low = 0
        var high = text.length
        var best = ""
        while (low <= high) {
            val keep = (low + high) / 2
            val headCount = keep * 3 / 5
            val tailCount = keep - headCount
            val candidate = safeHead(text, headCount) + marker + safeTail(text, tailCount)
            if (encode(candidate).length <= available) {
                best = candidate
                low = keep + 1
            } else {
                high = keep - 1
            }
        }
        return best.ifBlank { marker.trim() }
    }

    private fun safeHead(text: String, count: Int): String {
        if (count <= 0) return ""
        if (count >= text.length) return text
        var end = count
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    private fun safeTail(text: String, count: Int): String {
        if (count <= 0) return ""
        if (count >= text.length) return text
        var start = text.length - count
        if (Character.isLowSurrogate(text[start])) start++
        return text.substring(start)
    }

    /** GitHub starts returning 414 at about 8 KB; keep a safety margin. */
    private const val MAX_ISSUE_URL_LENGTH = 6_800

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun formatTime(date: Date): String =
        synchronized(timeFormat) { timeFormat.format(date) }

    private fun formatFileTime(date: Date): String =
        synchronized(fileFormat) { fileFormat.format(date) }

    private fun buildCrashReport(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): String = buildString {
        appendLine("UniStore ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Время: ${formatFileTime(Date())}")
        appendLine("Поток: ${thread.name}")
        appendLine()
        val stack = StringWriter()
        throwable.printStackTrace(PrintWriter(stack))
        append(stack.toString())
        appendLine()
        appendLine("--- Последние действия ---")
        appendLine(snapshot())
    }
}
