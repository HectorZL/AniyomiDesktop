# Diseño: Optimización de Rendimiento e Integración

## Introducción

Este documento describe la arquitectura de optimización de rendimiento para AniYomi. Incluye sistema de caching multinivel, validación de links de streaming, manejo robusto de errores de fuentes, y prefetching en background. El objetivo es reducir tiempos de carga a < 2s para búsquedas y proporcionar experiencia fluida incluso con fuentes problemáticas.

## Arquitectura General

```
┌──────────────────────────────────────┐
│       Video Source Provider           │
│  (Extensión de fuente externa)       │
└──────────┬───────────────────────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
┌──────────┐  ┌──────────────────┐
│ Source   │  │ Circuit Breaker  │
│ Requests │  │ + Retry Policy   │
└────┬─────┘  └────────┬─────────┘
     │                 │
     └────────┬────────┘
              ▼
     ┌────────────────────┐
     │ Cache Layer        │
     │ - Memory Cache     │
     │ - Disk Cache       │
     │ - Remote Cache     │
     └────────┬───────────┘
              │
     ┌────────┴──────────┐
     ▼                   ▼
┌──────────────┐  ┌──────────────────┐
│ Link Resolver│  │ Image Optimizer  │
│ (Validar)    │  │ (Compress)       │
└──────────────┘  └──────────────────┘
```

## Modelos de Datos

### Caching Models

```kotlin
data class CacheEntry<T>(
    val key: String,
    val value: T,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val version: Int = 1,
    val metadata: Map<String, String> = emptyMap()
)

data class CacheStats(
    val totalSize: Long,
    val hitRate: Float,
    val missRate: Float,
    val averageLoadTimeMs: Long,
    val evictedCount: Long
)

data class PerformanceMetric(
    val operation: String,           // "catalog_search", "episode_fetch", etc
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceId: String,
    val success: Boolean,
    val cacheHit: Boolean,
    val errorMessage: String? = null
)

data class SourceHealth(
    val sourceId: String,
    val successRate: Float,          // 0.0 - 1.0
    val averageResponseTimeMs: Long,
    val consecutiveFailures: Int,
    val lastCheckTime: Long,
    val status: SourceStatus = SourceStatus.HEALTHY
)

enum class SourceStatus {
    HEALTHY, DEGRADED, UNRELIABLE, DISABLED, RECOVERING
}

data class StreamingLinkValidation(
    val url: String,
    val isValid: Boolean,
    val statusCode: Int?,
    val validatedAt: Long = System.currentTimeMillis(),
    val ttl: Long = 24 * 60 * 60 * 1000  // 24 horas
)

@Entity(tableName = "dead_links")
data class DeadLink(
    @PrimaryKey
    val url: String,
    
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000
)
```

### Image Optimization Model

```kotlin
data class ImageCache(
    val originalUrl: String,
    val thumbnailPath: String?,      // Cached thumbnail < 50KB
    val fullResPath: String?,         // Cached full-res
    val format: ImageFormat = ImageFormat.WEBP,
    val compressionRatio: Float = 0.0f,
    val thumbnailSize: Long = 0,
    val fullResSize: Long = 0
)

enum class ImageFormat {
    WEBP, AVIF, JPEG, PNG
}
```

## Componentes Detallados

### 1. MultiLevel Cache System

```kotlin
interface CacheLayer<T> {
    suspend fun get(key: String): T?
    suspend fun put(key: String, value: T, ttlMs: Long? = null)
    suspend fun evict(key: String)
    suspend fun clear()
    fun getStats(): CacheStats
}

class MemoryCache<T> : CacheLayer<T> {
    private val cache = LinkedHashMap<String, CacheEntry<T>>(
        initialCapacity = 16,
        loadFactor = 0.75f,
        accessOrder = true
    )
    private val maxSizeBytes = 50 * 1024 * 1024  // 50MB
    private var currentSizeBytes = 0L
    
    override suspend fun get(key: String): T? {
        val entry = cache[key]
        
        if (entry != null && !isExpired(entry)) {
            return entry.value
        }
        
        cache.remove(key)
        return null
    }
    
    override suspend fun put(key: String, value: T, ttlMs: Long?) {
        val entry = CacheEntry(
            key = key,
            value = value,
            expiresAt = if (ttlMs != null) {
                System.currentTimeMillis() + ttlMs
            } else null
        )
        
        cache[key] = entry
        
        // LRU Eviction si necesario
        while (currentSizeBytes > maxSizeBytes && cache.isNotEmpty()) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
            currentSizeBytes -= estimateSize(cache[oldest])
        }
    }
    
    override suspend fun evict(key: String) {
        cache.remove(key)
    }
    
    override fun getStats(): CacheStats {
        return CacheStats(
            totalSize = currentSizeBytes,
            hitRate = calculateHitRate(),
            missRate = 1.0f - calculateHitRate(),
            averageLoadTimeMs = 0L,
            evictedCount = 0L
        )
    }
    
    private fun isExpired(entry: CacheEntry<T>): Boolean {
        return entry.expiresAt?.let { 
            System.currentTimeMillis() > it 
        } ?: false
    }
}

class DiskCache<T> : CacheLayer<T> {
    private val maxSizeBytes = 500 * 1024 * 1024  // 500MB
    private val cacheDir: File
    private val dao: DiskCacheDao
    
    override suspend fun get(key: String): T? {
        val cached = dao.getCacheEntry(key)
        
        if (cached != null && !isExpired(cached.expiresAt)) {
            val file = File(cacheDir, key)
            if (file.exists()) {
                return deserializeFromFile(file)
            }
        }
        
        dao.removeCacheEntry(key)
        return null
    }
    
    override suspend fun put(key: String, value: T, ttlMs: Long?) {
        val file = File(cacheDir, key)
        serializeToFile(file, value)
        
        val expiresAt = if (ttlMs != null) {
            System.currentTimeMillis() + ttlMs
        } else null
        
        dao.insertCacheEntry(key, expiresAt)
        
        // Cleanup if necessary
        if (calculateTotalSize() > maxSizeBytes) {
            evictLRU()
        }
    }
    
    private fun evictLRU() {
        // Remove oldest files by access time
        val entries = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        for (file in entries) {
            if (calculateTotalSize() <= maxSizeBytes) break
            file.delete()
        }
    }
}

class CacheChain<T>(
    private val memoryCache: MemoryCache<T>,
    private val diskCache: DiskCache<T>
) : CacheLayer<T> {
    
    override suspend fun get(key: String): T? {
        // Intentar Memory primero (< 10ms)
        var result = memoryCache.get(key)
        if (result != null) return result
        
        // Luego Disk (< 100ms)
        result = diskCache.get(key)
        if (result != null) {
            memoryCache.put(key, result)  // Re-cache en memoria
        }
        
        return result
    }
    
    override suspend fun put(key: String, value: T, ttlMs: Long?) {
        memoryCache.put(key, value, ttlMs)
        diskCache.put(key, value, ttlMs)
    }
}
```

### 2. Source Request Handler con Circuit Breaker

```kotlin
class SourceRequestHandler(
    private val sourceProvider: VideoSourceProvider,
    private val healthMonitor: SourceHealthMonitor,
    private val cache: CacheLayer<Any>,
    private val metricsCollector: PerformanceMetricsCollector
) {
    
    suspend inline fun <reified T> executeWithRetry(
        sourceId: String,
        cacheKey: String,
        crossinline operation: suspend () -> T,
        timeoutMs: Long = 3000
    ): Result<T> {
        val startTime = System.currentTimeMillis()
        
        // Verificar health del source
        val health = healthMonitor.getHealth(sourceId)
        if (health.status == SourceStatus.DISABLED) {
            return getFromCacheOrFail(cacheKey)
        }
        
        // Implementar Circuit Breaker
        if (health.consecutiveFailures >= 5) {
            healthMonitor.markDisabled(sourceId, 30 * 60 * 1000) // 30 min
            return getFromCacheOrFail(cacheKey)
        }
        
        return try {
            // Timeout en request
            val result = withTimeoutOrNull(timeoutMs) {
                operation()
            } ?: throw TimeoutException("Request timeout after ${timeoutMs}ms")
            
            // Cache hit
            cache.put(cacheKey, result, ttlMs = 60 * 60 * 1000)
            
            // Registrar éxito
            val durationMs = System.currentTimeMillis() - startTime
            metricsCollector.recordMetric(
                PerformanceMetric(
                    operation = "source_request",
                    durationMs = durationMs,
                    sourceId = sourceId,
                    success = true,
                    cacheHit = false
                )
            )
            
            healthMonitor.recordSuccess(sourceId, durationMs.toInt())
            Result.success(result)
            
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            
            // Registrar fallo
            metricsCollector.recordMetric(
                PerformanceMetric(
                    operation = "source_request",
                    durationMs = durationMs,
                    sourceId = sourceId,
                    success = false,
                    cacheHit = false,
                    errorMessage = e.message
                )
            )
            
            healthMonitor.recordFailure(sourceId)
            
            // Intentar cache como fallback
            getFromCacheOrFail<T>(cacheKey)
        }
    }
    
    private suspend inline fun <reified T> getFromCacheOrFail(
        cacheKey: String
    ): Result<T> {
        val cached = cache.get(cacheKey) as? T
        return if (cached != null) {
            Result.success(cached)
        } else {
            Result.failure(SourceUnavailableException("Source unavailable and no cache"))
        }
    }
}
```

### 3. Link Resolver y Validador

```kotlin
class StreamingLinkResolver(
    private val dao: StreamingLinkDao,
    private val deadLinkDao: DeadLinkDao,
    private val httpClient: OkHttpClient
) {
    
    suspend fun resolveAndValidate(
        episodeId: String,
        sourceId: String,
        timeoutMs: Long = 2000
    ): StreamingLinkValidation? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                // Obtener links candidatos
                val links = sourceProvider.getStreamingLinks(episodeId)
                    ?.filterNot { isDeadLink(it) }
                    ?: return@withTimeoutOrNull null
                
                // Validar el primero que funcione
                for (link in links) {
                    val validation = validateLink(link)
                    if (validation.isValid) {
                        return@withTimeoutOrNull validation
                    }
                }
                
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun validateLink(url: String): StreamingLinkValidation {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()  // HEAD request solo
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            StreamingLinkValidation(
                url = url,
                isValid = response.code in 200..299,
                statusCode = response.code
            )
        } catch (e: Exception) {
            StreamingLinkValidation(
                url = url,
                isValid = false,
                statusCode = null
            )
        }
    }
    
    private suspend fun isDeadLink(url: String): Boolean {
        val deadLink = deadLinkDao.getDeadLink(url)
        return deadLink?.let { System.currentTimeMillis() < it.expiresAt } ?: false
    }
    
    suspend fun markAsDead(url: String) {
        deadLinkDao.insertDeadLink(
            DeadLink(
                url = url,
                expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000
            )
        )
    }
}
```

### 4. Image Optimizer

```kotlin
class ImageOptimizer(
    private val cacheDir: File
) {
    
    suspend fun optimizeAndCache(
        imageUrl: String,
        isThumbnail: Boolean = false
    ): ImageCache? {
        return try {
            val targetSize = if (isThumbnail) 50000L else Long.MAX_VALUE
            val targetFormat = ImageFormat.WEBP
            
            // Descargar imagen
            val bitmap = downloadImage(imageUrl)
            
            // Comprimir a WEBP
            val compressedFile = File(cacheDir, 
                "${imageUrl.hashCode()}.${targetFormat.name.lowercase()}")
            
            val outputStream = FileOutputStream(compressedFile)
            bitmap.compress(
                if (isThumbnail) Bitmap.CompressFormat.WEBP else Bitmap.CompressFormat.WEBP,
                if (isThumbnail) 60 else 90,  // quality
                outputStream
            )
            outputStream.close()
            
            val originalSize = if (isThumbnail) 100000L else 1000000L
            val compressedSize = compressedFile.length()
            val ratio = compressedSize.toFloat() / originalSize
            
            ImageCache(
                originalUrl = imageUrl,
                thumbnailPath = if (isThumbnail) compressedFile.absolutePath else null,
                fullResPath = if (!isThumbnail) compressedFile.absolutePath else null,
                format = targetFormat,
                compressionRatio = ratio,
                thumbnailSize = if (isThumbnail) compressedSize else 0,
                fullResSize = if (!isThumbnail) compressedSize else 0
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun downloadImage(url: String): Bitmap {
        return withContext(Dispatchers.IO) {
            val urlConnection = URL(url).openConnection() as HttpURLConnection
            urlConnection.doInput = true
            urlConnection.connect()
            
            val inputStream = urlConnection.inputStream
            BitmapFactory.decodeStream(inputStream)
        }
    }
}
```

### 5. Background Prefetcher

```kotlin
class BackgroundPrefetcher(
    private val sourceHandler: SourceRequestHandler,
    private val imageOptimizer: ImageOptimizer,
    private val workManager: WorkManager
) {
    
    fun schedulePrefetch(
        watchlistEpisodes: List<Episode>,
        isCharging: Boolean,
        isWiFi: Boolean
    ) {
        if (!isCharging || !isWiFi) return
        
        val prefetchWork = OneTimeWorkRequestBuilder<PrefetchWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresDeviceIdle(true)
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.METERED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.MINUTES
            )
            .build()
        
        workManager.enqueueUniqueWork(
            "prefetch_work",
            ExistingWorkPolicy.KEEP,
            prefetchWork
        )
    }
}

class PrefetchWorker(
    context: Context,
    params: WorkerParameters,
    private val sourceHandler: SourceRequestHandler
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            val watchlist = getWatchlist()
            
            for (episode in watchlist.take(5)) {
                // Prefetch siguiente episodio
                sourceHandler.executeWithRetry(
                    sourceId = episode.sourceId,
                    cacheKey = "episode_${episode.id}",
                    operation = {
                        sourceProvider.getEpisodeDetails(episode.id)
                    }
                )
                
                delay(2000)  // No consumir > 2MB/min
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
```

## Ciclos de Vida de Request

### Búsqueda de Catálogo (< 2s)

```
1. User iniciates search
2. Check Memory Cache (< 10ms)
3. If miss → Check Disk Cache (< 100ms)
4. If miss → Check Circuit Breaker status
5. If healthy → executeWithRetry(timeout=3000ms):
   - Make Source request
   - On success: Cache in Memory + Disk
   - On timeout: Return cached if exists
   - On failure: Record metric, update health
```

### Validación de Link (< 2s)

```
1. Episode about to play
2. Check if URL in Dead Links list
3. If not dead:
   - HEAD request with 2000ms timeout
   - If 200-299: Valid
   - If 404/403: Mark as dead, try alternative
4. If all fail: Show error, suggest alternatives
```

## Manejo de Errores

| Error | Estrategia | Resultado |
|-------|-----------|-----------|
| Timeout Source (> 3s) | Retornar cache + mostrar "limited connection" | Usuario ve datos viejos pero funciona |
| Source returna 5xx | Circuit Breaker, disable 30 min | Future requests usan cache directo |
| Link broken (404) | Add to dead_links, try next | Si todas fallan: mostrar "unavailable" |
| Corrupted data | Validar estructura, descartar | Log error, usar cache si existe |
| Storage lleno | LRU eviction automático | Espacio liberado automáticamente |



## Consideraciones de Rendimiento

### Optimizaciones

1. **Parallel Source Queries:** Multiple sources se querean en paralelo, retornar el primero que responda
2. **Lazy Image Loading:** Thumbnails primero, full-res on-demand
3. **Virtual Scrolling:** Solo renderizar episodios visibles
4. **Compression:** WEBP/AVIF reduce storage 30-50%
5. **Metrics Tracking:** Identificar bottlenecks automáticamente

### Límites

- Catálogo búsqueda: < 2000ms
- Carga lista episodios: < 1500ms
- Validación de link: < 2000ms
- Memory Cache: 50MB
- Disk Cache: 500MB
- Dead Links TTL: 24 horas
- Background prefetch: < 2MB/min

## Correctness Properties

*Las propiedades especifican comportamientos críticos del sistema de optimización.*

### Property 1: Búsqueda Responde dentro del Timeout
*Para cualquier búsqueda de catálogo, el sistema debe retornar resultados (cache o new) dentro de 2000ms*

**Validates: Requirements 1.1, 1.6**

### Property 2: Cache Devuelve Datos Válidos
*Para cualquier entrada en cache, los datos retornados deben ser idénticos a los originales del source*

**Validates: Requirements 3.4, 3.7**

### Property 3: Links Inválidos se Marcan
*Cuando validación de link falla (404/403), debe agregarse a dead_links con TTL de 24 horas*

**Validates: Requirements 4.2, 4.7**

### Property 4: Circuit Breaker Protege de Cascada de Fallos
*Cuando un source tiene 5 fallos consecutivos, debe deshabilitarse por 30 minutos*

**Validates: Requirements 5.1, 5.5**

### Property 5: Source Fallido Retorna Cache
*Cuando un source falla, el sistema debe retornar datos en cache si existen*

**Validates: Requirements 5.6, 5.7**

### Property 6: Images se Comprimen Correctamente
*Cuando se cachea una imagen, debe comprimirse en WEBP/AVIF con ratio >= 0.3 (30% del original)*

**Validates: Requirements 6.2, 6.4**

### Property 7: Storage Respeta Límites
*Cuando Memory Cache excede 50MB o Disk Cache excede 500MB, deben evictarse entradas automáticamente*

**Validates: Requirements 3.2, 3.3**

### Property 8: Multi-Source Paralleliza
*Cuando múltiples sources son disponibles, requests deben ejecutarse en paralelo, retornando el primero que responda*

**Validates: Requirements 5.3**

### Property 9: Métricas Registran Correctamente
*Cada operación debe registrar su duración, estado de cache, y source en PerformanceMetric*

**Validates: Requirements 8.1, 8.2**

### Property 10: Dead Links Expiran
*Un Dead Link debe removerse automáticamente después de 24 horas de su creación*

**Validates: Requirements 4.7**

