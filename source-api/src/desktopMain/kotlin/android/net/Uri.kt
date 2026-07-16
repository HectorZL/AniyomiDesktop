package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

abstract class Uri protected constructor(internal val uri: URI) {

    val scheme: String? get() = uri.scheme
    val host: String? get() = uri.host
    val path: String? get() = uri.path
    val query: String? get() = uri.query
    val fragment: String? get() = uri.fragment
    val pathSegments: List<String> get() {
        val p = uri.path ?: return emptyList()
        return p.split("/").filter { it.isNotEmpty() }
    }
    val lastPathSegment: String? get() = pathSegments.lastOrNull()

    abstract override fun toString(): String

    fun getQueryParameter(key: String): String? {
        val query = uri.query ?: return null
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            val k = if (idx > 0) URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) else pair
            if (k == key) {
                return if (idx > 0 && pair.length > idx + 1) {
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
                } else {
                    ""
                }
            }
        }
        return null
    }

    fun buildUpon(): Builder {
        return Builder().apply {
            scheme(uri.scheme)
            authority(uri.authority)
            path(uri.path)
            query(uri.query)
        }
    }

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private var query: String? = null

        fun scheme(scheme: String?) = apply { this.scheme = scheme }
        fun authority(authority: String?) = apply { this.authority = authority }
        fun path(path: String?) = apply { this.path = path }
        fun query(query: String?) = apply { this.query = query }

        fun appendPath(segment: String) = apply {
            val p = this.path ?: ""
            this.path = if (p.endsWith("/")) p + segment else "$p/$segment"
        }

        fun appendQueryParameter(key: String, value: String) = apply {
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            val q = this.query
            this.query = if (q.isNullOrEmpty()) "$encodedKey=$encodedValue" else "$q&$encodedKey=$encodedValue"
        }

        fun build(): Uri {
            var uriString = ""
            if (scheme != null) uriString += "$scheme://"
            if (authority != null) uriString += authority
            if (path != null) {
                val p = path!!
                if (!p.startsWith("/") && uriString.isNotEmpty()) {
                    uriString += "/"
                }
                uriString += p
            }
            if (query != null) uriString += "?$query"
            return StringUri(URI.create(uriString))
        }
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri {
            return StringUri(URI.create(uriString))
        }
    }
}

private class StringUri(uri: URI) : Uri(uri) {
    override fun toString(): String = uri.toString()
}
