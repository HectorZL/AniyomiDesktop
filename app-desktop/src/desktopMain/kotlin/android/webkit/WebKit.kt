@file:Suppress("UNUSED_PARAMETER", "unused", "FunctionName")

package android.webkit

/**
 * Stub for android.webkit.ValueCallback<T>.
 * Used by WebView.evaluateJavascript() and similar APIs.
 * Extensions may reference this type at class-loading time even when
 * they don't actually call WebView on desktop.
 */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

/**
 * Stub for android.webkit.WebResourceRequest.
 */
open class WebResourceRequest {
    open val url: android.net.Uri get() = android.net.Uri.EMPTY
    open val isForMainFrame: Boolean get() = false
    open val isRedirect: Boolean get() = false
    open val hasGesture: Boolean get() = false
    open val method: String get() = "GET"
    open val requestHeaders: Map<String, String> get() = emptyMap()
}

/**
 * Stub for android.webkit.WebResourceResponse.
 */
open class WebResourceResponse(
    val mimeType: String?,
    val encoding: String?,
    val data: java.io.InputStream?,
) {
    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String?,
        responseHeaders: Map<String, String>?,
        data: java.io.InputStream?,
    ) : this(mimeType, encoding, data)

    open val statusCode: Int get() = 200
    open val reasonPhrase: String get() = "OK"
    open val responseHeaders: Map<String, String> get() = emptyMap()
}

/**
 * Stub for android.webkit.WebSettings.
 */
open class WebSettings {
    enum class LayoutAlgorithm { NORMAL, SINGLE_COLUMN, NARROW_COLUMNS, TEXT_AUTOSIZING }

    open var javaScriptEnabled: Boolean = false
    open var domStorageEnabled: Boolean = false
    open var allowFileAccess: Boolean = false
    open var allowContentAccess: Boolean = false
    open var loadsImagesAutomatically: Boolean = true
    open var userAgentString: String? = null
    open var useWideViewPort: Boolean = false
    open var loadWithOverviewMode: Boolean = false
    open var builtInZoomControls: Boolean = false
    open var displayZoomControls: Boolean = true
    open var setSupportMultipleWindows: Boolean = false
    open var mixedContentMode: Int = 0
    open var cacheMode: Int = 0
    open var blockNetworkImage: Boolean = false
    open var layoutAlgorithm: LayoutAlgorithm = LayoutAlgorithm.NORMAL
    open var mediaPlaybackRequiresUserGesture: Boolean = true

    open fun setGeolocationEnabled(flag: Boolean) {}
    open fun setRenderPriority(priority: RenderPriority) {}
    open fun setSupportZoom(support: Boolean) {}
    open fun setPluginState(state: PluginState) {}

    enum class RenderPriority { NORMAL, HIGH, LOW }
    enum class PluginState { ON, ON_DEMAND, OFF }
}

/**
 * Stub for android.webkit.WebViewClient.
 */
open class WebViewClient {
    open fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {}
    open fun onPageFinished(view: WebView?, url: String?) {}
    open fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {}
    open fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {}
    open fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
    open fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
    open fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? = null
    open fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? = null
    open fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {}

    companion object {
        const val ERROR_AUTHENTICATION = -4
        const val ERROR_BAD_URL = -12
        const val ERROR_CONNECT = -6
        const val ERROR_FAILED_SSL_HANDSHAKE = -11
        const val ERROR_FILE = -13
        const val ERROR_FILE_NOT_FOUND = -14
        const val ERROR_HOST_LOOKUP = -2
        const val ERROR_IO = -7
        const val ERROR_PROXY_AUTHENTICATION = -5
        const val ERROR_REDIRECT_LOOP = -9
        const val ERROR_TIMEOUT = -8
        const val ERROR_TOO_MANY_REQUESTS = -15
        const val ERROR_UNKNOWN = -1
        const val ERROR_UNSAFE_RESOURCE = -16
        const val ERROR_UNSUPPORTED_AUTH_SCHEME = -3
        const val ERROR_UNSUPPORTED_SCHEME = -10
    }
}

/**
 * Stub for android.webkit.WebResourceError.
 */
open class WebResourceError {
    open val errorCode: Int get() = 0
    open val description: CharSequence get() = ""
}

/**
 * Stub for android.webkit.SslErrorHandler.
 */
open class SslErrorHandler {
    open fun proceed() {}
    open fun cancel() {}
}

/**
 * Stub for android.webkit.WebChromeClient.
 */
open class WebChromeClient {
    open fun onProgressChanged(view: WebView?, newProgress: Int) {}
    open fun onReceivedTitle(view: WebView?, title: String?) {}
    open fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean = false
    open fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean = false
    open fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean = false
}

/**
 * Stub for android.webkit.JsResult.
 */
open class JsResult {
    open fun confirm() {}
    open fun cancel() {}
}

/**
 * Stub for android.webkit.JsPromptResult.
 */
open class JsPromptResult : JsResult() {
    open fun confirm(result: String?) {}
}

/**
 * Real android.webkit.CookieManager backed by the shared java.net.CookieManager
 * that JavaFX WebEngine also uses, so cookies are shared between OkHttp and WebView.
 */
open class CookieManager {
    companion object {
        private val instance = CookieManager()

        @JvmStatic
        fun getInstance(): CookieManager = instance

        @JvmStatic
        fun setAcceptFileSchemeCookies(accept: Boolean) { /* no-op on desktop */ }
    }

    open fun setAcceptCookie(accept: Boolean) { /* always accepted */ }
    open fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) { /* always accepted */ }

    /** Returns cookies for [url] from the shared JFX/java.net cookie store. */
    open fun getCookie(url: String?): String? = url?.let { DesktopWebEngine.getCookiesForUrl(it) }

    /** Sets a cookie manually in the shared store (best-effort). */
    open fun setCookie(url: String?, value: String?) {
        if (url == null || value == null) return
        try {
            val uri = java.net.URI(url)
            val header = mapOf("Set-Cookie" to listOf(value))
            val cm = java.net.CookieHandler.getDefault() as? java.net.CookieManager ?: return
            cm.put(uri, header)
        } catch (_: Exception) {}
    }

    open fun setCookie(url: String?, value: String?, callback: ValueCallback<Boolean>?) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    open fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        DesktopWebEngine.clearAllCookies()
        callback?.onReceiveValue(true)
    }

    open fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        DesktopWebEngine.clearAllCookies()
        callback?.onReceiveValue(true)
    }

    open fun flush() { /* java.net.CookieManager persists in-memory; no flush needed */ }

    open fun hasCookies(): Boolean {
        return try {
            val cm = java.net.CookieHandler.getDefault() as? java.net.CookieManager
            cm?.cookieStore?.cookies?.isNotEmpty() == true
        } catch (_: Exception) { false }
    }
}

/**
 * Stub for android.webkit.WebStorage.
 */
open class WebStorage {
    companion object {
        private val instance = WebStorage()

        @JvmStatic
        fun getInstance(): WebStorage = instance
    }

    open fun deleteAllData() {}
}

/**
 * Real android.webkit.WebView backed by a JavaFX WebEngine (WebKit).
 * Allows extensions to load URLs, execute JavaScript, and receive callbacks
 * exactly as they would on Android — without any visible browser window.
 *
 * Threading:
 *   - All JavaFX operations run on the JFX Application Thread internally.
 *   - Callbacks (onPageFinished, evaluateJavascript result) are delivered
 *     on the JFX Application Thread. Extensions that use coroutines should
 *     ensure they resume on an appropriate dispatcher.
 */
open class WebView(context: android.content.Context? = null) {

    private val engine: DesktopWebEngine = DesktopWebEngine()
    private val settings = WebSettings()

    open var webViewClient: WebViewClient = WebViewClient()
        set(value) {
            field = value
            connectClientCallbacks()
        }

    open var webChromeClient: WebChromeClient? = null

    init {
        connectClientCallbacks()
    }

    private fun connectClientCallbacks() {
        engine.onPageFinished = { url, isError ->
            if (isError) {
                webViewClient.onReceivedError(
                    this,
                    WebViewClient.ERROR_UNKNOWN,
                    "Load failed",
                    url,
                )
            }
            webViewClient.onPageFinished(this, url)
        }
    }

    open fun getSettings(): WebSettings = settings

    open fun loadUrl(url: String) {
        webViewClient.onPageStarted(this, url, null)
        // Forward User-Agent from settings if set
        val ua = settings.userAgentString
        if (!ua.isNullOrEmpty()) engine.setUserAgent(ua)
        engine.load(url)
    }

    open fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        webViewClient.onPageStarted(this, url, null)
        val ua = additionalHttpHeaders["User-Agent"] ?: settings.userAgentString
        if (!ua.isNullOrEmpty()) engine.setUserAgent(ua)
        engine.load(url, additionalHttpHeaders)
    }

    open fun loadData(data: String, mimeType: String?, encoding: String?) {
        engine.loadData(data, mimeType ?: "text/html", encoding ?: "UTF-8")
    }

    open fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        engine.loadData(data, mimeType ?: "text/html", encoding ?: "UTF-8")
    }

    open fun evaluateJavascript(script: String, resultCallback: ValueCallback<String?>?) {
        engine.evaluateJavascript(script, resultCallback)
    }

    open fun reload() {
        val url = engine.currentUrl()
        if (!url.isNullOrEmpty()) loadUrl(url)
    }

    open fun stopLoading() { engine.stopLoading() }

    open fun destroy() {
        engine.stopLoading()
    }

    open fun clearHistory() { /* no history stack on desktop */ }
    open fun clearCache(includeDiskFiles: Boolean) { /* cache is managed by OkHttp */ }

    open fun getUrl(): String? = engine.currentUrl()
    open fun getTitle(): String? = engine.currentTitle()

    open fun canGoBack(): Boolean = false
    open fun goBack() {}
    open fun canGoForward(): Boolean = false
    open fun goForward() {}

    open fun setWebContentsDebuggingEnabled(enabled: Boolean) { /* no-op on desktop */ }

    open fun addJavascriptInterface(obj: Any, name: String) {
        // JavaFX WebEngine supports this via JSObject but requires specific JFX plumbing;
        // extensions that need this may not work fully on desktop.
        println("[DesktopWebView] addJavascriptInterface('$name') called — not fully supported on desktop")
    }

    open fun removeJavascriptInterface(name: String) {}

    open fun onPause() {}
    open fun onResume() {}
    open fun setScrollBarStyle(style: Int) {}
}
