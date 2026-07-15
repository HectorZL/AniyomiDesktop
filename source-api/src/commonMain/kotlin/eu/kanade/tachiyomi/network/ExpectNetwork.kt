package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import rx.Observable
import eu.kanade.tachiyomi.network.compat.ProgressListener

expect fun GET(
    url: String,
    headers: Headers = Headers.Builder().build(),
    cache: CacheControl = CacheControl.Builder().build()
): Request

expect fun OkHttpClient.newCachelessCallWithProgress(
    request: Request,
    listener: ProgressListener
): okhttp3.Call

expect fun okhttp3.Call.asObservableSuccess(): Observable<Response>

expect suspend fun okhttp3.Call.await(): Response

expect suspend fun okhttp3.Call.awaitSuccess(): Response
