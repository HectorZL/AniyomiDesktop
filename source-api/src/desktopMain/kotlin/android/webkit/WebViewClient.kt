package android.webkit

import android.graphics.Bitmap

open class WebViewClient {
    open fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: Bitmap?) {}
    open fun onPageFinished(view: android.webkit.WebView?, url: String?) {}
    open fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean = false
    open fun onReceivedError(view: android.webkit.WebView?, errorCode: Int, description: String?, failingUrl: String?) {}
    open fun onReceivedSslError(view: android.webkit.WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {}
}

open class WebView {
    open fun loadUrl(url: String?) {}
    open fun loadDataWithBaseURL(baseUrl: String?, data: String?, mimeType: String?, encoding: String?, historyUrl: String?) {}
    open fun setWebViewClient(client: WebViewClient?) {}
    open fun settings(): WebSettings = WebSettings()
}

open class WebSettings {
    open fun setJavaScriptEnabled(enabled: Boolean) {}
    open fun setDomStorageEnabled(enabled: Boolean) {}
    open fun setAllowFileAccess(allow: Boolean) {}
}

open class SslErrorHandler {
    open fun proceed() {}
    open fun cancel() {}
}
