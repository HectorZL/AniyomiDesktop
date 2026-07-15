package eu.kanade.tachiyomi.data.track.trakt

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.trakt.TraktApi.Companion.CLIENT_ID
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktOAuth
import okhttp3.Interceptor
import okhttp3.Response

class TraktInterceptor(val trakt: Trakt) : Interceptor {

    /**
     * OAuth object used for authenticated requests.
     */
    private var oauth: TraktOAuth? = trakt.restoreToken()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val oauth = oauth ?: throw Exception("Not authenticated with Trakt")

        // Add the authorization header to the original request.
        val authRequest = originalRequest.newBuilder()
            .addHeader("Authorization", "Bearer ${oauth.accessToken}")
            .addHeader("trakt-api-key", CLIENT_ID)
            .addHeader("trakt-api-version", "2")
            .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(oauth: TraktOAuth?) {
        this.oauth = oauth
        trakt.saveToken(oauth)
    }
}
