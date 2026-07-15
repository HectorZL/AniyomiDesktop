package eu.kanade.tachiyomi.util

actual abstract class PlatformUri {
    actual abstract override fun toString(): String
}

actual interface PlatformProgressListener {
    actual fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
