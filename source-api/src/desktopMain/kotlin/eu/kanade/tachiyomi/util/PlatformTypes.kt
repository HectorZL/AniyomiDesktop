package eu.kanade.tachiyomi.util

actual typealias PlatformUri = android.net.Uri

actual class PlatformBitmap

actual object PlatformBitmapFactory {
    actual fun decodeStream(stream: java.io.InputStream): PlatformBitmap? {
        return null
    }
}
