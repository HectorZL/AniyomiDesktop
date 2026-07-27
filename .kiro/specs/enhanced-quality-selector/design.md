# Diseño: Selector de Calidad Mejorado

## Introducción

Este documento describe la arquitectura del sistema de selección de calidad de video en AniYomi. El sistema detecta automáticamente calidades disponibles, presenta una interfaz intuitiva, y guarda preferencias del usuario para futuras reproducciones. Soporta cambios de calidad incluso durante la reproducción.

## Arquitectura General

```
┌─────────────────────────────────────────────┐
│      Video Source / Streaming Provider       │
│  (Proporciona Quality_Options)               │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│    QualityDetector (Analizador)              │
│  - Fetch Quality_Options (< 3000ms)         │
│  - Parse resoluciones (720p, 1080p, etc)    │
│  - Infer metadata si falta                  │
│  - Cache Quality_Options en sesión          │
└──────────────────┬──────────────────────────┘
                   │
        ┌──────────┴──────────┐
        ▼                     ▼
┌──────────────────┐  ┌──────────────────┐
│ QualitySelector  │  │ QualityValidator │
│  (Presentación)  │  │  (Domain Logic)  │
│ - Show UI        │  │ - Compatibilidad │
│ - User select    │  │ - Validación     │
│ - Apply choice   │  │ - Device caps    │
└──────┬───────────┘  └────────┬─────────┘
       │                       │
       └───────────┬───────────┘
                   ▼
        ┌────────────────────┐
        │ QualityPreference  │
        │ (Repository)       │
        │ - Guardar pref     │
        │ - Cargar pref      │
        │ - Global + Per-src │
        └────────────────────┘
```

## Modelos de Datos

### Entidad de Calidad

```kotlin
data class QualityOption(
    val id: String,                    // Identificador único de calidad
    val resolution: String,            // "720p", "1080p", "2K", "4K"
    val bitrate: Int? = null,          // En Mbps
    val videoFormat: VideoFormat,      // H.264, H.265, VP9
    val width: Int? = null,            // En píxeles
    val height: Int? = null,
    val frameRate: Int? = null,        // En fps
    val url: String,                   // URL directa del stream
    val sourceId: String,              // De qué proveedor
    val isSupported: Boolean = true    // Compatible con dispositivo
)

enum class VideoFormat {
    H264, H265, VP9, UNKNOWN
}

@Entity(
    tableName = "quality_preferences",
    indices = [Index("sourceId", unique = false)]
)
data class QualityPreference(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "resolution")
    val resolution: String,            // "1080p" (normalizado)
    
    @ColumnInfo(name = "source_id")
    val sourceId: String? = null,      // null = preferencia global
    
    @ColumnInfo(name = "video_format")
    val videoFormat: String? = null,
    
    @ColumnInfo(name = "last_update")
    val lastUpdate: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "data_saver_mode_resolution")
    val dataSaverResolution: String? = null  // Preferencia en modo ahorro
)

data class QualityDetectionResult(
    val episodeId: String,
    val availableQualities: List<QualityOption>,
    val recommendedQuality: QualityOption?,
    val detectionTimeMs: Long,
    val hasMultipleQualities: Boolean
)

data class CurrentQualityDisplay(
    val resolution: String,
    val bitrate: Int?,
    val videoFormat: VideoFormat,
    val frameRate: Int?,
    val isAutoSelected: Boolean,
    val bufferingStatus: BufferingStatus = BufferingStatus.NONE
)

enum class BufferingStatus {
    NONE, BUFFERING, DEGRADED, FAILED
}
```

### DTO para Persistencia

```kotlin
data class QualityPreferenceDTO(
    val resolution: String,
    val sourceId: String?,
    val videoFormat: String?
)
```

## Componentes Detallados

### 1. QualityDetector (Extractor)

```kotlin
class QualityDetector(
    private val sourceProvider: VideoSourceProvider,
    private val validator: QualityValidator,
    private val preferenceRepo: QualityPreferenceRepository
) {
    suspend fun detectAvailableQualities(
        episode: Episode,
        sourceId: String,
        timeoutMs: Long = 3000
    ): QualityDetectionResult {
        return withTimeoutOrNull(timeoutMs) {
            val qualities = sourceProvider.getAvailableQualities(episode)
                ?: emptyList()
            
            val normalized = normalizeQualities(qualities)
            val filtered = validated(normalized)
            
            val recommended = selectRecommended(filtered)
            
            QualityDetectionResult(
                episodeId = episode.id,
                availableQualities = filtered,
                recommendedQuality = recommended,
                detectionTimeMs = System.currentTimeMillis(),
                hasMultipleQualities = filtered.size > 1
            )
        } ?: QualityDetectionResult(
            episodeId = episode.id,
            availableQualities = emptyList(),
            recommendedQuality = null,
            detectionTimeMs = System.currentTimeMillis(),
            hasMultipleQualities = false
        )
    }
    
    private fun normalizeQualities(
        raw: List<QualityOption>
    ): List<QualityOption> {
        return raw.map { quality ->
            // Si falta resolución, inferir de width/height
            val normalizedResolution = quality.resolution.ifEmpty {
                inferResolution(quality.width, quality.height)
            }
            
            quality.copy(
                resolution = normalizedResolution,
                videoFormat = detectFormat(quality.videoFormat, quality.url)
            )
        }
    }
    
    private fun inferResolution(width: Int?, height: Int?): String {
        return when {
            height == null || width == null -> "UNKNOWN"
            height >= 2160 -> "4K"
            height >= 1440 -> "2K"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            else -> "LOW"
        }
    }
    
    private fun selectRecommended(
        qualities: List<QualityOption>
    ): QualityOption? {
        val prefs = runBlocking {
            preferenceRepo.getGlobalPreference()
        }
        
        // Buscar calidad que coincida con preferencia
        return qualities.find { it.resolution == prefs?.resolution }
            ?: qualities.firstOrNull { it.isSupported }
    }
}
```

### 2. QualitySelector (UI Controller)

```kotlin
class QualitySelectorViewModel(
    private val qualityDetector: QualityDetector,
    private val validator: QualityValidator,
    private val preferenceRepo: QualityPreferenceRepository,
    private val playbackEngine: PlaybackEngine
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<QualitySelectorUiState>(
        QualitySelectorUiState.Hidden
    )
    val uiState: StateFlow<QualitySelectorUiState> = _uiState.asStateFlow()
    
    private val _currentQuality = MutableStateFlow<CurrentQualityDisplay?>(null)
    val currentQuality: StateFlow<CurrentQualityDisplay?> = _currentQuality.asStateFlow()
    
    private var detectedQualities: List<QualityOption> = emptyList()
    
    fun detectQualitiesForEpisode(episode: Episode, sourceId: String) {
        viewModelScope.launch {
            try {
                val result = qualityDetector.detectAvailableQualities(
                    episode, sourceId, timeoutMs = 3000
                )
                detectedQualities = result.availableQualities
                
                if (result.hasMultipleQualities) {
                    val pref = preferenceRepo.getGlobalPreference()
                    val preSelected = result.availableQualities.find {
                        it.resolution == pref?.resolution
                    } ?: result.recommendedQuality
                    
                    _uiState.value = QualitySelectorUiState.ShowSelection(
                        qualities = result.availableQualities,
                        preSelectedQuality = preSelected
                    )
                } else if (result.availableQualities.isNotEmpty()) {
                    // Auto-apply single quality
                    applyQuality(result.availableQualities.first())
                    _uiState.value = QualitySelectorUiState.Hidden
                }
            } catch (e: Exception) {
                _uiState.value = QualitySelectorUiState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun applyQuality(quality: QualityOption) {
        viewModelScope.launch {
            try {
                val validationResult = validator.validateQuality(quality)
                
                if (!validationResult.isSupported) {
                    _uiState.value = QualitySelectorUiState.ShowWarning(
                        message = "Formato no soportado: ${quality.videoFormat}",
                        alternatives = findAlternatives(quality)
                    )
                    return@launch
                }
                
                // Aplicar calidad al reproductor
                playbackEngine.switchQuality(quality)
                
                // Guardar preferencia
                val pref = QualityPreferenceDTO(
                    resolution = quality.resolution,
                    sourceId = quality.sourceId,
                    videoFormat = quality.videoFormat.toString()
                )
                preferenceRepo.savePreference(pref)
                
                _uiState.value = QualitySelectorUiState.Hidden
            } catch (e: Exception) {
                _uiState.value = QualitySelectorUiState.Error(e.message ?: "Error")
            }
        }
    }
    
    private fun findAlternatives(quality: QualityOption): List<QualityOption> {
        return detectedQualities
            .filter { it.isSupported && it.videoFormat != quality.videoFormat }
            .sortedByDescending { it.height ?: 0 }
    }
}

sealed class QualitySelectorUiState {
    object Hidden : QualitySelectorUiState()
    data class ShowSelection(
        val qualities: List<QualityOption>,
        val preSelectedQuality: QualityOption?
    ) : QualitySelectorUiState()
    data class ShowWarning(
        val message: String,
        val alternatives: List<QualityOption>
    ) : QualitySelectorUiState()
    data class Error(val message: String) : QualitySelectorUiState()
}
```

### 3. QualityValidator (Domain Logic)

```kotlin
class QualityValidator(
    private val deviceCapabilities: DeviceCapabilities
) {
    fun validateQuality(quality: QualityOption): ValidationResult {
        return ValidationResult(
            isSupported = isFormatSupported(quality.videoFormat),
            isResolutionSupported = canDisplayResolution(quality.height),
            requiresDowngrade = needsDowngrade(quality),
            message = null
        )
    }
    
    private fun isFormatSupported(format: VideoFormat): Boolean {
        return format in deviceCapabilities.supportedFormats
    }
    
    private fun canDisplayResolution(height: Int?): Boolean {
        if (height == null) return true
        return height <= deviceCapabilities.maxSupportedHeight
    }
    
    private fun needsDowngrade(quality: QualityOption): Boolean {
        if (!isFormatSupported(quality.videoFormat)) return true
        if (!canDisplayResolution(quality.height)) return true
        return false
    }
    
    fun selectBestSupported(
        qualities: List<QualityOption>
    ): QualityOption? {
        return qualities
            .filter { validateQuality(it).isSupported }
            .maxByOrNull { it.height ?: 0 }
    }
}

data class ValidationResult(
    val isSupported: Boolean,
    val isResolutionSupported: Boolean,
    val requiresDowngrade: Boolean,
    val message: String?
)

data class DeviceCapabilities(
    val supportedFormats: Set<VideoFormat>,
    val maxSupportedHeight: Int,
    val maxBitrate: Int,  // En Mbps
    val supportsHardwareDecoding: Boolean
)
```


### 4. QualityPreferenceRepository (Persistence)

```kotlin
@Dao
interface QualityPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreference(pref: QualityPreference)
    
    @Query("SELECT * FROM quality_preferences WHERE source_id IS NULL LIMIT 1")
    suspend fun getGlobalPreference(): QualityPreference?
    
    @Query("SELECT * FROM quality_preferences WHERE source_id = :sourceId")
    suspend fun getSourcePreference(sourceId: String): QualityPreference?
}

class QualityPreferenceRepository(private val dao: QualityPreferenceDao) {
    suspend fun savePreference(dto: QualityPreferenceDTO) {
        val entity = QualityPreference(
            resolution = dto.resolution,
            sourceId = dto.sourceId,
            videoFormat = dto.videoFormat
        )
        dao.insertPreference(entity)
    }
    
    suspend fun getGlobalPreference(): QualityPreference? {
        return dao.getGlobalPreference()
    }
    
    suspend fun getSourcePreference(sourceId: String): QualityPreference? {
        return dao.getSourcePreference(sourceId)
    }
}
```

### 5. UI Composable - Quality Selector

```kotlin
@Composable
fun QualitySelectorOverlay(
    uiState: QualitySelectorUiState,
    onQualitySelected: (QualityOption) -> Unit,
    onDismiss: () -> Unit
) {
    when (uiState) {
        is QualitySelectorUiState.ShowSelection -> {
            QualitySelectionDialog(
                qualities = uiState.qualities,
                preSelected = uiState.preSelectedQuality,
                onQualitySelected = onQualitySelected,
                onDismiss = onDismiss
            )
        }
        is QualitySelectorUiState.ShowWarning -> {
            QualityWarningDialog(
                message = uiState.message,
                alternatives = uiState.alternatives,
                onAlternativeSelected = onQualitySelected,
                onDismiss = onDismiss
            )
        }
        is QualitySelectorUiState.Error -> {
            ErrorSnackbar(message = uiState.message)
        }
        else -> {} // Hidden
    }
}

@Composable
fun QualitySelectionDialog(
    qualities: List<QualityOption>,
    preSelected: QualityOption?,
    onQualitySelected: (QualityOption) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedQuality by remember { mutableStateOf(preSelected) }
    
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.DarkGray)
                .padding(16.dp)
        ) {
            Text(
                "Seleccionar Calidad",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            qualities.forEach { quality ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedQuality = quality }
                        .padding(12.dp)
                ) {
                    RadioButton(
                        selected = selectedQuality?.id == quality.id,
                        onClick = { selectedQuality = quality }
                    )
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(quality.resolution)
                        quality.bitrate?.let {
                            Text("${it}Mbps", fontSize = 12.sp)
                        }
                    }
                }
            }
            
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onDismiss) {
                    Text("Cancelar")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        selectedQuality?.let(onQualitySelected)
                    }
                ) {
                    Text("Aplicar")
                }
            }
        }
    }
}
```

### 6. Playback Engine Integration

```kotlin
class PlaybackEngine(
    private val validator: QualityValidator
) {
    private var currentQuality: QualityOption? = null
    
    fun switchQuality(quality: QualityOption) {
        val validation = validator.validateQuality(quality)
        
        if (!validation.isSupported) {
            throw UnsupportedQualityException(quality.videoFormat)
        }
        
        // Obtener posición actual
        val currentPosition = getCurrentPosition()
        
        try {
            // Cargar nuevo stream
            loadStreamUrl(quality.url)
            
            // Buscar a la misma posición
            seek(currentPosition)
            
            currentQuality = quality
        } catch (e: Exception) {
            // Revertir a calidad anterior
            currentQuality?.let { loadStreamUrl(it.url) }
            throw PlaybackQualitySwitchException(e)
        }
    }
    
    fun getCurrentQualityDisplay(): CurrentQualityDisplay? {
        return currentQuality?.let {
            CurrentQualityDisplay(
                resolution = it.resolution,
                bitrate = it.bitrate,
                videoFormat = it.videoFormat,
                frameRate = it.frameRate,
                isAutoSelected = false
            )
        }
    }
    
    private fun loadStreamUrl(url: String) {
        // Usar mpv-android para cargar URL
        // mpv.commandAsync("loadfile", url, "replace")
    }
    
    private fun getCurrentPosition(): Long {
        // Obtener de mpv
        return 0L
    }
    
    private fun seek(position: Long) {
        // Buscar en mpv
    }
}
```

## Ciclos de Vida

### Flujo de Selección Antes de Reproducción

```
1. Usuario abre episodio
2. QualityDetector.detectAvailableQualities() inicia (< 3000ms)
3. Si múltiples calidades:
   - Mostrar QualitySelectorUiState.ShowSelection
   - Pre-seleccionar QualityPreference global
4. Usuario selecciona o acepta pre-selección
5. PlaybackEngine.switchQuality() busca a URL del stream
6. Guardar preferencia en QualityPreferenceRepository
```

### Cambio de Calidad Durante Reproducción

```
1. Usuario abre Quality Menu durante playback
2. QualitySelectorUiState.ShowSelection se muestra
3. Usuario selecciona nueva calidad
4. PlaybackEngine.switchQuality():
   - Pausa playback
   - Carga nuevo stream
   - Busca a posición actual (< 1000ms pausa)
   - Reanuda
5. Guardar preferencia
```

### Validación de Compatibilidad

```
1. User selects quality
2. QualityValidator.validateQuality():
   - Check VideoFormat support
   - Check resolution support
3. If unsupported:
   - Show QualitySelectorUiState.ShowWarning
   - Suggest alternatives (soportadas)
4. If supported:
   - Apply quality
```

## Manejo de Errores

| Escenario | Estrategia | Resultado |
|-----------|-----------|-----------|
| Calidad no compatible | ShowWarning + sugerir alternativas | Usuario elige formato soportado |
| Fallo al cargar stream | Revertir a calidad anterior | Playback continúa sin interrupciones |
| No hay múltiples calidades | Omitir selector (hidden) | Jugar directamente |
| Resolución no soportada | Degradar a máxima soportada | Usar mejor calidad disponible |
| Timeout en detección (> 3s) | Usar timeout y retornar empty | Proceder sin selector |


## Consideraciones de Rendimiento

### Optimizaciones

1. **Detección Asyncrónica:** QualityDetector no bloquea UI (< 3000ms timeout)
2. **Caching en Sesión:** QualityOptions se cachean durante sesión de episodio
3. **Lazy Loading:** Validación de dispositivo ocurre una sola vez al iniciar app
4. **Pre-selección:** QualityPreference se carga antes de mostrar dialog

### Límites

- Máximo 20 opciones de calidad por episodio
- Detección timeout: 3000ms
- Cambio de calidad sin interrupción: < 3000ms
- Pausa durante cambio: < 1000ms

## Correctness Properties

*Las propiedades especifican comportamientos que deben ser ciertos en toda ejecución.*

### Property 1: Detección de Calidades es Completa
*Para cualquier episodio con múltiples calidades disponibles, el QualityDetector debe detectarlas todas dentro de 3000ms*

**Validates: Requirements 1.1, 1.2**

### Property 2: Resoluciones Normalizadas
*Para cualquier Quality_Option del Video_Source, la resolución debe ser normalizada a un formato consistente (720p, 1080p, etc)*

**Validates: Requirements 1.3**

### Property 3: Selector Solo se Muestra con Múltiples Calidades
*Cuando hay solo una calidad disponible, la UI del selector no debe mostrarse*

**Validates: Requirements 7.1**

### Property 4: Preferencia se Aplica Automáticamente
*Cuando un episodio tiene una Quality_Option que coincide con Preferred_Quality guardada, debe aplicarse automáticamente sin mostrar selector*

**Validates: Requirements 4.1, 4.4**

### Property 5: Preferencia se Persiste Correctamente
*Cuando el usuario selecciona una calidad, esa resolución debe guardarse y recuperarse en futuras aperturas del mismo episodio*

**Validates: Requirements 3.1, 3.3**

### Property 6: Cambio de Calidad Preserva Posición
*Cuando el usuario cambia calidad durante reproducción, la nueva calidad debe cargar en la misma posición (± 500ms)*

**Validates: Requirements 5.2**

### Property 7: Validación Previene Formatos Incompatibles
*Cuando un formato de video no es soportado por el dispositivo, el selector debe prevenirlo y sugerir alternativas*

**Validates: Requirements 6.1, 6.2**

### Property 8: Degradación de Calidad Automática
*Si Preferred_Quality no está disponible, debe usarse la calidad más cercana disponible*

**Validates: Requirements 4.5**

### Property 9: Fallo en Carga Reintenta Alternativas
*Si falla la carga de una Quality_Option, el PlaybackEngine debe intentar la siguiente mejor opción dentro de 3000ms*

**Validates: Requirements 9.1, 9.4**

### Property 10: Información de Calidad Actual es Precisa
*Mientras se reproduce, la calidad mostrada en UI debe corresponder con la Quality_Option realmente cargada*

**Validates: Requirements 8.1, 8.2**

