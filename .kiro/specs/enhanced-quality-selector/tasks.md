# Implementation Plan: Selector de Calidad Mejorado

## Overview

Implementar un sistema completo de selección de calidad de video que detecta automáticamente opciones disponibles, presenta una interfaz intuitiva, valida compatibilidad de dispositivo, y persiste preferencias del usuario. El sistema soporta cambios de calidad incluso durante reproducción con transiciones < 3 segundos.

## Tasks

- [ ] 1. Configurar modelos de datos y entidades
  - [ ] 1.1 Crear data classes para calidades
    - Definir QualityOption (id, resolution, bitrate, videoFormat, width, height, frameRate, url, sourceId, isSupported)
    - Definir enum VideoFormat (H264, H265, VP9, UNKNOWN)
    - _Requirements: 1.2, 1.3_

  - [ ] 1.2 Crear Room entity QualityPreference
    - Definir tabla quality_preferences con índice en sourceId
    - Campos: id, resolution, sourceId (nullable para global), videoFormat, lastUpdate, dataSaverResolution
    - _Requirements: 3.1, 3.3_

  - [ ] 1.3 Crear DTOs de transferencia
    - QualityPreferenceDTO para separar domain de persistence
    - QualityDetectionResult para encapsular resultados
    - CurrentQualityDisplay para mostrar calidad actual en UI
    - _Requirements: 3.1_

  - [ ]* 1.4 Escribir property test para validez de modelos
    - **Property 10: Información de Calidad Actual es Precisa**
    - **Validates: Requirements 8.1, 8.2**

- [ ] 2. Implementar QualityDetector
  - [ ] 2.1 Crear detector con timeout de 3000ms
    - Implementar detectAvailableQualities() que obtiene opciones del source
    - Aplicar timeout con withTimeoutOrNull
    - _Requirements: 1.1, 1.4_

  - [ ] 2.2 Implementar normalización de resoluciones
    - normalizeQualities(): convertir labels inconsistentes a estándar (720p, 1080p, etc)
    - inferResolution(): deducir de width/height si falta resolución
    - detectFormat(): identificar videoFormat de URL o metadata
    - _Requirements: 1.3, 1.4_

  - [ ] 2.3 Implementar selección de recomendada
    - selectRecommended(): buscar calidad que coincida con preferencia global
    - Si no existe, retornar primera soportada
    - _Requirements: 4.1, 4.5_

  - [ ] 2.4 Implementar caching en sesión
    - Guardar QualityOptions en memoria durante sesión del episodio
    - _Requirements: 1.5_

  - [ ]* 2.5 Escribir property test para detección completa
    - **Property 1: Detección de Calidades es Completa**
    - **Validates: Requirements 1.1, 1.2**

  - [ ]* 2.6 Escribir property test para normalización
    - **Property 2: Resoluciones Normalizadas**
    - **Validates: Requirements 1.3**

- [ ] 3. Implementar QualityValidator
  - [ ] 3.1 Crear validator con capacidades de dispositivo
    - Definir DeviceCapabilities (supportedFormats, maxSupportedHeight, maxBitrate, supportsHardwareDecoding)
    - _Requirements: 6.1, 6.3_

  - [ ] 3.2 Implementar validación de formato
    - isFormatSupported(): verificar VideoFormat contra dispositivo
    - ValidationResult retorna isSupported, requiresDowngrade
    - _Requirements: 6.1, 6.2_

  - [ ] 3.3 Implementar validación de resolución
    - canDisplayResolution(): verificar altura máxima del dispositivo
    - needsDowngrade(): detectar si necesita downgrade
    - _Requirements: 6.3, 9.5_

  - [ ] 3.4 Implementar selección de mejor soportada
    - selectBestSupported(): retornar máxima resolución que funcione
    - _Requirements: 4.5, 6.2_

  - [ ]* 3.5 Escribir property test para validación
    - **Property 7: Validación Previene Formatos Incompatibles**
    - **Validates: Requirements 6.1, 6.2**

- [ ] 4. Implementar QualityPreferenceRepository
  - [ ] 4.1 Crear DAO con operaciones
    - @Insert para guardar preferencia
    - @Query getGlobalPreference (sourceId IS NULL)
    - @Query getSourcePreference (sourceId = :sourceId)
    - _Requirements: 3.1, 3.2_

  - [ ] 4.2 Implementar repository layer
    - savePreference(): convertir DTO a entity
    - getGlobalPreference()
    - getSourcePreference()
    - _Requirements: 3.1, 3.3_

  - [ ] 4.3 Implementar limpieza de preferencias inválidas
    - Limpiar preferencias si source es removido
    - _Requirements: 3.1_

  - [ ]* 4.4 Escribir property test para persistencia
    - **Property 5: Preferencia se Persiste Correctamente**
    - **Validates: Requirements 3.1, 3.3**

- [ ] 5. Checkpoint - Validar persistencia de preferencias
  - Ejecutar tests de repository
  - Verificar que preferencias globales y por-source se guardan
  - Preguntar al usuario si hay dudas

- [ ] 6. Implementar QualitySelectorViewModel
  - [ ] 6.1 Crear ViewModel con state management
    - _uiState: MutableStateFlow<QualitySelectorUiState>
    - _currentQuality: MutableStateFlow<CurrentQualityDisplay>
    - detectQualitiesForEpisode()
    - _Requirements: 2.1, 7.1_

  - [ ] 6.2 Implementar detección de calidades en ViewModel
    - Llamar qualityDetector.detectAvailableQualities()
    - Si múltiples: mostrar ShowSelection
    - Si una: aplicar automáticamente y ocultar UI
    - _Requirements: 7.1, 4.1_

  - [ ] 6.3 Implementar aplicación de calidad
    - applyQuality(): validar, aplicar al reproductor, guardar preferencia
    - Mostrar advertencia si formato no soportado
    - _Requirements: 2.5, 2.6_

  - [ ] 6.4 Implementar alternativas para formatos incompatibles
    - findAlternatives(): retornar calidades soportadas
    - Mostrar en ShowWarning state
    - _Requirements: 6.2, 9.2_

  - [ ] 6.5 Implementar manejo de errores
    - showError state si falla detección
    - _Requirements: 2.6, 9.3_

  - [ ]* 6.6 Escribir property test para auto-aplicación
    - **Property 4: Preferencia se Aplica Automáticamente**
    - **Validates: Requirements 4.1, 4.4**

  - [ ]* 6.7 Escribir property test para degradación automática
    - **Property 8: Degradación de Calidad Automática**
    - **Validates: Requirements 4.5**

- [ ] 7. Crear UI Composables para selector
  - [ ] 7.1 Crear QualitySelectorOverlay composable
    - Renderizar según QualitySelectorUiState (ShowSelection, ShowWarning, Error, Hidden)
    - _Requirements: 2.1, 2.2_

  - [ ] 7.2 Crear QualitySelectionDialog
    - Mostrar lista de QualityOptions con RadioButton
    - Mostrar resolution, bitrate, videoFormat
    - Pre-seleccionar calidad recomendada
    - Botones: Cancelar y Aplicar
    - _Requirements: 2.1, 2.2, 2.4_

  - [ ] 7.3 Crear QualityWarningDialog
    - Mostrar mensaje de formato no soportado
    - Listar alternativas disponibles
    - Permitir seleccionar alternativa
    - _Requirements: 6.2, 9.2_

  - [ ] 7.4 Crear CurrentQualityDisplay
    - Mostrar resolución, bitrate, videoFormat, frameRate actual
    - Actualizar cuando cambie calidad
    - _Requirements: 8.1, 8.2_

  - [ ]* 7.5 Escribir integration tests para UI
    - Testear selector se muestra correctamente
    - Testear selección de calidad
    - _Requirements: 2.1_

- [ ] 8. Integración con PlaybackEngine
  - [ ] 8.1 Crear PlaybackEngine.switchQuality()
    - Obtener posición actual
    - Validar calidad con validator
    - Cargar nueva URL del stream
    - Buscar a misma posición
    - _Requirements: 5.2, 5.3_

  - [ ] 8.2 Implementar manejo de fallos en switch
    - Si falla switch, revertir a calidad anterior
    - Lanzar PlaybackQualitySwitchException
    - _Requirements: 5.3, 9.1_

  - [ ] 8.3 Implementar reintentos automáticos
    - Si falla loadStreamUrl, intentar siguiente calidad
    - Exponential backoff: 500ms, 1000ms, 2000ms
    - _Requirements: 9.1, 9.4_

  - [ ] 8.4 Crear getCurrentQualityDisplay()
    - Retornar CurrentQualityDisplay con calidad actual
    - _Requirements: 8.1, 8.2_

  - [ ]* 8.5 Escribir property test para cambio sin pérdida
    - **Property 6: Cambio de Calidad Preserva Posición**
    - **Validates: Requirements 5.2**

  - [ ]* 8.6 Escribir property test para reintentos
    - **Property 9: Fallo en Carga Reintenta Alternativas**
    - **Validates: Requirements 9.1, 9.4**

- [ ] 9. Integración con Video Player
  - [ ] 9.1 Conectar ViewModel a PlayerScreen
    - Mostrar selector cuando state es ShowSelection
    - Manejar selecciones del usuario
    - _Requirements: 2.1, 2.5_

  - [ ] 9.2 Mostrar información de calidad actual
    - Renderizar CurrentQualityDisplay en player UI
    - Actualizar cuando cambia calidad
    - _Requirements: 8.1_

  - [ ] 9.3 Integrar con botón de controles
    - Agregar botón "Calidad" a controles del reproductor
    - Mostrar selector cuando se toca
    - _Requirements: 2.1, 5.1_

- [ ] 10. Implementar Data Saver Mode
  - [ ] 10.1 Crear lógica de data saver
    - Crear dataSaverResolution en QualityPreference
    - getPreferenceForMode(): retornar preferencia según modo
    - _Requirements: 10.1, 10.4_

  - [ ] 10.2 Filtrar calidades en data saver
    - Priorizar bitrates bajos en ShowSelection
    - Mostrar advertencia si selecciona alto bitrate
    - _Requirements: 10.1, 10.3_

  - [ ] 10.3 Implementar preferencia por modo
    - Guardar preferencias separadas: normal y dataSaver
    - _Requirements: 10.4_

- [ ] 11. Checkpoint - Validar integraciones con reproductor
  - Reproducir episodio, verificar selector aparece
  - Cambiar calidad, verificar que se aplica
  - Verificar información de calidad se muestra
  - Preguntar al usuario si hay dudas

- [ ] 12. Testing y validación
  - [ ] 12.1 Escribir unit tests para detector
    - Testear normalización de resoluciones
    - Testear inferencia de resolución
    - _Requirements: 1.2, 1.3_

  - [ ] 12.2 Escribir unit tests para validator
    - Testear validación de formatos
    - Testear selección de mejor calidad
    - _Requirements: 6.1, 6.2_

  - [ ] 12.3 Escribir unit tests para ViewModel
    - Testear detección de múltiples vs una calidad
    - Testear aplicación de preferencia
    - _Requirements: 4.1, 7.1_

  - [ ] 12.4 Escribir UI tests
    - Testear selector se muestra/oculta correctamente
    - Testear selección y aplicación
    - _Requirements: 2.1, 2.5_

- [ ] 13. Manejo de edge cases
  - [ ] 13.1 Manejar source sin Quality_Options
    - Proceder sin selector si video_source retorna empty
    - _Requirements: 7.2, 7.6_

  - [ ] 13.2 Manejar cambios dinámicos de Quality_Options
    - Si source reporta nuevas calidades durante sesión, actualizar
    - _Requirements: 7.3_

  - [ ] 13.3 Manejar preferencia no disponible
    - Si preferencia no está en opciones actuales, seleccionar cercana
    - _Requirements: 4.3, 4.5_

- [ ] 14. Final checkpoint - Todas las propiedades validadas
  - Ejecutar suite completa de property tests
  - Verificar selector funciona en múltiples escenarios
  - Preguntar al usuario si hay dudas

## Notes

- Tareas marcadas con `*` son opcionales y pueden saltarse para MVP
- El timeout de 3000ms en detección previene bloqueos UI
- El cambio de calidad debe completar en < 3000ms incluyendo buffer
- La pausa durante cambio debe ser < 1000ms
- Máximo 20 opciones de calidad por episodio
- DeviceCapabilities se cachean al iniciar app (una sola vez)

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "3.1", "3.2"] },
    { "id": 1, "tasks": ["1.4", "2.1", "2.2", "3.3", "3.4"] },
    { "id": 2, "tasks": ["2.3", "2.4", "2.5", "2.6", "3.5", "4.1", "4.2"] },
    { "id": 3, "tasks": ["4.3", "4.4", "6.1", "6.2", "6.3"] },
    { "id": 4, "tasks": ["6.4", "6.5", "6.6", "6.7", "7.1", "7.2"] },
    { "id": 5, "tasks": ["7.3", "7.4", "7.5", "8.1", "8.2"] },
    { "id": 6, "tasks": ["8.3", "8.4", "8.5", "8.6", "9.1", "9.2"] },
    { "id": 7, "tasks": ["9.3", "10.1", "10.2", "10.3", "12.1", "12.2"] },
    { "id": 8, "tasks": ["12.3", "12.4", "13.1", "13.2", "13.3"] }
  ]
}
```
