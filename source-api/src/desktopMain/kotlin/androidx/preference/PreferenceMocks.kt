package androidx.preference

import android.content.Context

open class Preference(context: Context) {
    interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any): Boolean
    }
    fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener) {}
    fun getKey(): String = ""
    fun getTitle(): CharSequence = ""
    fun getSummary(): CharSequence = ""
    fun setDefaultValue(defaultValue: Any?) {}
}

open class PreferenceGroup(context: Context) : Preference(context) {
    fun addPreference(preference: Preference): Boolean = true
}

open class PreferenceScreen(context: Context) : PreferenceGroup(context)

open class PreferenceManager(context: Context) {
    fun createPreferenceScreen(context: Context): PreferenceScreen = PreferenceScreen(context)
}

open class ListPreference(context: Context) : Preference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
    var value: String? = null
}

open class CheckBoxPreference(context: Context) : Preference(context) {
    var isChecked: Boolean = false
}

open class EditTextPreference(context: Context) : Preference(context) {
    var text: String? = null
}

open class MultiSelectListPreference(context: Context) : Preference(context) {
    var entries: Array<CharSequence>? = null
    var entryValues: Array<CharSequence>? = null
    var values: Set<String>? = null
}

open class SwitchPreferenceCompat(context: Context) : Preference(context) {
    var isChecked: Boolean = false
}

open class SwitchPreference(context: Context) : Preference(context) {
    var isChecked: Boolean = false
}
