package android.content.pm

import android.content.Context
import android.content.Intent

open class PackageManager {
    open fun getPackageInfo(packageName: String, flags: Int): PackageInfo? = null
    open fun queryIntentActivities(intent: Intent, flags: Int): List<ResolveInfo> = emptyList()
}

data class PackageInfo(
    val packageName: String = "",
    val versionName: String = "1.0",
    val versionCode: Int = 1
)

data class ResolveInfo(
    val activityInfo: ActivityInfo? = null,
    val resolvePackageName: String? = null
)

data class ActivityInfo(
    val name: String = "",
    val packageName: String = "",
    val exported: Boolean = false
)

open class ComponentName {
    constructor(pkg: String, cls: String) {}
    constructor(ctx: Context?, cls: Class<*>?) {}
    open fun getPackageName(): String = ""
    open fun getClassName(): String = ""
}
