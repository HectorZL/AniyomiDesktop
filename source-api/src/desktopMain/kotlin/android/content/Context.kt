package android.content

import java.io.File
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

open class Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return SharedPreferencesImpl(name)
    }

    fun getPackageName(): String = "eu.kanade.tachiyomi"
    
    fun getCacheDir(): File = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/cache")
    
    fun getFilesDir(): File = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/files")

    companion object {
        const val MODE_PRIVATE = 0
    }
}

interface SharedPreferences {
    fun getAll(): Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class SharedPreferencesImpl(private val name: String) : SharedPreferences {
    private val file: File
    private var data: MutableMap<String, JsonElement> = mutableMapOf()
    private val json = Json { prettyPrint = true }

    init {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/preferences")
        if (!appDir.exists()) appDir.mkdirs()
        file = File(appDir, "$name.json")
        load()
    }

    private fun load() {
        if (file.exists()) {
            try {
                val text = file.readText()
                val parsed = json.parseToJsonElement(text).jsonObject
                data = parsed.toMutableMap()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun save() {
        try {
            val jsonObject = JsonObject(data)
            file.writeText(json.encodeToString(jsonObject))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getAll(): Map<String, *> {
        return data.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> {
                    if (value.isString) value.content
                    else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
                }
                is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
                else -> value.toString()
            }
        }
    }

    override fun getString(key: String, defValue: String?): String? {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.content
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val elem = data[key] ?: return defValues
        return try {
            elem.jsonArray.map { it.jsonPrimitive.content }.toSet()
        } catch (e: Exception) {
            defValues
        }
    }

    override fun getInt(key: String, defValue: Int): Int {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.int
    }

    override fun getLong(key: String, defValue: Long): Long {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.long
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.float
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.boolean
    }

    override fun contains(key: String): Boolean {
        return data.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return EditorImpl()
    }

    inner class EditorImpl : SharedPreferences.Editor {
        private val tempMap = data.toMutableMap()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) {
                tempMap.remove(key)
            } else {
                tempMap[key] = JsonPrimitive(value)
            }
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values == null) {
                tempMap.remove(key)
            } else {
                tempMap[key] = JsonArray(values.map { JsonPrimitive(it) })
            }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            tempMap.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            tempMap.clear()
            return this
        }

        override fun apply() {
            data = tempMap
            save()
        }

        override fun commit(): Boolean {
            data = tempMap
            save()
            return true
        }
    }
}

open class ContextWrapper(private val base: Context?) : Context() {
    fun getBaseContext(): Context? = base
}
