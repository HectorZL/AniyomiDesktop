package eu.kanade.tachiyomi.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object VideoProxyServer {
    private var server: HttpServer? = null
    private data class VideoRegistration(val url: String, val headers: Headers)
    private val urlMap = ConcurrentHashMap<String, VideoRegistration>()
    private var cookieJar: CookieJar = CookieJar.NO_COOKIES

    // Tracks the last valid external Referer seen for each CDN host.
    // When the extension fetches master.m3u8 with the correct embed-page Referer,
    // we record it here so the proxy can reuse it for sub-playlist / segment requests.
    private val cdnRefererTracker = ConcurrentHashMap<String, String>() // host → referer

    private val cdnDomainsToTrack = listOf("cloudwindow-route.com", "voe.sx")

    /** Returns true if this host belongs to a tracked CDN. */
    private fun isCdnHost(host: String) = cdnDomainsToTrack.any { host.endsWith(it) }

    /** Interceptor added to the shared extension OkHttpClient to sniff Referers. */
    private val refererTrackingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val referer = request.header("Referer")
        // Record only when the Referer is external (not from the same CDN)
        if (isCdnHost(host) && referer != null) {
            val refererHost = referer.toHttpUrlOrNull()?.host ?: ""
            if (refererHost != host) {              // external → valid embed-page referer
                cdnRefererTracker[host] = referer
                println("[ProxyServer] Tracked CDN Referer: $host → $referer")
            }
        }
        chain.proceed(request)
    }

    fun setCookieJar(newCookieJar: CookieJar) {
        cookieJar = newCookieJar
        client = client.newBuilder().cookieJar(newCookieJar).build()
    }

    private val headerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // Enforce exact order and casing
        val orderedHeaders = listOf(
            "Host" to originalRequest.url.host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive"
        )

        // Remove enforced headers to re-add in order
        orderedHeaders.forEach { (name, _) -> builder.removeHeader(name) }

        // Add enforced headers in order
        orderedHeaders.forEach { (name, value) -> builder.addHeader(name, value) }

        // Add remaining headers
        val originalHeaders = originalRequest.headers
        for (i in 0 until originalHeaders.size) {
            val name = originalHeaders.name(i)
            val value = originalHeaders.value(i)
            if (orderedHeaders.none { it.first.equals(name, ignoreCase = true) }) {
                builder.addHeader(name, value)
            }
        }

        chain.proceed(builder.build())
    }

    private var client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(headerInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val latency = System.currentTimeMillis() - startTime
            val size = response.body?.contentLength() ?: -1L
            println("[ProxyServer] URL: ${request.url}, Status: ${response.code}, Latency: ${latency}ms, Size: ${size} bytes")
            response
        }
        .build()

    fun setClient(newClient: OkHttpClient) {
        // Wrap the shared extension client with our Referer tracker
        client = newClient.newBuilder()
            .addNetworkInterceptor(refererTrackingInterceptor)
            .build()
    }

    fun start(): Int {
        val currentServer = server
        if (currentServer != null) return currentServer.address.port
        
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/play", ProxyHandler())
        s.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ProxyServer-Worker").apply { isDaemon = true }
        }
        s.start()
        server = s
        println("[ProxyServer] Starting on port ${s.address.port}")
        return s.address.port
    }

    fun registerVideo(url: String, headers: Headers): String {
        val id = UUID.randomUUID().toString()
        urlMap[id] = VideoRegistration(url, headers)
        val port = start()
        
        val httpUrl = url.toHttpUrlOrNull()
        val fileName = httpUrl?.pathSegments?.lastOrNull() ?: "video.mp4"
        val proxiedUrl = "http://127.0.0.1:$port/play/$id/$fileName"
        println("[ProxyServer] Registering: $url")
        println("[ProxyServer] Proxied URL: $proxiedUrl")
        // Log custom headers supplied by the extension
        for (i in 0 until headers.size) {
            println("[ProxyServer] Custom header from extension: ${headers.name(i)} = ${headers.value(i)}")
        }
        return proxiedUrl
    }

    private class ProxyHandler : HttpHandler {
            private fun modifyHlsPlaylist(playlist: String, uuid: String, baseUrl: String): String {
                val baseHttpUrl = baseUrl.toHttpUrlOrNull() ?: return playlist
                val port = server?.address?.port ?: return playlist
                return playlist.lines().joinToString("\n") { line ->
                    if (line.isBlank() || line.startsWith("#")) {
                        line
                    } else {
                        val segmentUrl = baseHttpUrl.resolve(line)?.toString() ?: line
                        "http://127.0.0.1:$port/play/$uuid/${URLEncoder.encode(segmentUrl, StandardCharsets.UTF_8.toString())}"
                    }
                }
            }

            override fun handle(exchange: HttpExchange) {
                val path = exchange.requestURI.path
                val query = exchange.requestURI.rawQuery
                
                val playPath = path.substringAfter("/play/", "")
                if (playPath.isEmpty()) {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.close()
                    return
                }

                val parts = playPath.split('/', limit = 2)
                val uuid = parts[0]
                val relativePath = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString()) else ""

                val target = urlMap[uuid]
                if (target == null) {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.close()
                    return
                }

                val realUrl = target.url
                val customHeaders = target.headers
                val baseHttpUrl = realUrl.toHttpUrlOrNull()
                if (baseHttpUrl == null) {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                    return
                }

                // Determine the actual CDN URL to request.
                // When the player requests /play/UUID/filename.m3u8, relativePath = "filename.m3u8".
                // baseHttpUrl.resolve("filename.m3u8") would STRIP query params (the CDN token).
                // Fix: if relativePath is exactly the registered filename (not an HLS sub-resource),
                // use baseHttpUrl directly to preserve the full URL including the token.
                val registeredFilename = baseHttpUrl.pathSegments.lastOrNull() ?: ""
                val targetHttpUrl = when {
                    relativePath.isEmpty() -> baseHttpUrl
                    relativePath == registeredFilename -> baseHttpUrl
                    relativePath.startsWith("http") -> relativePath.toHttpUrlOrNull()
                    else -> {
                        val relativeWithQuery = if (query != null) "$relativePath?$query" else relativePath
                        baseHttpUrl.resolve(relativeWithQuery)
                    }
                }

                if (targetHttpUrl == null) {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                    return
                }

                val resolvedUrl = targetHttpUrl.toString()
                println("[ProxyServer] Proxying request to resolved URL: $resolvedUrl")

                val client = client
                val requestBuilder = Request.Builder().url(resolvedUrl)

                // Copy request headers from player
                val headersToSkip = setOf("Host", "Accept-Encoding", "Connection", "User-Agent", "If-None-Match", "If-Modified-Since")
                exchange.requestHeaders.getFirst("Range")?.let { println("[ProxyServer] Range header: $it") }
                
                // Explicitly log and copy Referer and Origin if they exist
                exchange.requestHeaders.getFirst("Referer")?.let { 
                    println("[ProxyServer] Copying Referer: $it")
                    requestBuilder.header("Referer", it)
                }
                exchange.requestHeaders.getFirst("Origin")?.let { 
                    println("[ProxyServer] Copying Origin: $it")
                    requestBuilder.header("Origin", it)
                }

                // Override User-Agent
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                requestBuilder.header("User-Agent", userAgent)
                println("[ProxyServer] Final User-Agent: $userAgent")

                for ((key, values) in exchange.requestHeaders) {
                    if (headersToSkip.any { it.equals(key, ignoreCase = true) }) continue
                    if (key.equals("Referer", ignoreCase = true) || key.equals("Origin", ignoreCase = true)) continue
                    
                    if (key.equals("Cookie", ignoreCase = true)) {
                        println("[ProxyServer] Sending Cookie: $values")
                    }
                    
                    println("[ProxyServer] Sending Header: $key = $values")
                    for (value in values) {
                        requestBuilder.addHeader(key, value)
                    }
                }

                // Apply non-Referer/Origin custom headers from extension (e.g. Accept, User-Agent overrides)
                for (i in 0 until customHeaders.size) {
                    val name = customHeaders.name(i)
                    if (!name.equals("Referer", ignoreCase = true) && !name.equals("Origin", ignoreCase = true)) {
                        requestBuilder.header(name, customHeaders.value(i))
                    }
                }

                // ── Referer resolution (priority order) ─────────────────────────
                // 1. Tracked Referer: sniffed from extension's own successful CDN requests
                // 2. Player Referer: sent by the media player (if external)
                // 3. Custom Referer from extension (only if it's NOT a self-Referer)
                // 4. CDN-specific fallback rules
                // 5. Origin fallback
                val resolvedHttpUrl = resolvedUrl.toHttpUrlOrNull()
                val resolvedHost = resolvedHttpUrl?.host ?: ""
                val customReferer = customHeaders.get("Referer")
                val customRefererHost = customReferer?.toHttpUrlOrNull()?.host ?: ""
                val isSelfReferer = customReferer != null &&
                    resolvedHost.isNotEmpty() &&
                    customRefererHost == resolvedHost

                // Priority 1: use the Referer we tracked from extension's network traffic
                val trackedReferer = if (isCdnHost(resolvedHost)) cdnRefererTracker[resolvedHost] else null

                // Player's own Referer (only external ones)
                val playerRefererRaw = exchange.requestHeaders.getFirst("Referer")
                val playerRefererHost = playerRefererRaw?.toHttpUrlOrNull()?.host ?: ""
                val playerReferer = if (playerRefererRaw != null && playerRefererHost != resolvedHost) playerRefererRaw else null

                when {
                    trackedReferer != null -> {
                        requestBuilder.header("Referer", trackedReferer)
                        println("[ProxyServer] Using tracked Referer for $resolvedHost: $trackedReferer")
                    }
                    playerReferer != null -> {
                        requestBuilder.header("Referer", playerReferer)
                        println("[ProxyServer] Using player Referer: $playerReferer")
                    }
                    customReferer != null && !isSelfReferer -> {
                        requestBuilder.header("Referer", customReferer)
                        println("[ProxyServer] Using extension Referer: $customReferer")
                    }
                    isCdnHost(resolvedHost) -> {
                        // Known CDN without a tracked Referer yet — fallback rules
                        val cdnRefererRules = mapOf(
                            "cloudwindow-route.com" to "https://voe.sx/",
                            "voe.sx"               to "https://voe.sx/"
                        )
                        val cdnReferer = cdnRefererRules.entries
                            .firstOrNull { (domain, _) -> resolvedHost.endsWith(domain) }?.value
                        if (cdnReferer != null) {
                            requestBuilder.header("Referer", cdnReferer)
                            println("[ProxyServer] CDN fallback Referer for $resolvedHost: $cdnReferer")
                        }
                    }
                    else -> {
                        val originUrl = realUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: realUrl
                        requestBuilder.header("Referer", "$originUrl/")
                        println("[ProxyServer] Origin fallback Referer: $originUrl/")
                    }

                } // end when (Referer resolution)

                // Derive Origin from Referer if not already set
                val builtRequest = requestBuilder.build()
                val currentReferer = builtRequest.header("Referer")
                val currentOrigin = builtRequest.header("Origin")
                if (currentOrigin == null && currentReferer != null) {
                    val refererOrigin = currentReferer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                    if (refererOrigin != null) {
                        requestBuilder.header("Origin", refererOrigin)
                    }
                }

                val request = requestBuilder.build()
                println("[ProxyServer] Sending Referer: ${request.header("Referer")}")
                println("[ProxyServer] Sending Origin: ${request.header("Origin")}")

                val call = client.newCall(request)
                try {
                    val startTime = System.currentTimeMillis()
                    val response = call.execute()
                    val latency = System.currentTimeMillis() - startTime

                    if (resolvedUrl.endsWith(".vtt", ignoreCase = true) || resolvedUrl.endsWith(".srt", ignoreCase = true)) {
                        println("[ProxyServer] Subtitle Request: $resolvedUrl, Status: ${response.code}, Latency: ${latency}ms")
                    }
                    
                    // Copy response headers
                    val responseHeaders = exchange.responseHeaders
                    for (i in 0 until response.headers.size) {
                        val name = response.headers.name(i)
                        val value = response.headers.value(i)
                        if (name.equals("Set-Cookie", ignoreCase = true)) {
                            println("[ProxyServer] Received Set-Cookie: $value")
                        }
                        if (name.equals("Transfer-Encoding", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
                        responseHeaders.add(name, value)
                    }

                    val statusCode = response.code
                    val responseBody = response.body
                    
                    val isHls = response.header("Content-Type")?.contains("application/vnd.apple.mpegurl", ignoreCase = true) == true ||
                                 resolvedUrl.endsWith(".m3u8", ignoreCase = true)

                    if (isHls && responseBody != null) {
                        val bodyString = responseBody.string()
                        val modifiedBody = modifyHlsPlaylist(bodyString, uuid, realUrl)
                        println("[ProxyServer] Modified HLS Playlist (first 5 lines):\n${modifiedBody.lines().take(5).joinToString("\n")}")
                        val bytes = modifiedBody.toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                        exchange.responseBody.close()
                    } else {
                        val contentLength = responseBody?.contentLength() ?: -1L
                        if (contentLength > 0) {
                            exchange.sendResponseHeaders(statusCode, contentLength)
                        } else {
                            exchange.sendResponseHeaders(statusCode, 0)
                        }

                        if (responseBody != null) {
                            responseBody.byteStream().use { input ->
                                exchange.responseBody.use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        } else {
                            exchange.responseBody.close()
                        }
                    }
                    response.close()
                } catch (e: java.io.IOException) {
                    call.cancel()
                    println("[ProxyServer] IOException proxying request: ${e.message}")
                    // Gracefully handle client disconnection or interruption
                } catch (e: Exception) {
                    call.cancel()
                    println("[ProxyServer] Error proxying request: ${e.message}")
                    try {
                        exchange.sendResponseHeaders(500, 0)
                        exchange.close()
                    } catch (ignored: Exception) {}
                }
            }

    }
}
