package ru.updatehub.app.data

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IconLoader(private val context: Context) {

    private val lowRam = DeviceProfileDetector.isLowRam(context)

    @Volatile
    private var iconSize = if (lowRam) 96 else 128

    // 160 cached icons at 128 px take roughly 10 MB; low-RAM devices get a
    // smaller cache and 96 px bitmaps, which the 52 dp cards upscale cleanly.
    private val cache = object : LruCache<String, ImageBitmap>(if (lowRam) 48 else 160) {}

    /** Re-applies cache limits when the weak-device switch changes. */
    fun setWeakDevice(enabled: Boolean) {
        val weak = enabled || lowRam
        iconSize = if (weak) 96 else 128
        cache.resize(if (weak) 48 else 160)
        cache.evictAll()
    }

    /** Cache hit without leaving the caller's thread; safe for composition. */
    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(packageName: String): ImageBitmap? {
        // The list is rebuilt when the user comes back from another screen.
        // A cached icon must appear on the first frame, so a hit skips the
        // IO dispatcher entirely instead of flashing a letter placeholder.
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(width = iconSize, height = iconSize).asImageBitmap()
                    .also { cache.put(packageName, it) }
            }.getOrNull()
        }
    }
}
