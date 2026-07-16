package androidx.preference

import android.content.Context
import android.content.SharedPreferences

open class Preference(val context: Context) {
    interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any): Boolean
    }
    
    private var listener: OnPreferenceChangeListener? = null
    private var key: String = ""
    private var title: CharSequence = ""
    private var summary: CharSequence = ""
    private var defaultValue: Any? = null
    private var isVisible: Boolean = true
    private var isEnabled: Boolean = true

    fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener) {
        this.listener = listener
    }
    
    fun getKey(): String = key
    fun setKey(key: String) {
        this.key = key
    }
    
    fun getTitle(): CharSequence = title
    fun setTitle(title: CharSequence?) {
        this.title = title ?: ""
    }
    fun setTitle(titleRes: Int) {
        this.title = ""
    }
    
    fun getSummary(): CharSequence = summary
    fun setSummary(summary: CharSequence?) {
        this.summary = summary ?: ""
    }
    fun setSummary(summaryRes: Int) {
        this.summary = ""
    }
    
    fun setDefaultValue(defaultValue: Any?) {
        this.defaultValue = defaultValue
    }
    
    fun setVisible(visible: Boolean) {
        this.isVisible = visible
    }
    fun isVisible(): Boolean = isVisible
    
    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
    }
    fun isEnabled(): Boolean = isEnabled
    
    fun setIcon(icon: Any?) {}
    fun setIcon(iconRes: Int) {}
    
    fun getSharedPreferences(): SharedPreferences {
        return context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    }
}

open class PreferenceGroup(context: Context) : Preference(context) {
    private val preferences = mutableListOf<Preference>()
    
    fun addPreference(preference: Preference): Boolean {
        preferences.add(preference)
        return true
    }
    
    fun findPreference(key: CharSequence): Preference? {
        return preferences.find { it.getKey() == key.toString() }
    }
}

open class PreferenceScreen(context: Context) : PreferenceGroup(context)

open class PreferenceManager(val context: Context) {
    fun createPreferenceScreen(context: Context): PreferenceScreen = PreferenceScreen(context)
    fun getSharedPreferences(): SharedPreferences {
        return context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    }
    
    companion object {
        @JvmStatic
        fun getDefaultSharedPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences("default_preferences", Context.MODE_PRIVATE)
        }
    }
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
    interface OnBindEditTextListener {
        fun onBindEditText(editText: android.widget.EditText)
    }
    fun setOnBindEditTextListener(listener: OnBindEditTextListener?) {}
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
