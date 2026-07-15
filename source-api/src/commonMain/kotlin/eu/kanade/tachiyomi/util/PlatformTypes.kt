package eu.kanade.tachiyomi.util

expect abstract class PlatformUri {
    abstract override fun toString(): String
}

expect class PlatformBitmap

expect object PlatformBitmapFactory {
    fun decodeStream(stream: java.io.InputStream): PlatformBitmap?
}
