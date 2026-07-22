@file:Suppress("UNUSED_PARAMETER", "unused")

package android.net

/**
 * Stub for android.net.Uri.
 */
open class Uri private constructor(private val uriString: String) {

    override fun toString(): String = uriString

    open fun getScheme(): String? = uriString.substringBefore(":", missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    open fun getHost(): String? = try { java.net.URI(uriString).host } catch (_: Exception) { null }
    open fun getPath(): String? = try { java.net.URI(uriString).path } catch (_: Exception) { null }
    open fun getQuery(): String? = try { java.net.URI(uriString).query } catch (_: Exception) { null }
    open fun getFragment(): String? = try { java.net.URI(uriString).fragment } catch (_: Exception) { null }
    open fun getQueryParameter(key: String): String? = null
    open fun getLastPathSegment(): String? = uriString.substringAfterLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    companion object {
        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic
        fun fromFile(file: java.io.File): Uri = Uri("file://${file.absolutePath}")

        @JvmStatic
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        @JvmStatic
        fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
    }
}
