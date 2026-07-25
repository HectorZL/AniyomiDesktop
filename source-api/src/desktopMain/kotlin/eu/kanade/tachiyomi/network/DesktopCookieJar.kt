package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class DesktopCookieJar private constructor() : CookieJar {
    companion object {
        /** Shared by OkHttp and the desktop browser challenge handler. */
        val shared = DesktopCookieJar()
    }

    /**
     * Cookies are keyed globally by their identity rather than by the host that
     * produced them. This lets a domain cookie issued on `www.example.com` also
     * match requests to another allowed subdomain.
     */
    private val cookieStore = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val key = cookieKey(cookie)
            if (cookie.expiresAt > now) {
                cookieStore[key] = cookie
            } else {
                cookieStore.remove(key)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookieStore.entries.removeIf { it.value.expiresAt <= now }
        return cookieStore.values.filter { it.matches(url) }
    }

    /** Imports a browser-style `Cookie` header into the OkHttp cookie store. */
    fun importCookieHeader(url: HttpUrl, cookieHeader: String?) {
        val cookies = cookieHeader
            ?.split(';')
            ?.mapNotNull { Cookie.parse(url, it.trim()) }
            .orEmpty()
        saveFromResponse(url, cookies)
    }

    fun remove(url: HttpUrl, names: Set<String>) {
        cookieStore.entries.removeIf { (_, cookie) ->
            cookie.name in names && cookie.matches(url)
        }
    }

    private fun cookieKey(cookie: Cookie): String {
        return "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"
    }
}
