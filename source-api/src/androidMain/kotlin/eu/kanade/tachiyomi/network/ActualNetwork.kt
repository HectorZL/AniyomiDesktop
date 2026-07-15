package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import okhttp3.OkHttpClient
import rx.Observable
import eu.kanade.tachiyomi.network.compat.ProgressListener

import eu.kanade.tachiyomi.network.GET as androidGET
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress as androidNewCallWithProgress
import eu.kanade.tachiyomi.network.asObservableSuccess as androidAsObservableSuccess
import eu.kanade.tachiyomi.network.await as androidAwait
import eu.kanade.tachiyomi.network.awaitSuccess as androidAwaitSuccess

actual fun GET(
    url: String,
    headers: Headers,
    cache: CacheControl
): Request {
    return androidGET(url, headers, cache)
}

actual fun OkHttpClient.newCachelessCallWithProgress(
    request: Request,
    listener: ProgressListener
): okhttp3.Call {
    return this.androidNewCallWithProgress(request, listener)
}

actual fun okhttp3.Call.asObservableSuccess(): Observable<Response> {
    return this.androidAsObservableSuccess()
}

actual suspend fun okhttp3.Call.await(): Response {
    return this.androidAwait()
}

actual suspend fun okhttp3.Call.awaitSuccess(): Response {
    return this.androidAwaitSuccess()
}
