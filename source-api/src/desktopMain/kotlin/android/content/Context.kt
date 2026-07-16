package android.content

import java.io.File
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

open class Context {
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return SharedPreferences(name)
    }

    companion object {
        const val MODE_PRIVATE = 0
    }
}

class SharedPreferences(private val name: String) {
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

    fun getString(key: String, defValue: String?): String? {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.content
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.boolean
    }

    fun getInt(key: String, defValue: Int): Int {
        val elem = data[key] ?: return defValue
        return elem.jsonPrimitive.int
    }

    fun edit(): Editor {
        return Editor()
    }

    inner class Editor {
        private val tempMap = data.toMutableMap()

        fun putString(key: String, value: String?): Editor {
            if (value == null) {
                tempMap.remove(key)
            } else {
                tempMap[key] = JsonPrimitive(value)
            }
            return this
        }

        fun putBoolean(key: String, value: Boolean): Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        fun putInt(key: String, value: Int): Editor {
            tempMap[key] = JsonPrimitive(value)
            return this
        }

        fun remove(key: String): Editor {
            tempMap.remove(key)
            return this
        }

        fun clear(): Editor {
            tempMap.clear()
            return this
        }

        fun apply() {
            data = tempMap
            save()
        }

        fun commit(): Boolean {
            data = tempMap
            save()
            return true
        }
    }
}

open class ContextWrapper(private val base: Context?) : Context() {
    fun getBaseContext(): Context? = base
}
