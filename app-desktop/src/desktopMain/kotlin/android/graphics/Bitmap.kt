@file:Suppress("UNUSED_PARAMETER", "unused")

package android.graphics

/**
 * Stub for android.graphics.Bitmap.
 * Extensions may reference this type in WebViewClient callbacks without actually using it on desktop.
 */
open class Bitmap {
    enum class CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }
    enum class Config { ALPHA_8, ARGB_4444, ARGB_8888, RGB_565, RGBA_F16, HARDWARE }

    open val width: Int get() = 0
    open val height: Int get() = 0
    open val config: Config get() = Config.ARGB_8888
    open fun isRecycled(): Boolean = true
    open fun recycle() {}
    open fun compress(format: CompressFormat, quality: Int, stream: java.io.OutputStream): Boolean = false

    companion object {
        @JvmStatic
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap()

        @JvmStatic
        fun createBitmap(source: Bitmap): Bitmap = Bitmap()
    }
}
