package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl

/**
 * Resolves a Cloudflare challenge using a real desktop browser engine.
 *
 * The handler is deliberately supplied by the desktop application because the
 * source-api module must not depend on JavaFX or a particular WebView backend.
 * It may execute the site's own JavaScript challenge, but it does not solve
 * CAPTCHAs or forge Cloudflare cookies.
 */
data class DesktopCloudflareResult(
    /** Cookies obtained only from the isolated challenge profile. */
    val cookies: List<Cookie>,
    /** Browser headers that must accompany the clearance cookie. */
    val requestHeaders: Map<String, String>,
)

fun interface DesktopCloudflareHandler {
    fun resolve(
        url: HttpUrl,
        headers: Headers,
        oldCookies: List<Cookie>,
    ): DesktopCloudflareResult?
}

/** Process-wide registration point for the desktop HTTP client. */
object DesktopCloudflareHandlerRegistry {
    @Volatile
    private var handler: DesktopCloudflareHandler? = null

    fun install(handler: DesktopCloudflareHandler?) {
        this.handler = handler
    }

    fun resolve(
        url: HttpUrl,
        headers: Headers,
        oldCookies: List<Cookie>,
    ): DesktopCloudflareResult? {
        return handler?.resolve(url, headers, oldCookies)
    }
}
