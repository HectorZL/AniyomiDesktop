package android.graphics

open class Bitmap {
    open val width: Int get() = 0
    open val height: Int get() = 0
}

open class BitmapFactory {
    open fun decodeFile(pathName: String?): Bitmap? = null
}

class Canvas {
    open fun drawBitmap(bitmap: Bitmap?, left: Float, top: Float, paint: Paint?) {}
}

class Paint {
    constructor()
    constructor(flags: Int) {}
    open fun setColor(color: Int) {}
    open fun setAlpha(a: Int) {}
    open fun setTypeface(typeface: Typeface?) {}
    open fun setTextSize(textSize: Float) {}
    open fun measureText(text: String?): Float = 0f
}

open class Typeface {
    companion object {
        @JvmStatic
        val DEFAULT: Typeface = Typeface()
        @JvmStatic
        val BOLD: Typeface = Typeface()
    }
}

open class ColorMatrix

class ColorMatrixColorFilter {
    constructor(matrix: ColorMatrix?) {}
}

class PorterDuffXfermode {
    constructor(mode: PorterDuffMode?) {}
}

enum class PorterDuffMode {
    SRC_IN, SRC_OVER, CLEAR, DST
}

open class Rect {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
}
