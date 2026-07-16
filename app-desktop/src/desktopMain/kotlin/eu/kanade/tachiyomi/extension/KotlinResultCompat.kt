package eu.kanade.tachiyomi.extension

/**
 * Runtime compatibility shim for Kotlin value-class (inline class) synthetic
 * static methods generated for `kotlin.Result` that are absent from the desktop
 * Kotlin stdlib (they exist only in newer Kotlin compiler output for Android targets).
 *
 * The ASMClassLoader patches INVOKESTATIC calls targeting `kotlin/Result.*_impl`
 * to call these methods instead.
 *
 * Internally, `kotlin.Result` is a value class whose underlying representation is
 * just `Object`:
 *   - A *successful* result is the raw value itself (any Object, including null
 *     wrapped in a special marker).
 *   - A *failed* result is a `Result.Failure` wrapper around a Throwable.
 *
 * On the JVM this means the "boxed" value IS the raw Object at call-sites where
 * type-erasure applies.  These helpers replicate that contract faithfully.
 */
object KotlinResultCompat {

    // Internal marker class that wraps a failure — mirrors kotlin.Result.Failure
    // We cannot reference the real inner class by name because it's private,
    // so we use reflection to detect it and to create instances when needed.
    private val failureClass: Class<*>? by lazy {
        try {
            Class.forName("kotlin.Result\$Failure")
        } catch (_: ClassNotFoundException) {
            null
        }
    }
    private val failureExceptionField by lazy {
        failureClass?.getDeclaredField("exception")?.also { it.isAccessible = true }
    }

    private fun isFailureObject(value: Any?): Boolean {
        if (value == null) return false
        val fc = failureClass ?: return false
        return fc.isInstance(value)
    }

    private fun failureExceptionOf(value: Any?): Throwable? {
        if (!isFailureObject(value)) return null
        return failureExceptionField?.get(value) as? Throwable
    }

    // -------------------------------------------------------------------------
    // constructor_impl(Object): Object
    //   The raw value IS the Result — identity function.
    //   (The no-op in the patcher handles this; this method is here for completeness.)
    // -------------------------------------------------------------------------
    @JvmStatic
    fun constructorImpl(value: Any?): Any? = value

    // -------------------------------------------------------------------------
    // isSuccess_impl(Object): boolean
    // -------------------------------------------------------------------------
    @JvmStatic
    fun isSuccess(value: Any?): Boolean = !isFailureObject(value)

    // -------------------------------------------------------------------------
    // isFailure_impl(Object): boolean
    // -------------------------------------------------------------------------
    @JvmStatic
    fun isFailure(value: Any?): Boolean = isFailureObject(value)

    // -------------------------------------------------------------------------
    // exceptionOrNull_impl(Object): Throwable?
    // -------------------------------------------------------------------------
    @JvmStatic
    fun exceptionOrNull(value: Any?): Throwable? = failureExceptionOf(value)

    // -------------------------------------------------------------------------
    // getOrNull_impl / getValue_impl(Object): Object?
    //   Returns the success value, or null if this is a failure.
    // -------------------------------------------------------------------------
    @JvmStatic
    fun getOrNull(value: Any?): Any? = if (isFailureObject(value)) null else value

    // -------------------------------------------------------------------------
    // throwOnFailure_impl(Object): void
    //   Throws the wrapped exception if this is a failure result.
    // -------------------------------------------------------------------------
    @JvmStatic
    fun throwOnFailure(value: Any?) {
        val ex = failureExceptionOf(value)
        if (ex != null) throw ex
    }

    // -------------------------------------------------------------------------
    // box-impl / box_impl(Object): Object
    //   In value-class erased bytecode "boxing" just means wrapping in
    //   kotlin.Result.  We use Result.success / Result.failure via the stdlib.
    // -------------------------------------------------------------------------
    @JvmStatic
    fun box(value: Any?): Any? {
        return if (isFailureObject(value)) {
            val ex = failureExceptionOf(value) ?: Exception("Unknown failure")
            Result.failure<Any?>(ex)
        } else {
            Result.success(value)
        }
    }

    // -------------------------------------------------------------------------
    // unbox-impl / unbox_impl(Object): Object
    //   Unwrap a kotlin.Result or a raw value back to the underlying object.
    // -------------------------------------------------------------------------
    @JvmStatic
    fun unbox(value: Any?): Any? {
        return when (value) {
            is Result<*> -> {
                if (value.isFailure) {
                    // Return the Failure wrapper so isFailure_impl still works downstream
                    val ex = value.exceptionOrNull() ?: Exception("Unknown failure")
                    createFailure(ex)
                } else {
                    value.getOrNull()
                }
            }
            else -> value
        }
    }

    // -------------------------------------------------------------------------
    // toString_impl(Object): String
    // -------------------------------------------------------------------------
    @JvmStatic
    fun toStringImpl(value: Any?): String {
        return if (isFailureObject(value)) {
            "Failure(${failureExceptionOf(value)})"
        } else {
            "Success($value)"
        }
    }

    // -------------------------------------------------------------------------
    // hashCode_impl(Object): int
    // -------------------------------------------------------------------------
    @JvmStatic
    fun hashCodeImpl(value: Any?): Int = value?.hashCode() ?: 0

    // -------------------------------------------------------------------------
    // equals_impl(Object, Object): boolean
    // -------------------------------------------------------------------------
    @JvmStatic
    fun equalsImpl(a: Any?, b: Any?): Boolean = a == b

    // -------------------------------------------------------------------------
    // Helper: create a kotlin.Result.Failure instance via reflection,
    // falling back to a simple wrapper if the inner class is unavailable.
    // -------------------------------------------------------------------------
    private fun createFailure(ex: Throwable): Any? {
        return try {
            val fc = failureClass ?: return FailureFallback(ex)
            val ctor = fc.getDeclaredConstructor(Throwable::class.java)
            ctor.isAccessible = true
            ctor.newInstance(ex)
        } catch (_: Throwable) {
            FailureFallback(ex)
        }
    }

    /** Fallback when kotlin.Result\$Failure is not accessible via reflection. */
    private class FailureFallback(val exception: Throwable)
}
