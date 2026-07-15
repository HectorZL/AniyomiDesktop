package eu.kanade.tachiyomi.network.compat

import okhttp3.OkHttpClient

expect class NetworkHelper {
    val client: OkHttpClient
    fun defaultUserAgentProvider(): String
}

expect interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
