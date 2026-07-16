package android.net.http

import java.security.cert.X509Certificate

open class SslError {
    open fun getCertificate(): X509Certificate? = null
    open fun getUrl(): String? = null
}
