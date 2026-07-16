package eu.kanade.tachiyomi.network

import okhttp3.CookieJar
import okhttp3.OkHttpClient

class NetworkHelper {
    val cookieJar: CookieJar = CookieJar.NO_COOKIES

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
            println("[HTTP REQUEST] ${request.method} ${request.url}")
            try {
                val response = chain.proceed(request)
                val bodyString = response.peekBody(1024 * 1024).string()
                println("[HTTP RESPONSE] ${response.code} ${request.url} (Body Length: ${bodyString.length})")
                if (response.code >= 400 || bodyString.contains("Cloudflare") || bodyString.contains("Just a moment")) {
                    println("[HTTP DETECTED] Potential challenge or block page. Sneak peek: ${bodyString.take(400)}")
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
