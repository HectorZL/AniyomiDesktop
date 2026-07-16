package android.os

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long {
        return System.currentTimeMillis()
    }

    @JvmStatic
    fun uptimeMillis(): Long {
        return System.currentTimeMillis()
    }
}
