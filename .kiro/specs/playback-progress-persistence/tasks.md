# Implementation Plan: Persistencia de Progreso de Reproducción

## Overview

Implementar un sistema robusto de persistencia de progreso de reproducción en AniYomi que capture y restaure automáticamente posiciones de video. El sistema utiliza Room Database para almacenamiento atómico, debouncing de 5 segundos para optimizar escrituras, y validación de sesión para recuperación ante fallos.

## Tasks

- [x] 1. Configurar base de datos Room y entidades
  - [x] 1.1 Crear entidad PlaybackProgress con índices
    - Definir campos: episodeId, positionMs, durationMs, lastUpdateTime, deviceId, status, version
    - Crear índices compuestos: (episodeId, deviceId) para queries rápidas
    - Implementar índice en lastUpdateTime para cleanup automático
    - _Requirements: 1.4, 5.3_

  - [x] 1.2 Implementar PlaybackProgressDao con operaciones CRUD
    - Crear inserciones atómicas con OnConflictStrategy.REPLACE
    - Implementar query para recuperar última posición por episodeId + deviceId
    - Crear operación updateProgressAtomic() con transacciones
    - _Requirements: 1.1, 3.3_

  - [ ]* 1.3 Escribir property test para atomicidad de escrituras
    - **Property 1: Atomicidad de Escrituras**
    - **Validates: Requirements 5.3**

  - [x] 1.4 Crear DeadLink entity y DAO para manejo de links rotos
    - Definir campos: url, addedAt, expiresAt (24 horas)
    - Implementar lógica de expiración automática
    - _Requirements: 3.1_

- [ ] 2. Implementar PlaybackProgressRepository
  - [x] 2.1 Crear clase repository con transacciones
    - Implementar saveProgress() con manejo de excepciones
    - Implementar getProgressForEpisode() que valida sesión expirada
    - Crear markAsCompleted() cuando episodio alcanza 95%
    - _Requirements: 1.2, 1.3_

  - [~] 2.2 Implementar rollback automático en escrituras fallidas
    - Retornar estado anterior si write falla
    - Registrar error pero no interrumpir reproducción
    - _Requirements: 8.1, 8.2_

  - [ ]* 2.3 Escribir property test para recuperación ante fallos
    - **Property 6: Aplicación Persiste Progreso al Cerrar**
    - **Validates: Requirements 3.1, 3.2**

- [x] 3. Implementar PlaybackSessionValidator
  - [x] 3.1 Crear validator para validación de posiciones
    - isValidPosition(): verificar 0 <= position <= duration
    - isSessionExpired(): marcar expirado si > 2 minutos sin actividad
    - isValidProgress(): validar que status != CORRUPTED
    - _Requirements: 1.5, 4.3_

  - [x] 3.2 Implementar ajustes de posición para recuperación
    - adjustPositionIfNeeded(): limitar a 90% si excede duración
    - shouldShowResumeDialog(): retornar true si position > 10s
    - _Requirements: 2.1, 2.5_

  - [ ]* 3.3 Escribir property test para validación de sesiones
    - **Property 8: Sesiones Expiradas se Descartan**
    - **Validates: Requirements 4.3**

- [x] 4. Implementar DeviceProvider
  - [x] 4.1 Crear proveedor de identificador único de dispositivo
    - Generar UUID persistente al instalar app
    - Guardar en SharedPreferences
    - _Requirements: 7.2_

  - [x] 4.2 Implementar getDeviceId() sincrónico
    - Cachear ID en memoria para acceso rápido
    - _Requirements: 7.3_

- [~] 5. Checkpoint - Validar capas de persistencia
  - Ejecutar todos los tests de Room y repository
  - Verificar que las transacciones son atómicas
  - Preguntar al usuario si hay dudas antes de continuar

- [ ] 6. Implementar PlaybackProgressManager
  - [~] 6.1 Crear manager con debouncing de 5 segundos
    - Implementar onPlaybackPositionChanged() con Job debounce
    - Cancelar job anterior cuando nueva posición llega
    - _Requirements: 1.2, 1.1_

  - [~] 6.2 Implementar onPlaybackPaused() para guardado inmediato
    - Guardar sin debounce cuando usuario pausa
    - _Requirements: 1.3_

  - [~] 6.3 Implementar handleWriteFailure() con reintentos
    - Encolar actualizaciones fallidas en memoria (List<PlaybackProgressDTO>)
    - Reintentar después de 5 segundos
    - Contar fallos consecutivos, notificar si >= 3 en 30s
    - _Requirements: 8.1, 8.3_

  - [~] 6.4 Implementar flushPendingUpdates() para cierre de app
    - Procesar todas las actualizaciones pendientes
    - Ejecutar en onAppTerminating()
    - _Requirements: 3.2_

  - [ ]* 6.5 Escribir property test para debouncing
    - **Property 2: Guardado Periódico Durante Reproducción**
    - **Validates: Requirements 1.2**

  - [ ]* 6.6 Escribir property test para guardado en pausa
    - **Property 3: Guardado Inmediato en Pausa**
    - **Validates: Requirements 1.3**

- [ ] 7. Implementar ResumeDialog UI Composable
  - [~] 7.1 Crear composable ResumeDialog con dos botones
    - Mostrar posición guardada formateada (ej: "10:30")
    - Botón "Continuar" y "Desde el inicio"
    - Botón para descartar (X)
    - _Requirements: 2.2, 2.3_

  - [~] 7.2 Integrar dialog con PlayerViewModel
    - loadResumeState() al abrir episodio
    - onResumeSelected() hace seekTo() en reproductor
    - onStartFromBeginning() limpia y busca a 0
    - _Requirements: 2.3_

  - [ ]* 7.3 Escribir property test para mostrado de dialog
    - **Property 4: Resume Dialog se Muestra Cuando Corresponde**
    - **Validates: Requirements 2.2**

- [ ] 8. Integración con Video Player (mpv-android)
  - [~] 8.1 Conectar eventos del reproductor a PlaybackProgressManager
    - Escuchar onPositionChanged() emitido por mpv-android
    - Llamar onPlaybackPositionChanged() con posición y duración
    - _Requirements: 1.1_

  - [~] 8.2 Implementar hook de pausa
    - Escuchar onPaused() del reproductor
    - Llamar onPlaybackPaused() inmediatamente
    - _Requirements: 1.3_

  - [~] 8.3 Implementar hook de cierre
    - Llamar onAppTerminating() en onDestroy()
    - Flush pending writes antes de terminar
    - _Requirements: 3.2_

  - [ ]* 8.4 Escribir property test para posición en resume
    - **Property 5: Resume Busca a Posición Correcta**
    - **Validates: Requirements 2.3**

- [ ] 9. Implementar limpieza de completados
  - [~] 9.1 Crear lógica para marcar episodio como completado
    - Detectar cuando posición >= 95% de duración
    - Llamar markAsCompleted()
    - _Requirements: 6.2, 6.3_

  - [~] 9.2 Limpiar posición guardada de completados
    - deleteCompletedProgress() elimina entries con status COMPLETED
    - Ejecutar daily cleanup en background
    - _Requirements: 6.2_

  - [ ]* 9.3 Escribir property test para limpieza
    - **Property 10: Posición Completada se Limpia**
    - **Validates: Requirements 6.2, 6.3**

- [ ] 10. Implementar soporte multi-dispositivo
  - [~] 10.1 Aislar datos por deviceId
    - Queries siempre incluyen filtro deviceId
    - No cargar posiciones de otros dispositivos
    - _Requirements: 7.2, 7.3_

  - [~] 10.2 Crear lógica de conflicto multi-dispositivo
    - Si múltiples dispositivos tienen posiciones, usar más reciente del dispositivo actual
    - Ignorar posiciones de otros dispositivos
    - _Requirements: 7.1_

  - [ ]* 10.3 Escribir property test para aislamiento multi-dispositivo
    - **Property 7: Multi-Dispositivo Aísla Posiciones**
    - **Validates: Requirements 7.2, 7.3**

- [ ] 11. Implementar manejo de corrupciones
  - [~] 11.1 Detectar datos corruptos al cargar
    - Validar posición <= duración
    - Si falla, marcar como CORRUPTED
    - Comenzar reproducción desde 0
    - _Requirements: 5.4_

  - [~] 11.2 Implementar recuperación tras reboot
    - Leer datos de Room tras reinicio de sistema
    - Validar integridad con transacciones
    - _Requirements: 5.2_

- [~] 12. Checkpoint - Validar integraciones
  - Ejecutar flujos end-to-end: abrir episodio → pausar → cerrar → reabrir
  - Verificar resume dialog se muestra correctamente
  - Preguntar al usuario si hay dudas

- [ ] 13. Configuración de persistencia
  - [~] 13.1 Crear database configuration
    - Configurar Room database con versión 1
    - Habilitar WAL (Write-Ahead Logging) para durabilidad
    - _Requirements: 5.3_

  - [~] 13.2 Implementar migración de database (si existe versión anterior)
    - Crear migration strategy
    - _Requirements: 5.2_

- [ ] 14. Testing y validación
  - [~] 14.1 Escribir unit tests para validator
    - Testear validación de posiciones
    - Testear detección de sesiones expiradas
    - _Requirements: 1.5_

  - [~] 14.2 Escribir integration tests
    - Testear flujo completo: guardar → cargar → restaurar
    - Testear manejo de fallos de storage
    - _Requirements: 8.1, 8.3_

  - [~] 14.3 Escribir instrumented tests
    - Testear con Room database real
    - Testear cierre de app y flush pending
    - _Requirements: 3.2_

- [~] 15. Final checkpoint - Todas las propiedades validadas
  - Ejecutar suite completa de property tests
  - Verificar recuperación ante fallos de storage
  - Preguntar al usuario si hay dudas

## Notes

- Tareas marcadas con `*` son opcionales y pueden saltarse para MVP más rápido
- El debouncing de 5 segundos es crítico para evitar múltiples escrituras
- Las transacciones atómicas previenen corrupción de datos si app termina abruptamente
- El multi-dispositivo se maneja a través del campo deviceId, sin sincronización cloud
- Máximo 500 registros activos en playback_progress, límpieza automática de completados

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.4", "4.1", "4.2"] },
    { "id": 1, "tasks": ["1.3", "2.1", "3.1", "3.2"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.3", "6.1", "6.2"] },
    { "id": 3, "tasks": ["6.3", "6.4", "6.5", "6.6", "7.1", "7.2"] },
    { "id": 4, "tasks": ["7.3", "8.1", "8.2", "8.3"] },
    { "id": 5, "tasks": ["8.4", "9.1", "9.2", "9.3"] },
    { "id": 6, "tasks": ["10.1", "10.2", "10.3", "11.1", "11.2"] },
    { "id": 7, "tasks": ["13.1", "13.2", "14.1", "14.2", "14.3"] }
  ]
}
```
