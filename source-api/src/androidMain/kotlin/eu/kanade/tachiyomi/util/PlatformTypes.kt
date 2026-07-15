package eu.kanade.tachiyomi.util

actual typealias PlatformUri = android.net.Uri

actual typealias PlatformBitmap = android.graphics.Bitmap

actual object PlatformBitmapFactory {
    actual fun decodeStream(stream: java.io.InputStream): PlatformBitmap? {
        return android.graphics.BitmapFactory.decodeStream(stream)
    }
}
