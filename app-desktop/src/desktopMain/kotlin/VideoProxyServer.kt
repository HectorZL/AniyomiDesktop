package eu.kanade.tachiyomi.network

import fi.iki.elonen.NanoHTTPD
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object VideoProxyServer {
    private var server: ProxyServer? = null
    private data class VideoRegistration(val url: String, val headers: Headers)
    private val urlMap = ConcurrentHashMap<String, VideoRegistration>()
    private var cookieJar: CookieJar = CookieJar.NO_COOKIES

    // Tracks the last valid external Referer seen for each CDN host.
    // When the extension fetches master.m3u8 with the correct embed-page Referer,
    // we record it here so the proxy can reuse it for sub-playlist / segment requests.
    private val cdnRefererTracker = ConcurrentHashMap<String, String>()
    private val cdnDomainsToTrack = listOf("cloudwindow-route.com", "voe.sx")

    /** Returns true if this host belongs to a tracked CDN. */
    private fun isCdnHost(host: String) = cdnDomainsToTrack.any { host.endsWith(it) }

    /** Interceptor added to the shared extension OkHttpClient to sniff Referers. */
    private val refererTrackingInterceptor = Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        val referer = request.header("Referer")
        if (isCdnHost(host) && referer != null) {
            val refererHost = referer.toHttpUrlOrNull()?.host ?: ""
            if (refererHost != host) {
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
        val orderedHeaders = listOf(
            "Host" to originalRequest.url.host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive",
        )
        orderedHeaders.forEach { (name, _) -> builder.removeHeader(name) }
        orderedHeaders.forEach { (name, value) -> builder.addHeader(name, value) }
        chain.proceed(builder.build())
    }

    private var client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .addNetworkInterceptor(headerInterceptor)
        .cookieJar(cookieJar)
        .build()

    fun setClient(newClient: OkHttpClient) {
        client = newClient.newBuilder()
            .addNetworkInterceptor(refererTrackingInterceptor)
            .build()
    }

    fun start(): Int {
        val currentServer = server
        if (currentServer != null) return currentServer.listeningPort

        return ProxyServer().also {
            it.start()
            server = it
            println("[ProxyServer] Starting on port ${it.listeningPort}")
        }.listeningPort
    }

    fun registerVideo(url: String, headers: Headers): String {
        val id = UUID.randomUUID().toString()
        urlMap[id] = VideoRegistration(url, headers)
        val port = start()
        val fileName = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: "video.mp4"
        val proxiedUrl = "http://127.0.0.1:$port/play/$id/$fileName"
        println("[ProxyServer] Registering: $url")
        println("[ProxyServer] Proxied URL: $proxiedUrl")
        return proxiedUrl
    }

    private class ProxyServer : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = VideoProxyServer.handle(session)
    }

    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = session.uri
        val playPath = path.substringAfter("/play/", "")
        if (playPath.isEmpty()) return error(NanoHTTPD.Response.Status.NOT_FOUND)

        val parts = playPath.split('/', limit = 2)
        val uuid = parts[0]
        val relativePath = if (parts.size > 1) {
            URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString())
        } else {
            ""
        }
        val target = urlMap[uuid] ?: return error(NanoHTTPD.Response.Status.NOT_FOUND)
        val baseHttpUrl = target.url.toHttpUrlOrNull() ?: return error(NanoHTTPD.Response.Status.INTERNAL_ERROR)

        // Preserve tokens in the registered URL when the player asks for its original filename.
        val registeredFilename = baseHttpUrl.pathSegments.lastOrNull() ?: ""
        val targetHttpUrl = when {
            relativePath.isEmpty() || relativePath == registeredFilename -> baseHttpUrl
            relativePath.startsWith("http") -> relativePath.toHttpUrlOrNull()
            else -> {
                val relativeWithQuery = session.queryParameterString?.let { "$relativePath?$it" } ?: relativePath
                baseHttpUrl.resolve(relativeWithQuery)
            }
        } ?: return error(NanoHTTPD.Response.Status.INTERNAL_ERROR)

        val resolvedUrl = targetHttpUrl.toString()
        println("[ProxyServer] Proxying request to resolved URL: $resolvedUrl")
        val requestBuilder = Request.Builder().url(resolvedUrl)
        val requestHeaders = session.headers
        val headersToSkip = setOf("Host", "Accept-Encoding", "Connection", "User-Agent", "If-None-Match", "If-Modified-Since")

        fun requestHeader(name: String): String? = requestHeaders.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value

        requestHeader("Range")?.let { println("[ProxyServer] Range header: $it") }
        requestHeader("Referer")?.let { requestBuilder.header("Referer", it) }
        requestHeader("Origin")?.let { requestBuilder.header("Origin", it) }

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestBuilder.header("User-Agent", userAgent)

        requestHeaders.forEach { (key, value) ->
            if (headersToSkip.any { it.equals(key, ignoreCase = true) } ||
                key.equals("Referer", ignoreCase = true) ||
                key.equals("Origin", ignoreCase = true)
            ) {
                return@forEach
            }
            requestBuilder.addHeader(key, value)
        }

        for (i in 0 until target.headers.size) {
            val name = target.headers.name(i)
            if (!name.equals("Referer", ignoreCase = true) && !name.equals("Origin", ignoreCase = true)) {
                requestBuilder.header(name, target.headers.value(i))
            }
        }

        val resolvedHost = targetHttpUrl.host
        val customReferer = target.headers["Referer"]
        val customRefererHost = customReferer?.toHttpUrlOrNull()?.host ?: ""
        val isSelfReferer = customReferer != null && resolvedHost.isNotEmpty() && customRefererHost == resolvedHost
        val trackedReferer = if (isCdnHost(resolvedHost)) cdnRefererTracker[resolvedHost] else null
        val playerRefererRaw = requestHeader("Referer")
        val playerRefererHost = playerRefererRaw?.toHttpUrlOrNull()?.host ?: ""
        val playerReferer = playerRefererRaw?.takeIf { playerRefererHost != resolvedHost }

        when {
            trackedReferer != null -> requestBuilder.header("Referer", trackedReferer)
            playerReferer != null -> requestBuilder.header("Referer", playerReferer)
            customReferer != null && !isSelfReferer -> requestBuilder.header("Referer", customReferer)
            isCdnHost(resolvedHost) -> {
                val cdnReferer = mapOf(
                    "cloudwindow-route.com" to "https://voe.sx/",
                    "voe.sx" to "https://voe.sx/",
                ).entries.firstOrNull { resolvedHost.endsWith(it.key) }?.value
                if (cdnReferer != null) requestBuilder.header("Referer", cdnReferer)
            }
            else -> requestBuilder.header("Referer", "${baseHttpUrl.scheme}://${baseHttpUrl.host}/")
        }

        val currentReferer = requestBuilder.build().header("Referer")
        if (requestBuilder.build().header("Origin") == null && currentReferer != null) {
            currentReferer.toHttpUrlOrNull()?.let {
                requestBuilder.header("Origin", "${it.scheme}://${it.host}")
            }
        }

        val call = client.newCall(requestBuilder.build())
        return try {
            val response = call.execute()
            val status = proxyStatus(response.code)
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val body = response.body ?: run {
                response.close()
                return error(status)
            }
            val isHls = contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                resolvedUrl.endsWith(".m3u8", ignoreCase = true)

            if (isHls) {
                val playlist = body.string()
                response.close()
                val bytes = modifyHlsPlaylist(playlist, uuid, target.url).toByteArray(StandardCharsets.UTF_8)
                NanoHTTPD.newFixedLengthResponse(status, contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
            } else {
                // NanoHTTPD owns the returned stream and closes it after the player consumes it.
                NanoHTTPD.newChunkedResponse(status, contentType, body.byteStream())
            }.also { proxyResponse ->
                for (i in 0 until response.headers.size) {
                    val name = response.headers.name(i)
                    if (!name.equals("Transfer-Encoding", ignoreCase = true) &&
                        !name.equals("Content-Length", ignoreCase = true)
                    ) {
                        proxyResponse.addHeader(name, response.headers.value(i))
                    }
                }
            }
        } catch (e: Exception) {
            call.cancel()
            println("[ProxyServer] Error proxying request: ${e.message}")
            error(NanoHTTPD.Response.Status.INTERNAL_ERROR)
        }
    }

    private fun modifyHlsPlaylist(playlist: String, uuid: String, baseUrl: String): String {
        val baseHttpUrl = baseUrl.toHttpUrlOrNull() ?: return playlist
        val port = server?.listeningPort ?: return playlist
        return playlist.lines().joinToString("\n") { line ->
            if (line.isBlank() || line.startsWith("#")) {
                line
            } else {
                val segmentUrl = baseHttpUrl.resolve(line)?.toString() ?: line
                "http://127.0.0.1:$port/play/$uuid/${URLEncoder.encode(segmentUrl, StandardCharsets.UTF_8.toString())}"
            }
        }
    }

    private fun error(status: NanoHTTPD.Response.IStatus): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", "")

    private fun proxyStatus(code: Int): NanoHTTPD.Response.IStatus = object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = code.toString()
    }
}
