package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class NetworkHelper {
    val cookieJar: CookieJar = DesktopCookieJar.shared

    /** Browser fingerprint associated with a particular clearance-cookie scope. */
    private val cloudflareHeaderBindings = ConcurrentHashMap<String, CloudflareHeaderBinding>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            var request = chain.request()

            if (request.url.host.contains("xnxx.com")) {
                val newUrl = request.url.newBuilder()
                    .host("www.xnxx.es")
                    .build()
                request = request.newBuilder().url(newUrl).build()
                println("[OkHttp Interceptor] Rewriting xnxx.com request to www.xnxx.es: $newUrl")
            }

            headersForHost(request.url.host)?.let { browserHeaders ->
                request = request.newBuilder()
                    .apply {
                        browserHeaders.forEach { (name, value) -> header(name, value) }
                    }
                    .build()
            }

            // Match Android's UserAgentInterceptor: extension requests that use
            // GET without explicit headers must still look like normal app traffic.
            if (request.header("User-Agent").isNullOrEmpty()) {
                request = request.newBuilder()
                    .removeHeader("User-Agent")
                    .addHeader("User-Agent", defaultUserAgentProvider())
                    .build()
            }
            println("[HTTP REQUEST] ${request.method} ${request.url}")
            try {
                var response = chain.proceed(request)
                val bodyString = response.peekBody(1024 * 1024).string()
                val challengeHeader = response.header("Cf-Mitigated")
                    ?.equals("challenge", ignoreCase = true) == true
                val challengeBody = bodyString.contains("Just a moment", ignoreCase = true) ||
                    bodyString.contains("challenge-platform", ignoreCase = true) ||
                    bodyString.contains("cf-chl-", ignoreCase = true)
                val servedByCloudflare = response.header("Server")
                    ?.contains("cloudflare", ignoreCase = true) == true
                val looksLikeCloudflare = response.code in setOf(403, 503) &&
                    (challengeHeader || servedByCloudflare && challengeBody)
                println("[HTTP RESPONSE] ${response.code} ${request.url} (Body Length: ${bodyString.length})")
                if (response.code >= 400 || looksLikeCloudflare) {
                    println("[HTTP DETECTED] Potential challenge or block page. Sneak peek: ${bodyString.take(400)}")
                }

                val requestBody = request.body
                val canReplayRequest = requestBody == null ||
                    (!requestBody.isOneShot() && !requestBody.isDuplex())

                // Android resolves this through CloudflareInterceptor. Desktop
                // opens an isolated Chromium session and retries once with the
                // resulting cookies and browser-bound request headers.
                if (looksLikeCloudflare && canReplayRequest) {
                    val challengeUrl = response.request.url
                    val resolution = DesktopCloudflareHandlerRegistry.resolve(
                        challengeUrl,
                        response.request.headers,
                        cookieJar.loadForRequest(challengeUrl),
                    )
                    if (resolution != null) {
                        // Publish the fingerprint before the cookie. Concurrent
                        // requests can therefore never send a clearance token
                        // with the old User-Agent or Client Hints.
                        rememberResolution(resolution)
                        cookieJar.saveFromResponse(challengeUrl, resolution.cookies)
                        response.close()

                        val retryRequest = request.newBuilder()
                            .header("Cache-Control", "no-cache")
                            .apply {
                                resolution.requestHeaders.forEach { (name, value) -> header(name, value) }
                            }
                            .build()
                        val cookieNames = cookieJar.loadForRequest(challengeUrl)
                            .joinToString { it.name }
                        println("[HTTP RETRY] Sending browser cookies [$cookieNames] to ${challengeUrl.host}")
                        response = chain.proceed(retryRequest)
                        println("[HTTP RETRY] ${response.code} ${response.request.url} after Chromium verification")
                    }
                } else if (looksLikeCloudflare) {
                    println("[CloudflareDesktop] Request body cannot be replayed safely; skipping retry")
                }
                response
            } catch (e: Exception) {
                println("[HTTP ERROR] ${request.url}: ${e.message}")
                throw e
            }
        }
        .build()

    val nonCloudflareClient: OkHttpClient = client

    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun rememberResolution(resolution: DesktopCloudflareResult) {
        val clearance = resolution.cookies.firstOrNull { it.name == "cf_clearance" } ?: return
        val binding = CloudflareHeaderBinding(
            domain = clearance.domain,
            hostOnly = clearance.hostOnly,
            expiresAt = clearance.expiresAt,
            headers = resolution.requestHeaders,
        )
        cloudflareHeaderBindings[binding.key] = binding
    }

    private fun headersForHost(host: String): Map<String, String>? {
        val now = System.currentTimeMillis()
        cloudflareHeaderBindings.entries.removeIf { it.value.expiresAt <= now }
        return cloudflareHeaderBindings.values
            .filter { it.matches(host) }
            .maxByOrNull { it.domain.length }
            ?.headers
    }

    private data class CloudflareHeaderBinding(
        val domain: String,
        val hostOnly: Boolean,
        val expiresAt: Long,
        val headers: Map<String, String>,
    ) {
        val key: String = "$domain\u0000$hostOnly"

        fun matches(host: String): Boolean {
            return if (hostOnly) {
                host == domain
            } else {
                host == domain || host.endsWith(".$domain")
            }
        }
    }
}
