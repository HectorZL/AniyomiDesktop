package android.content

import android.net.Uri
import android.os.Bundle

open class Intent {
    constructor()
    constructor(packageContext: Context?, cls: Class<*>?) {}
    constructor(action: String?) {}
    constructor(action: String?, uri: Uri?, packageContext: Context?, cls: Class<*>?) {}
    constructor(action: String?, uri: Uri?) {}

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    }

    private var action: String? = null
    private var data: Uri? = null
    private val extras: Bundle = Bundle()
    private var flags: Int = 0

    fun getAction(): String? = action
    fun setAction(action: String?): Intent { this.action = action; return this }

    fun getData(): Uri? = data
    fun setData(data: Uri?): Intent { this.data = data; return this }

    fun getFlags(): Int = flags
    fun setFlags(flags: Int): Intent { this.flags = flags; return this }
    fun addFlags(flags: Int): Intent { this.flags = this.flags or flags; return this }

    fun putExtra(name: String, value: String): Intent { extras.putString(name, value); return this }
    fun putExtra(name: String, value: Int): Intent { extras.putInt(name, value); return this }
    fun putExtra(name: String, value: Boolean): Intent { extras.putBoolean(name, value); return this }
    fun putExtra(name: String, value: Long): Intent { extras.putLong(name, value); return this }

    fun getStringExtra(name: String): String? = extras.getString(name)
    fun getIntExtra(name: String, default: Int): Int = extras.getInt(name, default)
    fun getBooleanExtra(name: String, default: Boolean): Boolean = extras.getBoolean(name, default)
    fun getLongExtra(name: String, default: Long): Long = extras.getLong(name, default)

    fun hasExtra(name: String): Boolean = extras.containsKey(name)

    fun getExtras(): Bundle = extras

    fun resolveActivity(pm: android.content.pm.PackageManager?): android.content.pm.ActivityInfo? = null

    fun setClassName(packageName: String, className: String): Intent { return this }
    fun setClass(packageContext: Context?, cls: Class<*>?): Intent { return this }
}
