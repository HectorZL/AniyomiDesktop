package android.content.res

open class TypedArray {
    open fun getString(index: Int): String? = null
    open fun getInt(index: Int, defValue: Int): Int = defValue
    open fun getBoolean(index: Int, defValue: Boolean): Boolean = defValue
    open fun getDimension(index: Int, defValue: Float): Float = defValue
    open fun recycle() {}
}
