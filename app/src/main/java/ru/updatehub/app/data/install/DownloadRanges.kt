package ru.updatehub.app.data.install

/** Inclusive byte range used for HTTP Range requests. */
internal data class ByteRange(
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = endInclusive - start + 1
}

/** Parsed value of the Content-Range response header. */
internal data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long?
) {
    val range: ByteRange get() = ByteRange(start, endInclusive)
}

/**
 * Splits a file into contiguous ranges for parallel downloads. Small files
 * stay on one connection: the extra round trips would cost more than they
 * save. The last ranges absorb the remainder, so the ranges always cover the
 * whole file without gaps or overlaps.
 */
internal fun splitIntoRanges(
    total: Long,
    maxParts: Int,
    minPartBytes: Long = MIN_PARALLEL_PART_BYTES
): List<ByteRange> {
    if (total <= 0 || maxParts <= 1 || minPartBytes <= 0) return emptyList()
    val parts = minOf(maxParts, (total / minPartBytes).toInt().coerceAtLeast(1))
    if (parts <= 1) return emptyList()

    val base = total / parts
    val remainder = total % parts
    val ranges = ArrayList<ByteRange>(parts)
    var start = 0L
    for (index in 0 until parts) {
        val length = base + if (index < remainder) 1L else 0L
        val end = start + length - 1
        ranges += ByteRange(start, end)
        start = end + 1
    }
    return ranges
}

/**
 * Parses `Content-Range: bytes 0-1023/12345`. The total can be `*` when the
 * server does not disclose the full size.
 */
internal fun parseContentRange(value: String?): ContentRange? {
    val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    val total = match.groupValues[3]
        .takeUnless { it == "*" }
        ?.toLongOrNull()
        ?.takeIf { it > end }
    return ContentRange(start, end, total)
}

internal fun supportsRangeDownload(
    length: Long?,
    acceptRanges: String?,
    maxParts: Int
): Boolean {
    if (length == null || length <= 0) return false
    if (acceptRanges?.contains("bytes", ignoreCase = true) != true) return false
    return splitIntoRanges(length, maxParts).isNotEmpty()
}

internal const val MIN_PARALLEL_PART_BYTES = 4L * 1024 * 1024

private val CONTENT_RANGE = Regex(
    """bytes\s+(\d+)-(\d+)/(\d+|\*)""",
    RegexOption.IGNORE_CASE
)
