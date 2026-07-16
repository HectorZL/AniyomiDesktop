package eu.kanade.tachiyomi.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object VideoProxyServer {
    private var server: HttpServer? = null
    private val urlMap = ConcurrentHashMap<String, Pair<String, Map<String, String>>>()

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
        println("[ProxyServer] Started on port ${s.address.port}")
        return s.address.port
    }

    fun registerVideo(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url

        val id = UUID.randomUUID().toString()
        urlMap[id] = Pair(url, headers)
        val port = start()
        
        val httpUrl = url.toHttpUrlOrNull()
        val fileName = httpUrl?.pathSegments?.lastOrNull() ?: "video.mp4"
        val proxiedUrl = "http://127.0.0.1:$port/play/$id/$fileName"
        println("[ProxyServer] Registered URL: $url -> $proxiedUrl with headers: $headers")
        return proxiedUrl
    }

    private class ProxyHandler : HttpHandler {
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
            val relativePath = if (parts.size > 1) parts[1] else ""

            val target = urlMap[uuid]
            if (target == null) {
                exchange.sendResponseHeaders(404, 0)
                exchange.close()
                return
            }

            val (realUrl, customHeaders) = target
            val baseHttpUrl = realUrl.toHttpUrlOrNull()
            if (baseHttpUrl == null) {
                exchange.sendResponseHeaders(500, 0)
                exchange.close()
                return
            }

            val targetHttpUrl = if (relativePath.isNotEmpty()) {
                val relativeWithQuery = if (query != null) "$relativePath?$query" else relativePath
                baseHttpUrl.resolve(relativeWithQuery)
            } else {
                baseHttpUrl
            }

            if (targetHttpUrl == null) {
                exchange.sendResponseHeaders(500, 0)
                exchange.close()
                return
            }

            val resolvedUrl = targetHttpUrl.toString()
            println("[ProxyServer] Proxying request to resolved URL: $resolvedUrl")

            val client = Injekt.get<NetworkHelper>().client
            val requestBuilder = Request.Builder().url(resolvedUrl)

            // Copy request headers from player
            for ((key, values) in exchange.requestHeaders) {
                if (key.equals("Host", ignoreCase = true)) continue
                for (value in values) {
                    requestBuilder.addHeader(key, value)
                }
            }

            // Apply custom headers from extension
            for ((key, value) in customHeaders) {
                requestBuilder.header(key, value)
            }

            try {
                val call = client.newCall(requestBuilder.build())
                val response = call.execute()
                
                // Copy response headers
                val responseHeaders = exchange.responseHeaders
                for (i in 0 until response.headers.size) {
                    val name = response.headers.name(i)
                    val value = response.headers.value(i)
                    if (name.equals("Transfer-Encoding", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) continue
                    responseHeaders.add(name, value)
                }

                val statusCode = response.code
                val responseBody = response.body
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
                response.close()
            } catch (e: Exception) {
                println("[ProxyServer] Error proxying request: ${e.message}")
                try {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                } catch (ignored: Exception) {}
            }
        }
    }
}
