package android.util

open class LruCache<K : Any, V : Any>(private var maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    
    private var size = 0
    private var putCount = 0
    private var createCount = 0
    private var evictionCount = 0
    private var hitCount = 0
    private var missCount = 0

    init {
        require(maxSize > 0) { "maxSize <= 0" }
    }

    @Synchronized
    fun resize(maxSize: Int) {
        require(maxSize > 0) { "maxSize <= 0" }
        this.maxSize = maxSize
        trimToSize(maxSize)
    }

    @Synchronized
    operator fun get(key: K): V? {
        val mapValue = map[key]
        if (mapValue != null) {
            hitCount++
            return mapValue
        }
        missCount++

        val createdValue = create(key) ?: return null

        synchronized(this) {
            createCount++
            val mapValueAfterCreate = map.put(key, createdValue)

            if (mapValueAfterCreate != null) {
                map.put(key, mapValueAfterCreate)
            } else {
                size += safeSizeOf(key, createdValue)
            }
        }

        trimToSize(maxSize)
        return createdValue
    }

    fun put(key: K, value: V): V? {
        val previous: V?
        synchronized(this) {
            putCount++
            size += safeSizeOf(key, value)
            previous = map.put(key, value)
            if (previous != null) {
                size -= safeSizeOf(key, previous)
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, value)
        }

        trimToSize(maxSize)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            var key: K
            var value: V
            synchronized(this) {
                if (size < 0 || (map.isEmpty() && size != 0)) {
                    throw IllegalStateException(javaClass.name + ".sizeOf() is reporting inconsistent results!")
                }

                if (size <= maxSize || map.isEmpty()) {
                    return
                }

                val toEvict = map.entries.iterator().next()
                key = toEvict.key
                value = toEvict.value
                map.remove(key)
                size -= safeSizeOf(key, value)
                evictionCount++
            }

            entryRemoved(true, key, value, null)
        }
    }

    fun remove(key: K): V? {
        val previous: V?
        synchronized(this) {
            previous = map.remove(key)
            if (previous != null) {
                size -= safeSizeOf(key, previous)
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, null)
        }

        return previous
    }

    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    protected open fun create(key: K): V? = null

    private fun safeSizeOf(key: K, value: V): Int {
        val result = sizeOf(key, value)
        require(result >= 0) { "Negative size: $key=$value" }
        return result
    }

    protected open fun sizeOf(key: K, value: V): Int = 1

    fun evictAll() {
        trimToSize(-1)
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun maxSize(): Int = maxSize

    @Synchronized
    fun hitCount(): Int = hitCount

    @Synchronized
    fun missCount(): Int = missCount

    @Synchronized
    fun createCount(): Int = createCount

    @Synchronized
    fun putCount(): Int = putCount

    @Synchronized
    fun evictionCount(): Int = evictionCount

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(map)

    @Synchronized
    override fun toString(): String {
        val accesses = hitCount + missCount
        val hitPercent = if (accesses != 0) 100 * hitCount / accesses else 0
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,excludes=%d,hitRate=%d%%]",
                maxSize, hitCount, missCount, evictionCount, hitPercent)
    }
}
