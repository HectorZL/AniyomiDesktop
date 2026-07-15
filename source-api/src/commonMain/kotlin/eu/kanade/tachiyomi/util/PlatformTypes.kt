package eu.kanade.tachiyomi.util

expect abstract class PlatformUri {
    abstract override fun toString(): String
}

expect interface PlatformProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
