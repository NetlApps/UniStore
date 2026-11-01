package ru.updatehub.app.data

import java.math.BigInteger

/**
 * Compares version names such as "2.26.41.77" and "8.201".
 *
 * Only numeric parts are significant. Stores append channel/build suffixes
 * ("28.0.0.9905-gms" vs "28.0.0.9905-rustore", "6.2.90-G") and use letter
 * prefixes ("R-26.09.01.00", "v2.3.4"); those must not create fake updates.
 * Missing numeric parts are treated as zero, so "1.0" equals "1.0.0".
 */
object VersionComparator : Comparator<String> {

    private val numberRegex = Regex("""\d+""")

    override fun compare(a: String, b: String): Int {
        val left = numbers(a)
        val right = numbers(b)

        if (left.isEmpty() && right.isEmpty()) {
            return a.trim().compareTo(b.trim(), ignoreCase = true)
        }
        if (left.isEmpty()) return -1
        if (right.isEmpty()) return 1

        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrNull(i) ?: BigInteger.ZERO
            val y = right.getOrNull(i) ?: BigInteger.ZERO
            val result = x.compareTo(y)
            if (result != 0) return result
        }
        return 0
    }

    private fun numbers(value: String): List<BigInteger> =
        numberRegex.findAll(value)
            .map { BigInteger(it.value) }
            .toList()

    fun isNewer(remote: String, installed: String): Boolean = compare(remote, installed) > 0
}
