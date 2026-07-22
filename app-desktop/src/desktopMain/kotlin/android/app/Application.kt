@file:Suppress("UNUSED_PARAMETER", "unused")

package android.app

import android.content.Context

/**
 * Stub for android.app.Application.
 * Used in main.kt to register a singleton with Injekt.
 * Some extensions may also reference it as their context source.
 */
open class Application : Context() {
    open fun onCreate() {}
    open fun onTerminate() {}
    open fun onLowMemory() {}
    open fun onTrimMemory(level: Int) {}

    companion object {
        const val TRIM_MEMORY_COMPLETE = 80
        const val TRIM_MEMORY_MODERATE = 60
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
    }
}
