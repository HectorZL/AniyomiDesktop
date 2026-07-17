package eu.kanade.tachiyomi

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.io.IOException

class Reproduce403Test {
    @Test
    fun reproduce403() {
        val url = "https://moon.ironwallnet.net/video.mp4"
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("Referer", "https://moon.ironwallnet.net/")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Request failed with code: ${response.code}")
                    // Assert that it is 403
                    assert(response.code == 403) { "Expected 403, got ${response.code}" }
                } else {
                    println("Request successful!")
                }
            }
        } catch (e: IOException) {
            println("Network error: ${e.message}")
        }
    }
}
