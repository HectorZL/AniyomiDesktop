package eu.kanade.tachiyomi.network

import okhttp3.CookieJar
import okhttp3.OkHttpClient

class NetworkHelper {
    val cookieJar: CookieJar = CookieJar.NO_COOKIES

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .build()

    val nonCloudflareClient: OkHttpClient = client

    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider(): String = 
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
