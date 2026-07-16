package android.util

object Log {
    @JvmStatic
    fun d(tag: String, msg: String): Int {
        println("[$tag] (DEBUG) $msg")
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable): Int {
        println("[$tag] (DEBUG) $msg")
        tr.printStackTrace()
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        println("[$tag] (INFO) $msg")
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable): Int {
        println("[$tag] (INFO) $msg")
        tr.printStackTrace()
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        println("[$tag] (WARN) $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable): Int {
        println("[$tag] (WARN) $msg")
        tr.printStackTrace()
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        System.err.println("[$tag] (ERROR) $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int {
        System.err.println("[$tag] (ERROR) $msg")
        tr.printStackTrace()
        return 0
    }
}
