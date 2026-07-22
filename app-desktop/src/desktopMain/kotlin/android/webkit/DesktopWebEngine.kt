@file:Suppress("UNUSED_PARAMETER", "unused")

package android.webkit

import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DesktopWebEngine — bridge between the Android android.webkit.WebView API
 * and a real JavaFX WebEngine (WebKit-based) running in an invisible JFX thread.
 *
 * Lifecycle:
 *   1. Call [initPlatform] once at app startup (from main.kt) to boot the JFX thread.
 *   2. Each android.webkit.WebView instance creates one DesktopWebEngine.
 *   3. Operations are dispatched to the JFX Application Thread; callers can
 *      use [awaitLoad] to suspend until page load completes.
 */
internal class DesktopWebEngine {

    private val engineRef = AtomicReference<WebEngine?>()
    private val initLatch = CountDownLatch(1)

    /** Fired (on JFX thread) when the page finishes loading successfully or with error. */
    var onPageFinished: ((url: String, isError: Boolean) -> Unit)? = null

    /** Fired (on JFX thread) for every sub-resource request. Return non-null to block/replace. */
    var shouldInterceptRequest: ((url: String) -> WebResourceResponse?)? = null

    private val loadLatch = AtomicReference<CountDownLatch?>(null)

    init {
        ensurePlatformStarted()
        Platform.runLater {
            val engine = WebEngine()
            engine.isJavaScriptEnabled = true

            // Suppress alert/confirm dialogs that would block the JFX thread
            engine.setOnAlert { event ->
                println("[DesktopWebEngine] JS alert: ${event.data}")
            }
            engine.setConfirmHandler { _ -> true }
            engine.setPromptHandler { _ -> "" }

            // Listen for load state changes
            engine.loadWorker.stateProperty().addListener(ChangeListener { _, _, newState ->
                when (newState) {
                    Worker.State.SUCCEEDED -> {
                        val url = engine.location ?: ""
                        println("[DesktopWebEngine] Page loaded: $url")
                        onPageFinished?.invoke(url, false)
                        loadLatch.getAndSet(null)?.countDown()
                    }
                    Worker.State.FAILED, Worker.State.CANCELLED -> {
                        val url = engine.location ?: ""
                        println("[DesktopWebEngine] Page load failed: $url — ${engine.loadWorker.exception?.message}")
                        onPageFinished?.invoke(url, true)
                        loadLatch.getAndSet(null)?.countDown()
                    }
                    else -> {}
                }
            })

            engineRef.set(engine)
            initLatch.countDown()
        }
        // Wait up to 10 s for JFX to initialise the engine
        initLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun engine(): WebEngine = engineRef.get()
        ?: error("[DesktopWebEngine] WebEngine not initialised yet")

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Load a URL. Does NOT block; returns immediately.
     * Use [awaitLoad] to wait for page-finished.
     */
    fun load(url: String, headers: Map<String, String> = emptyMap()) {
        val latch = CountDownLatch(1)
        loadLatch.set(latch)
        Platform.runLater {
            try {
                if (headers.isEmpty()) {
                    engine().load(url)
                } else {
                    // JavaFX WebEngine doesn't support per-request headers directly;
                    // we set User-Agent via the engine's userAgent property, and for
                    // other headers we fall back to plain load.
                    headers["User-Agent"]?.let { engine().userAgent = it }
                    engine().load(url)
                }
            } catch (e: Exception) {
                println("[DesktopWebEngine] load() error: ${e.message}")
                loadLatch.getAndSet(null)?.countDown()
            }
        }
    }

    /**
     * Blocks the calling thread until the current page load finishes,
     * or until [timeoutMs] ms elapse.
     */
    fun awaitLoad(timeoutMs: Long = 30_000L) {
        loadLatch.get()?.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * Execute [script] on the JFX thread and deliver the result (as String?)
     * to [callback] on the same JFX thread.
     *
     * This maps to Android's WebView.evaluateJavascript(script, ValueCallback).
     */
    fun evaluateJavascript(script: String, callback: ValueCallback<String?>?) {
        Platform.runLater {
            try {
                val result = engine().executeScript(script)
                val str = when (result) {
                    null -> "null"
                    is String -> "\"$result\""
                    else -> result.toString()
                }
                callback?.onReceiveValue(str)
            } catch (e: Exception) {
                println("[DesktopWebEngine] evaluateJavascript error: ${e.message}")
                callback?.onReceiveValue(null)
            }
        }
    }

    /** Returns the current page URL, or null. */
    fun currentUrl(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        Platform.runLater {
            ref.set(engineRef.get()?.location)
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ref.get()
    }

    /** Returns the current page title, or null. */
    fun currentTitle(): String? {
        val ref = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        Platform.runLater {
            ref.set(engineRef.get()?.title)
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ref.get()
    }

    /** Injects CSS to hide UI noise during scraping. No-op if called before page load. */
    fun injectCss(css: String) {
        Platform.runLater {
            try {
                val escaped = css.replace("'", "\\'").replace("\n", " ")
                engine().executeScript(
                    """
                    var s=document.createElement('style');
                    s.innerHTML='$escaped';
                    document.head.appendChild(s);
                    """.trimIndent()
                )
            } catch (_: Exception) {}
        }
    }

    /** Load raw HTML content with an optional base URL. */
    fun loadData(html: String, mimeType: String = "text/html", encoding: String = "UTF-8", baseUrl: String = "") {
        val latch = CountDownLatch(1)
        loadLatch.set(latch)
        Platform.runLater {
            try {
                engine().loadContent(html, mimeType)
            } catch (e: Exception) {
                println("[DesktopWebEngine] loadData() error: ${e.message}")
                loadLatch.getAndSet(null)?.countDown()
            }
        }
    }

    /** Stop any ongoing load. */
    fun stopLoading() {
        Platform.runLater {
            try { engine().loadWorker.cancel() } catch (_: Exception) {}
        }
    }

    /** Set the userAgent string on the underlying engine. */
    fun setUserAgent(ua: String) {
        Platform.runLater {
            try { engine().userAgent = ua } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------------------
    // Companion — Platform singleton initialisation
    // -------------------------------------------------------------------------

    companion object {
        private val platformStarted = AtomicBoolean(false)
        private val platformLatch = CountDownLatch(1)
        private val defaultCookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

        /**
         * Boot the JavaFX Platform. Must be called once before any WebEngine is created.
         * Safe to call multiple times; subsequent calls are no-ops.
         */
        fun initPlatform() {
            if (platformStarted.compareAndSet(false, true)) {
                // Install a system-wide CookieManager so JavaFX WebEngine cookies
                // are shared with OkHttp via java.net.HttpURLConnection/CookieHandler.
                CookieHandler.setDefault(defaultCookieManager)

                try {
                    // JavaFX 9+ API: start the toolkit without a visible stage
                    Platform.startup {
                        println("[DesktopWebEngine] JavaFX Platform started.")
                        platformLatch.countDown()
                    }
                } catch (e: IllegalStateException) {
                    // Already started (e.g. in tests or second call)
                    println("[DesktopWebEngine] JavaFX Platform already running.")
                    platformLatch.countDown()
                }

                // Keep JFX alive when all windows are closed (needed for headless mode)
                Platform.setImplicitExit(false)
            }
            // Block until the platform is ready
            platformLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /**
         * Retrieves all cookies for [url] from the shared JavaFX/java.net cookie store.
         * Returns a "name=value; name2=value2" string, same as Android CookieManager.getCookie().
         */
        fun getCookiesForUrl(url: String): String? {
            return try {
                val uri = java.net.URI(url)
                val cookies = defaultCookieManager.cookieStore.get(uri)
                if (cookies.isEmpty()) null
                else cookies.joinToString("; ") { "${it.name}=${it.value}" }
            } catch (_: Exception) { null }
        }

        /** Remove all cookies from the shared store. */
        fun clearAllCookies() {
            try { defaultCookieManager.cookieStore.removeAll() } catch (_: Exception) {}
        }

        private fun ensurePlatformStarted() {
            if (!platformStarted.get()) {
                initPlatform()
            } else {
                platformLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
}
