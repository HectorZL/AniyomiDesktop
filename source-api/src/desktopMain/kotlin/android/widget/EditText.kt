package android.widget

import android.text.Editable

open class EditText {
    fun setText(text: CharSequence?) {}
    fun getText(): Editable? = null
    fun setInputType(type: Int) {}
    fun setSelection(index: Int) {}
}
