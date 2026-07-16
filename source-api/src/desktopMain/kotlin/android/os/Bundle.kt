package android.os

import java.io.Serializable

open class Bundle {
    private val map = mutableMapOf<String, Any?>()

    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, defaultValue: String): String = map[key] as? String ?: defaultValue
    fun putString(key: String, value: String) { map[key] = value }

    fun getInt(key: String): Int = (map[key] as? Int) ?: 0
    fun getInt(key: String, defaultValue: Int): Int = (map[key] as? Int) ?: defaultValue
    fun putInt(key: String, value: Int) { map[key] = value }

    fun getBoolean(key: String): Boolean = (map[key] as? Boolean) ?: false
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = (map[key] as? Boolean) ?: defaultValue
    fun putBoolean(key: String, value: Boolean) { map[key] = value }

    fun getLong(key: String): Long = (map[key] as? Long) ?: 0L
    fun getLong(key: String, defaultValue: Long): Long = (map[key] as? Long) ?: defaultValue
    fun putLong(key: String, value: Long) { map[key] = value }

    fun getSerializable(key: String): Serializable? = map[key] as? Serializable
    fun putSerializable(key: String, value: Serializable?) { map[key] = value }

    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun isEmpty(): Boolean = map.isEmpty()
    fun size(): Int = map.size

    fun clear() { map.clear() }

    override fun toString(): String = "Bundle(${map.size} items)"
}
