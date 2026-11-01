package ru.updatehub.app.data.install

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import ru.updatehub.app.CurrentActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * APKPure's file host requires a user gesture: a plain navigation (even from
 * Chromium's own network stack) is redirected to the HTML home page, while a
 * real tap on the download button serves the signed data.winudf.com file.
 *
 * The page is loaded in a WebView and its real download button is tapped with
 * a synthetic MotionEvent, which WebView forwards as trusted input. The final
 * data.winudf.com URL is captured through the download listener and downloaded
 * by [ApkDownloader].
 */
class BrowserDownloadResolver {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolve(
        pageUrl: String,
        timeoutMs: Long = 90_000L
    ): String? = withContext(Dispatchers.Main) {
        // A bulk update may start several APKPure downloads at once. Only one
        // helper page may exist at a time: a second one leaks memory and, when
        // it sat on top of the UI, swallowed every touch.
        resolveMutex.withLock {
            resolveLocked(pageUrl, timeoutMs)
        }
    }

    private suspend fun resolveLocked(pageUrl: String, timeoutMs: Long): String? {
        val activity = CurrentActivity.value
        if (activity == null) {
            Log.w(TAG, "Нет активной Activity для WebView")
            return null
        }
        val webView = WebView(activity)
        val released = AtomicBoolean(false)
        return try {
            val metrics = activity.resources.displayMetrics
            // The WebView must be attached to a window: without it the
            // renderer never receives input events, so the synthetic tap is
            // ignored and APKPure sees a non-gesture navigation.
            //
            // It is inserted at index 0, *below* the Compose content. Earlier
            // it was added with activity.addContentView() on top of the whole
            // screen with alpha 0.01: the user still saw the normal UI, but
            // the invisible page intercepted every tap for the 90 seconds the
            // resolve could take, so the app looked frozen during a download.
            val host = activity.findViewById<ViewGroup>(android.R.id.content)
                ?: activity.window.decorView as ViewGroup
            host.addView(
                webView,
                0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // Keep the helper page out of input, focus and accessibility even
            // if a future layout change puts it above the content again.
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.isClickable = false
            webView.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webView.alpha = 0.01f
            webView.setBackgroundColor(Color.TRANSPARENT)

            withTimeoutOrNull<String>(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val finished = AtomicBoolean(false)
                    val clicked = AtomicBoolean(false)

                    fun finish(url: String?) {
                        if (url.isNullOrBlank()) return
                        if (finished.compareAndSet(false, true) && continuation.isActive) {
                            Log.i(TAG, "WebView нашёл файл: $url")
                            continuation.resume(url)
                        }
                    }

                    with(webView.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // The default WebView UA contains the "; wv" marker,
                        // which APKPure uses to block in-app browsers. Use a
                        // matching mobile Chrome UA without the marker.
                        userAgentString = MOBILE_UA
                    }
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                        // Keep Cloudflare/session cookies between downloads.
                        // Clearing them on every app made bulk APKPure updates
                        // repeat the full challenge from scratch.
                        flush()
                    }

                    webView.setDownloadListener { url, _, _, _, _ ->
                        finish(url)
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (released.get()) return
                            if (!url.contains("apkpure.com")) return
                            if (!clicked.compareAndSet(false, true)) return

                            val script = """
                                (function() {
                                  var a = document.querySelector('.download-start-btn') ||
                                          document.getElementById('download_link');
                                  if (!a) return '';
                                  a.scrollIntoView({block: 'center'});
                                  var r = a.getBoundingClientRect();
                                  return JSON.stringify({
                                    x: r.left + r.width / 2,
                                    y: r.top + r.height / 2,
                                    w: r.width,
                                    h: r.height,
                                    title: document.title
                                  });
                                })();
                            """
                            view.evaluateJavascript(script) { result ->
                                if (released.get()) return@evaluateJavascript
                                val rect = runCatching {
                                    JSONObject(result.trim('"').replace("\\\"", "\""))
                                }.getOrNull()
                                val x = rect?.optDouble("x", -1.0) ?: -1.0
                                val y = rect?.optDouble("y", -1.0) ?: -1.0
                                if (x < 0 || y < 0) {
                                    Log.w(TAG, "WebView не нашёл кнопку скачивания")
                                    return@evaluateJavascript
                                }
                                val density = metrics.density
                                val px = (x * density).toFloat()
                                val py = (y * density).toFloat()
                                tap(view, px, py)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val target = request.url.toString()
                            if (isFileUrl(target)) {
                                finish(target)
                                return true
                            }
                            return false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                            if (isFileUrl(url)) {
                                finish(url)
                                return true
                            }
                            return false
                        }
                    }

                    webView.loadUrl(pageUrl)
                    continuation.invokeOnCancellation {
                        webView.stopLoading()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WebView не смог открыть APKPure: ${t.message}", t)
            null
        } finally {
            released.set(true)
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    private fun tap(view: WebView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.postDelayed({
            view.dispatchTouchEvent(up)
        }, 60)
        down.recycle()
        view.postDelayed({ up.recycle() }, 200)
    }

    private fun isFileUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("data.winudf.com") ||
            lower.contains(".xapk") ||
            lower.contains(".apk?") ||
            lower.endsWith(".apk")
    }

    private companion object {
        const val TAG = "UpdateHub"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 9; SM-G955F Build/PPR1.180610.011) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/129.0.6668.100 Mobile Safari/537.36"

        val resolveMutex = Mutex()
    }
}
