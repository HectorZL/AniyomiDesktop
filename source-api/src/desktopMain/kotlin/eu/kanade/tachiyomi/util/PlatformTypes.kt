package eu.kanade.tachiyomi.util

actual abstract class PlatformUri {
    actual abstract override fun toString(): String
}

actual class PlatformBitmap

actual object PlatformBitmapFactory {
    actual fun decodeStream(stream: java.io.InputStream): PlatformBitmap? {
        return null
    }
}
