# Diseño: Persistencia de Progreso de Reproducción

## Introducción

Este documento describe la arquitectura técnica para implementar un sistema robusto de persistencia de progreso de reproducción en AniYomi. El sistema debe capturar y restaurar posiciones de video de forma confiable, soportando múltiples escenarios de cierre/reapertura de aplicación y dispositivos.

## Arquitectura General

### Componentes Principales

```
┌─────────────────────────────────────────────────┐
│           Video Player UI (mpv-android)          │
└──────────────────┬──────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────┐
│    PlaybackProgressManager (Controller)          │
│  - Escucha cambios de posición                  │
│  - Gestiona ciclo de sincronización             │
│  - Coordina resume dialog                       │
└──────────────────┬──────────────────────────────┘
                   │
        ┌──────────┴──────────┐
        ▼                     ▼
┌──────────────────┐  ┌──────────────────┐
│ PlaybackStorage  │  │ SessionValidator │
│ (Repository)     │  │  (Domain Logic)  │
│ - Room DB        │  │ - Validación     │
│ - Transacciones  │  │ - Recuperación   │
└──────┬───────────┘  └────────┬─────────┘
       │                       │
       └───────────┬───────────┘
                   ▼
        ┌────────────────────┐
        │ Playback_Position  │
        │  - episodeId       │
        │  - position (ms)   │
        │  - duration        │
        │  - timestamp       │
        │  - deviceId        │
        │  - status          │
        └────────────────────┘
```

### Flujos Principales

**1. Guardado de Posición:**
```
Video Player emite evento → Acumular cambios (debounce 5s)
→ Validar posición → Escribir en Room DB (atomic write)
→ En caso de error: reintentar o encolar en memoria
```

**2. Restauración de Posición:**
```
Usuario abre episodio → Cargar desde DB (< 500ms)
→ Validar contra duración → Si > Resume_Threshold (10s)
→ Mostrar Resume Dialog → Usuario selecciona opción
```

**3. Manejo de Cierre de Aplicación:**
```
Acción de cierre (back/app switcher) → onPause() → Flush pending writes
→ Completar transacciones → Liberar recursos
```

## Modelos de Datos

### Entidad PlaybackProgress (Room Entity)

```kotlin
@Entity(
    tableName = "playback_progress",
    indices = [
        Index("episodeId", "deviceId", unique = false),
        Index("lastUpdateTime", unique = false)
    ]
)
data class PlaybackProgress(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "episodeId")
    val episodeId: String,
    
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    
    @ColumnInfo(name = "last_update_time")
    val lastUpdateTime: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    
    @ColumnInfo(name = "status")
    val status: PlaybackStatus = PlaybackStatus.IN_PROGRESS,
    
    @ColumnInfo(name = "version")
    val version: Int = 1 // Para resolver conflictos multi-dispositivo
)

enum class PlaybackStatus {
    IN_PROGRESS,      // Reproducción activa
    PAUSED,           // En pausa
    COMPLETED,        // Episodio completado
    EXPIRED,          // Sesión expirada (> 2 minutos inactivo)
    CORRUPTED         // Datos corruptos detectados
}
```

### DTO de Transferencia

```kotlin
data class PlaybackProgressDTO(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdateTime: Long,
    val deviceId: String
)

data class ResumeDialogState(
    val showDialog: Boolean,
    val episodeId: String,
    val savedPosition: Long,
    val duration: Long,
    val lastUpdated: Long
)
```

## Componentes Detallados

### 1. PlaybackProgressManager (Controller)

**Responsabilidades:**
- Escuchar eventos de cambio de posición del reproductor
- Implementar debouncing (5 segundos)
- Coordinar con Storage Layer
- Gestionar Resume Dialog

**Pseudocódigo:**

```kotlin
class PlaybackProgressManager(
    private val storage: PlaybackProgressRepository,
    private val validator: PlaybackSessionValidator,
    private val deviceProvider: DeviceProvider
) {
    private var pendingUpdates = mutableListOf<PlaybackProgressDTO>()
    private var debounceJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Evento del reproductor
    fun onPlaybackPositionChanged(episodeId: String, positionMs: Long, durationMs: Long) {
        // Validar antes de procesar
        if (!validator.isValidPosition(positionMs, durationMs)) {
            logError("Invalid position: $positionMs / $durationMs")
            return
        }
        
        // Cancelar job anterior (debounce)
        debounceJob?.cancel()
        
        // Crear nuevo trabajo con delay de 5 segundos
        debounceJob = scope.launch {
            delay(5000)
            
            val progress = PlaybackProgressDTO(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                lastUpdateTime = System.currentTimeMillis(),
                deviceId = deviceProvider.getDeviceId()
            )
            
            try {
                // Intentar escribir en storage
                storage.saveProgress(progress)
            } catch (e: Exception) {
                // Encolar en memoria para reintentar después
                handleWriteFailure(progress, e)
            }
        }
    }
    
    // Al pausar
    fun onPlaybackPaused(episodeId: String, positionMs: Long, durationMs: Long) {
        debounceJob?.cancel()
        
        val progress = PlaybackProgressDTO(
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            lastUpdateTime = System.currentTimeMillis(),
            deviceId = deviceProvider.getDeviceId()
        )
        
        scope.launch {
            try {
                storage.saveProgress(progress)
            } catch (e: Exception) {
                handleWriteFailure(progress, e)
            }
        }
    }
    
    // Recuperar posición guardada
    suspend fun loadPlaybackProgress(episodeId: String): ResumeDialogState? {
        return try {
            val deviceId = deviceProvider.getDeviceId()
            val progress = storage.getProgressForEpisode(
                episodeId = episodeId,
                deviceId = deviceId
            )
            
            if (progress == null) {
                return null
            }
            
            // Validar que no esté expirado (> 2 minutos)
            if (validator.isSessionExpired(progress.lastUpdateTime)) {
                storage.markAsExpired(progress.id)
                return null
            }
            
            // Validar que la posición sea válida
            if (!validator.isValidProgress(progress)) {
                storage.markAsCorrupted(progress.id)
                return null
            }
            
            ResumeDialogState(
                showDialog = progress.positionMs > 10000, // Resume_Threshold
                episodeId = episodeId,
                savedPosition = progress.positionMs,
                duration = progress.durationMs,
                lastUpdated = progress.lastUpdateTime
            )
        } catch (e: Exception) {
            logError("Failed to load progress: ${e.message}")
            null
        }
    }
    
    private fun handleWriteFailure(progress: PlaybackProgressDTO, error: Exception) {
        pendingUpdates.add(progress)
        
        // Contar fallos consecutivos
        val consecutiveFailures = pendingUpdates.size
        if (consecutiveFailures >= 3 && checkTimeWindow(30000)) { // 3 fallos en 30s
            notifyUser("Problemas guardando progreso - reintentando...")
        }
        
        // Reintentar cuando el storage esté disponible
        scope.launch {
            delay(5000)
            flushPendingUpdates()
        }
    }
    
    private suspend fun flushPendingUpdates() {
        val updates = pendingUpdates.toList()
        pendingUpdates.clear()
        
        for (update in updates) {
            try {
                storage.saveProgress(update)
            } catch (e: Exception) {
                pendingUpdates.add(update) // Re-encolar
            }
        }
    }
    
    fun onAppTerminating() {
        // Flush all pending writes
        runBlocking {
            flushPendingUpdates()
            debounceJob?.cancel()
        }
    }
}
```

### 2. PlaybackProgressRepository (Data Layer)

**Responsabilidades:**
- Acceso a Room Database
- Operaciones CRUD con transacciones
- Invalidación y cleanup de datos

```kotlin
@Dao
interface PlaybackProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: PlaybackProgress)
    
    @Query("""
        SELECT * FROM playback_progress 
        WHERE episodeId = :episodeId 
        AND deviceId = :deviceId
        ORDER BY last_update_time DESC
        LIMIT 1
    """)
    suspend fun getProgressForEpisode(episodeId: String, deviceId: String): PlaybackProgress?
    
    @Query("""
        DELETE FROM playback_progress 
        WHERE episodeId = :episodeId 
        AND status = 'COMPLETED'
    """)
    suspend fun deleteCompletedProgress(episodeId: String)
    
    @Transaction
    suspend fun updateProgressAtomic(progress: PlaybackProgress) {
        deleteProgress(progress.id)
        insertProgress(progress)
    }
}

class PlaybackProgressRepository(private val dao: PlaybackProgressDao) {
    suspend fun saveProgress(dto: PlaybackProgressDTO) {
        val entity = PlaybackProgress(
            episodeId = dto.episodeId,
            positionMs = dto.positionMs,
            durationMs = dto.durationMs,
            lastUpdateTime = dto.lastUpdateTime,
            deviceId = dto.deviceId
        )
        
        // Usar transacción atómica
        try {
            dao.updateProgressAtomic(entity)
        } catch (e: Exception) {
            throw PlaybackStorageException("Failed to save progress", e)
        }
    }
    
    suspend fun getProgressForEpisode(episodeId: String, deviceId: String): PlaybackProgress? {
        return dao.getProgressForEpisode(episodeId, deviceId)
    }
    
    suspend fun markAsCompleted(episodeId: String, deviceId: String) {
        // Buscar y marcar como completado
        val progress = dao.getProgressForEpisode(episodeId, deviceId)
        if (progress != null) {
            val completed = progress.copy(status = PlaybackStatus.COMPLETED)
            dao.updateProgressAtomic(completed)
        }
    }
    
    suspend fun markAsExpired(id: String) {
        // Implementar update con status EXPIRED
    }
    
    suspend fun markAsCorrupted(id: String) {
        // Implementar update con status CORRUPTED
    }
}
```

### 3. PlaybackSessionValidator (Domain Logic)

```kotlin
class PlaybackSessionValidator(
    private val deviceProvider: DeviceProvider
) {
    fun isValidPosition(positionMs: Long, durationMs: Long): Boolean {
        return positionMs >= 0 && positionMs <= durationMs && durationMs > 0
    }
    
    fun isSessionExpired(lastUpdateTime: Long): Boolean {
        val now = System.currentTimeMillis()
        val ageMs = now - lastUpdateTime
        return ageMs > 2 * 60 * 1000 // 2 minutos
    }
    
    fun isValidProgress(progress: PlaybackProgress): Boolean {
        return isValidPosition(progress.positionMs, progress.durationMs) &&
               progress.status != PlaybackStatus.CORRUPTED &&
               !isSessionExpired(progress.lastUpdateTime)
    }
    
    fun shouldShowResumeDialog(progress: PlaybackProgress): Boolean {
        return progress.positionMs > 10 * 1000 && // Resume_Threshold 10s
               progress.status == PlaybackStatus.IN_PROGRESS
    }
    
    fun adjustPositionIfNeeded(position: Long, duration: Long): Long {
        // Si la posición excede duración, ajustar al 90%
        return if (position > duration) {
            (duration * 0.90).toLong()
        } else {
            position
        }
    }
}
```

### 4. Resume Dialog Component (UI)

```kotlin
@Composable
fun PlaybackResumeDialog(
    state: ResumeDialogState,
    onResume: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!state.showDialog) return
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Continuar reproducción") },
        text = {
            val resumeTime = formatTime(state.savedPosition)
            Text("¿Continuar desde $resumeTime?")
        },
        confirmButton = {
            Button(onClick = onResume) {
                Text("Continuar")
            }
        },
        dismissButton = {
            Button(onClick = onStartFromBeginning) {
                Text("Desde el inicio")
            }
        }
    )
}

// En ViewModel o Presenter
class PlayerViewModel(
    private val progressManager: PlaybackProgressManager,
    private val episodeId: String
) : ViewModel() {
    private val _resumeState = MutableStateFlow<ResumeDialogState?>(null)
    val resumeState: StateFlow<ResumeDialogState?> = _resumeState.asStateFlow()
    
    fun loadResumeState() {
        viewModelScope.launch {
            val state = progressManager.loadPlaybackProgress(episodeId)
            _resumeState.value = state
        }
    }
    
    fun onResumeSelected(position: Long) {
        // Buscar en reproductor y hacer seek
        seekTo(position)
        _resumeState.value = null
    }
    
    fun onStartFromBeginning() {
        seekTo(0)
        _resumeState.value = null
    }
}
```

## Ciclos de Vida

### Guardado Automático
```
T=0s: Reproducción iniciada
T=5s: Primer guardado (debounce)
T=10s: Posición actualizada, nuevo debounce
T=15s: Segundo guardado
T=20s: Posición actualizada, nuevo debounce
...
T=N (pausa): Guardado inmediato
T=N+1: Aplicación cerrada, flush pending
```

### Recuperación Multi-Dispositivo
```
Dispositivo A: Guarda posición 10:00
Dispositivo B: Abre episodio → Carga posición de B (0:00)
Dispositivo A: Cierra app
Dispositivo B: Continúa desde 0:00 (no usa posición de A)
```

## Manejo de Errores y Recuperación

### Escenarios de Fallo

| Escenario | Manejo | Resultado |
|-----------|--------|-----------|
| Fallo de escritura (3 intentos en 30s) | Mostrar notificación, encolar en memoria | Usuario ve advertencia, progreso se guarda cuando storage recupera |
| Storage no disponible | Usar cola en memoria (buffer) | Datos se pierden si app termina sin flush |
| Datos corruptos (posición > duración) | Marcar como CORRUPTED, descartar | Usuario comienza desde 0 |
| Sesión expirada (> 2 min inactivo) | Marcar como EXPIRED, descartar | Usuario comienza desde 0 |
| Device reboot | Transacciones atómicas persisten | Storage recupera datos tras reinicio |

### Estrategia de Reintentos

```
Intento 1: Inmediato
Intento 2: Después de 5s (si storage falla)
Intento 3: Después de 10s (exponential backoff)

Si 3 fallos consecutivos en 30s:
  → Mostrar notificación al usuario
  → Continuar encolando en memoria
  → Reintentar cuando storage esté disponible
```

## Consideraciones de Rendimiento

### Optimizaciones

1. **Índices Database:** episodeId + deviceId para queries rápidas
2. **Debouncing:** Reduce writes de O(n) a O(1) durante reproducción
3. **Lazy Loading:** Resume dialog estado se carga asyncrónicamente
4. **In-Memory Buffer:** Minimiza acceso a disk durante escrituras fallidas

### Límites

- Máximo 500 registros de progreso activos
- Máximo 100 registros de progreso completados (cleanup automático)
- Máximo 1000ms latencia en recuperación de posición

## Verificación de Corrección

Este diseño se verifica a través de propiedades formales que validan:

1. **Atomicidad de Escrituras:** Transacciones Room garantizan writes atómicas
2. **Consistencia Multi-dispositivo:** Campo deviceId aísla datos por dispositivo
3. **Recuperación ante Fallos:** Validación de sesión previene datos corruptos
4. **Sincronización Periódica:** Debouncing con 5s garantiza captura reciente

## Correctness Properties

*Las propiedades son características que deben ser ciertas en todo momento durante la ejecución del sistema.*

### Property 1: Posición Guardada es Válida
*Para cualquier episodio reproducido, la posición guardada debe estar entre 0 y la duración total del video*

**Validates: Requirements 1.4, 1.5**

### Property 2: Guardado Periódico Durante Reproducción
*Para cualquier reproducción activa de duración mayor a 5 segundos, la posición debe persistirse al menos una vez*

**Validates: Requirements 1.2**

### Property 3: Guardado Inmediato en Pausa
*Cuando el usuario pausa la reproducción, la posición en ese momento debe guardarse dentro de 1 segundo*

**Validates: Requirements 1.3**

### Property 4: Resume Dialog se Muestra Cuando Corresponde
*Para cualquier episodio con posición guardada mayor a 10 segundos desde la última actualización (< 2 min ago), se debe mostrar el dialog de resume*

**Validates: Requirements 2.2**

### Property 5: Resume Busca a Posición Correcta
*Cuando el usuario selecciona "Resume" desde el dialog, la reproducción debe comenzar en la posición guardada (± 500ms)*

**Validates: Requirements 2.3**

### Property 6: Aplicación Persiste Progreso al Cerrar
*Cuando la aplicación se cierra normalmente, todos los cambios pendientes de progreso deben guardarse antes de terminar*

**Validates: Requirements 3.1, 3.2**

### Property 7: Multi-Dispositivo Aísla Posiciones
*Cuando un dispositivo abre un episodio, solo debe cargar posiciones guardadas en ese mismo dispositivo, no de otros*

**Validates: Requirements 7.2, 7.3**

### Property 8: Sesiones Expiradas se Descartan
*Para posiciones guardadas hace más de 2 minutos, la aplicación debe comenzar desde posición 0 sin mostrar resume dialog*

**Validates: Requirements 4.3**

### Property 9: Escribes Fallidos se Reintentan
*Cuando un escribir falla, debe haber al menos un reintento dentro de 5 segundos*

**Validates: Requirements 8.1**

### Property 10: Posición Completada se Limpia
*Cuando un episodio alcanza el 95% de reproducción, su posición guardada debe ser eliminada*

**Validates: Requirements 6.2, 6.3**

