package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

open class Looper private constructor() {
    companion object {
        private val mainLooper = Looper()
        
        @JvmStatic
        fun getMainLooper(): Looper = mainLooper

        @JvmStatic
        fun myLooper(): Looper? = mainLooper
    }
}

open class Handler {
    companion object {
        private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "Handler-Scheduler-Thread").apply { isDaemon = true }
        }
    }

    constructor()
    constructor(looper: Looper)

    open fun post(r: Runnable): Boolean {
        scheduler.execute(r)
        return true
    }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        scheduler.schedule(r, delayMillis, TimeUnit.MILLISECONDS)
        return true
    }

    open fun removeCallbacks(r: Runnable) {
        // Simple mock behavior
    }
}
