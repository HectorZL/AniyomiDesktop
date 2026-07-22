@file:Suppress("UNUSED_PARAMETER", "unused")

package android.net.http

/**
 * Stub for android.net.http.SslError.
 * Referenced by WebViewClient.onReceivedSslError().
 *
 * Note: getPrimaryError() and getUrl() are provided via @JvmName to avoid
 * clash with Kotlin's auto-generated property accessors.
 */
open class SslError(
    @get:JvmName("getPrimaryError") val primaryError: Int,
    @get:JvmName("getUrl") val url: String?,
) {
    companion object {
        const val SSL_NOTYETVALID = 0
        const val SSL_EXPIRED = 1
        const val SSL_IDMISMATCH = 2
        const val SSL_UNTRUSTED = 3
        const val SSL_DATE_INVALID = 4
        const val SSL_INVALID = Integer.MAX_VALUE
    }
}
