package android.view

import android.content.Context

open class View {
    constructor(context: Context?) {}
    constructor(context: Context?, attrs: android.util.AttributeSet?) {}

    open fun setOnClickListener(l: OnClickListener?) {}
    open fun setVisibility(visibility: Int) {}

    interface OnClickListener {
        fun onClick(v: View?)
    }

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}
