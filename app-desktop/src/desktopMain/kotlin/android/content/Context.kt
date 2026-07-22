@file:Suppress("UNUSED_PARAMETER", "unused")

package android.content

/**
 * Stub for android.content.Context.
 */
abstract class Context {
    private val prefsStore = mutableMapOf<String, SharedPreferences>()

    open fun getSystemService(name: String): Any? = null
    open fun getFilesDir(): java.io.File = java.io.File(System.getProperty("user.home"), "AniyomiDesktop/files")
    open fun getCacheDir(): java.io.File = java.io.File(System.getProperty("user.home"), "AniyomiDesktop/cache")
    open fun getExternalFilesDir(type: String?): java.io.File? = null
    open fun getPackageName(): String = "eu.kanade.tachiyomi"
    open fun getApplicationContext(): Context = this
    open fun getString(resId: Int): String = ""
    open fun getString(resId: Int, vararg formatArgs: Any?): String = ""
    open fun getResources(): Resources = Resources()
    open fun getContentResolver(): ContentResolver = ContentResolver()

    /**
     * Returns a real AssetManager that reads resources from the current thread's
     * context classloader (which is the extension's ASMClassLoader during constructor calls).
     * Extensions use context.assets.open("file.json") to read bundled JSON/config files.
     */
    open fun getAssets(): AssetManager = AssetManager()

    /**
     * Returns a SharedPreferences instance (interface, as in real Android) for [name].
     * Returns the same instance for repeated calls with the same name.
     */
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsStore.getOrPut(name) { SharedPreferencesImpl(name) }

    companion object {
        const val MODE_PRIVATE = 0
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val WIFI_SERVICE = "wifi"
    }
}

/**
 * android.content.res.AssetManager stub.
 * Reads resources from the extension JAR via the thread's context classloader.
 * When extensions call context.assets.open("file.json"), the file is looked up
 * inside the extension JAR that the ASMClassLoader loaded.
 */
open class AssetManager {
    /**
     * Opens an asset file for reading. Tries multiple path forms to find the resource
     * inside the extension JAR:
     *   1. "assets/<name>" (Android APK asset path)
     *   2. "<name>" (flat in JAR root)
     *   3. "/<name>" (absolute resource path)
     */
    open fun open(fileName: String): java.io.InputStream {
        val cl = Thread.currentThread().contextClassLoader
            ?: AssetManager::class.java.classLoader

        // Try common path variants used by different extension builds
        val candidates = listOf(
            "assets/$fileName",
            fileName,
            "res/raw/$fileName",
        )
        for (path in candidates) {
            val stream = cl?.getResourceAsStream(path)
            if (stream != null) {
                println("[AssetManager] Opened asset: $path via classloader")
                return stream
            }
        }
        throw java.io.FileNotFoundException("Asset not found: $fileName (tried: $candidates)")
    }

    open fun list(path: String): Array<String> = emptyArray()
    open fun open(fileName: String, accessMode: Int): java.io.InputStream = open(fileName)
    open fun close() {}
}



// ─────────────────────────────────────────────────────────────────────────────
// SharedPreferences — MUST be an interface (same as Android SDK).
// Extensions reference it with INVOKEINTERFACE; using a class throws
// IncompatibleClassChangeError at runtime.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * android.content.SharedPreferences — declared as interface to match the Android SDK.
 */
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
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }

    /**
     * android.content.SharedPreferences.Editor — also an interface in Android.
     */
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

/**
 * Concrete desktop implementation of SharedPreferences.
 * Stores values in memory; optionally persists to a JSON file under
 * AniyomiDesktop/prefs/<name>.json so preferences survive restarts.
 */
internal class SharedPreferencesImpl(private val name: String) : SharedPreferences {

    private val store: MutableMap<String, Any?> = mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): Map<String, *> = store.toMap()

    override fun getString(key: String, defValue: String?): String? =
        store.getOrDefault(key, defValue) as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (store[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (store[key] as? Number)?.toInt() ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (store[key] as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (store[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        store.getOrDefault(key, defValue) as? Boolean ?: defValue

    override fun contains(key: String): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl(store)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl(
        private val backing: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value } as SharedPreferences.Editor
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toHashSet() } as SharedPreferences.Editor
        override fun putInt(key: String, value: Int) = apply { pending[key] = value } as SharedPreferences.Editor
        override fun putLong(key: String, value: Long) = apply { pending[key] = value } as SharedPreferences.Editor
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value } as SharedPreferences.Editor
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value } as SharedPreferences.Editor
        override fun remove(key: String) = apply { removals.add(key) } as SharedPreferences.Editor
        override fun clear() = apply { clearAll = true } as SharedPreferences.Editor

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() { flush() }

        private fun flush() {
            synchronized(backing) {
                if (clearAll) backing.clear()
                removals.forEach { backing.remove(it) }
                backing.putAll(pending)
            }
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Other stubs
// ─────────────────────────────────────────────────────────────────────────────

open class ContentResolver {
    open fun openInputStream(uri: android.net.Uri): java.io.InputStream? = null
    open fun openOutputStream(uri: android.net.Uri): java.io.OutputStream? = null
}

open class Resources {
    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg formatArgs: Any?): String = ""
    open fun getBoolean(id: Int): Boolean = false
    open fun getInteger(id: Int): Int = 0
}
