package android.text

interface InputType {
    companion object {
        const val TYPE_CLASS_TEXT = 1
        const val TYPE_TEXT_VARIATION_PASSWORD = 128
        const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 144
        const val TYPE_TEXT_FLAG_MULTI_LINE = 131072
        const val TYPE_CLASS_NUMBER = 2
    }
}
