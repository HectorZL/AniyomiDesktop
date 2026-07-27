# Implementation Plan: Optimización de Rendimiento e Integración

## Overview

Implementar un sistema completo de optimización de rendimiento con caching multinivel, validación de links, manejo robusto de errores de fuentes, y prefetching en background. El objetivo es reducir tiempos de carga a < 2s para búsquedas, validar links de streaming, comprimir imágenes, y proporcionar experiencia fluida incluso con fuentes problemáticas.

## Tasks

- [ ] 1. Implementar sistema de caching multinivel
  - [ ] 1.1 Crear MemoryCache<T> con LRU eviction
    - Implementar LinkedHashMap con accessOrder=true
    - Máximo 50MB con evición automática
    - Métodos: get(), put(), evict(), clear(), getStats()
    - _Requirements: 3.2, 3.4_

  - [ ] 1.2 Crear DiskCache<T> con File system
    - Usar File para almacenamiento con máximo 500MB
    - Implementar evición LRU por access time
    - Métodos: get(), put(), evict(), clear()
    - _Requirements: 3.2, 3.3_

  - [ ] 1.3 Crear CacheEntry<T> con metadatos
    - key, value, createdAt, expiresAt, version, metadata
    - Calcular si está expirado
    - _Requirements: 3.4, 3.7_

  - [ ] 1.4 Crear CacheChain<T> multinivel
    - Intentar Memory primero (< 10ms)
    - Luego Disk (< 100ms)
    - Re-cachear en Memory si encuentra en Disk
    - _Requirements: 3.1, 3.2_

  - [ ]* 1.5 Escribir property test para validez de cache
    - **Property 2: Cache Devuelve Datos Válidos**
    - **Validates: Requirements 3.4, 3.7**

- [ ] 2. Implementar SourceRequestHandler con Circuit Breaker
  - [ ] 2.1 Crear handler con retry logic
    - executeWithRetry<T>(): genérico para cualquier request
    - Timeout configurable (default 3000ms)
    - _Requirements: 5.1, 5.4_

  - [ ] 2.2 Implementar Circuit Breaker
    - Si 5 fallos consecutivos: deshabilitar por 30 min
    - markDisabled(): desactivar source temporalmente
    - _Requirements: 5.1, 5.5_

  - [ ] 2.3 Implementar health monitoring
    - recordSuccess(): actualizar éxito
    - recordFailure(): incrementar contador
    - getHealth(): retornar SourceHealth
    - _Requirements: 5.3, 8.1_

  - [ ] 2.4 Implementar fallback a cache
    - Si source falla, retornar data en cache si existe
    - Si ambos fallan, retornar error
    - _Requirements: 5.6, 5.7_

  - [ ] 2.5 Implementar metrics collection
    - Registrar PerformanceMetric con duración, sourceId, success, cacheHit
    - _Requirements: 8.1, 8.2_

  - [ ]* 2.6 Escribir property test para timeout
    - **Property 1: Búsqueda Responde dentro del Timeout**
    - **Validates: Requirements 1.1, 1.6**

  - [ ]* 2.7 Escribir property test para circuit breaker
    - **Property 4: Circuit Breaker Protege de Cascada de Fallos**
    - **Validates: Requirements 5.1, 5.5**

  - [ ]* 2.8 Escribir property test para fallback a cache
    - **Property 5: Source Fallido Retorna Cache**
    - **Validates: Requirements 5.6, 5.7**

- [ ] 3. Implementar StreamingLinkResolver y validador
  - [ ] 3.1 Crear resolver con validación de links
    - resolveAndValidate(): timeout 2000ms
    - Obtener links de source, filtrar muertos
    - Validar el primero que funcione
    - _Requirements: 4.1, 4.2_

  - [ ] 3.2 Crear validador de links
    - validateLink(): HEAD request con timeout
    - Retornar StreamingLinkValidation (isValid, statusCode)
    - _Requirements: 4.1, 4.3_

  - [ ] 3.3 Implementar dead links tracking
    - isDeadLink(): verificar si está en dead_links list
    - markAsDead(): agregar con TTL 24 horas
    - _Requirements: 4.2, 4.7_

  - [ ] 3.4 Crear DeadLink entity en Room
    - url, addedAt, expiresAt (24 horas)
    - Query para obtener dead links vigentes
    - _Requirements: 4.2, 4.7_

  - [ ]* 3.5 Escribir property test para detección de links muertos
    - **Property 3: Links Inválidos se Marcan**
    - **Validates: Requirements 4.2, 4.7**

  - [ ]* 3.6 Escribir property test para expiración de links
    - **Property 10: Dead Links Expiran**
    - **Validates: Requirements 4.7**

- [ ] 4. Implementar ImageOptimizer con compresión
  - [ ] 4.1 Crear optimizer con WEBP compression
    - optimizeAndCache(): descargar y comprimir imagen
    - Soportar thumbnail (< 50KB) y full-res
    - _Requirements: 6.2, 6.4_

  - [ ] 4.2 Implementar descarga de imágenes
    - downloadImage(): obtener bitmap de URL
    - _Requirements: 6.1_

  - [ ] 4.3 Implementar compresión WEBP
    - Thumbnail: 60% quality
    - Full-res: 90% quality
    - Calcular compression ratio
    - _Requirements: 6.2, 6.4_

  - [ ] 4.4 Implementar caching de imágenes
    - Guardar en cacheDir con nombre basado en URL
    - Retornar ImageCache con metadata
    - _Requirements: 6.3, 6.7_

  - [ ]* 4.5 Escribir property test para compresión
    - **Property 6: Images se Comprimen Correctamente**
    - **Validates: Requirements 6.2, 6.4**

- [ ] 5. Checkpoint - Validar caching y link resolution
  - Ejecutar tests de CacheChain
  - Verificar que links inválidos se marcan
  - Verificar que imágenes se comprimen
  - Preguntar al usuario si hay dudas

- [ ] 6. Implementar búsqueda de catálogo optimizada
  - [ ] 6.1 Crear flujo de búsqueda con caching
    - Aceptar query del usuario
    - Buscar en Memory Cache (< 10ms)
    - Buscar en Disk Cache (< 100ms)
    - Si miss: llamar executeWithRetry a source
    - _Requirements: 1.1, 1.4, 1.5_

  - [ ] 6.2 Implementar timeout de 2000ms
    - Si source tarda > 2000ms, retornar cache
    - Si no hay cache, mostrar error
    - _Requirements: 1.1, 1.6_

  - [ ] 6.3 Implementar lazy loading de imágenes
    - Priorizar fetch de metadata (título, poster thumbnail)
    - Cargar full-res on-demand
    - _Requirements: 1.3, 1.7_

  - [ ] 6.4 Implementar invalidación de cache
    - TTL de 1 hora para resultados
    - Permitir manual refresh del usuario
    - _Requirements: 1.5, 3.6_

  - [ ]* 6.5 Escribir property test para búsqueda completa
    - **Property 1: Búsqueda Responde dentro del Timeout**
    - **Validates: Requirements 1.1, 1.6**

- [ ] 7. Implementar carga de lista de episodios
  - [ ] 7.1 Crear flujo con virtual scrolling
    - Cargar lista dentro de 1500ms
    - Renderizar solo episodios visibles
    - _Requirements: 2.1, 2.2_

  - [ ] 7.2 Implementar paginación on-demand
    - Fetch siguiente página cuando usuario scroll
    - _Requirements: 2.3_

  - [ ] 7.3 Implementar caching de episodios
    - Cachear lista completa con invalidación
    - _Requirements: 2.4_

  - [ ] 7.4 Implementar lazy loading de metadatos
    - Fetch mínimo: número, fecha, título
    - Fetch completo on-demand: synopsis, thumbnails
    - _Requirements: 2.5_

  - [ ] 7.5 Implementar batch loading
    - Mostrar primer batch en 800ms
    - Cargar siguientes asincronicamente
    - _Requirements: 2.6_

- [ ] 8. Implementar limpieza de storage
  - [ ] 8.1 Crear limpieza automática de cache
    - LRU eviction cuando Memory > 50MB
    - LRU eviction cuando Disk > 500MB
    - _Requirements: 3.2, 3.3_

  - [ ] 8.2 Crear limpieza manual
    - Permitir usuario limpiar cache global
    - Permitir limpiar cache por anime
    - _Requirements: 3.8_

  - [ ]* 8.3 Escribir property test para límites de storage
    - **Property 7: Storage Respeta Límites**
    - **Validates: Requirements 3.2, 3.3**

- [ ] 9. Implementar validación de datos de source
  - [ ] 9.1 Crear validador de estructura
    - Validar: títulos no vacíos, IDs únicos, URLs bien formadas
    - _Requirements: 9.1, 9.2_

  - [ ] 9.2 Implementar sanitización de datos
    - Limpiar null, duplicados, caracteres inválidos
    - Log de errores de validación
    - _Requirements: 9.2, 9.3_

  - [ ] 9.3 Implementar límites de campos
    - Título: 255 chars máximo
    - Descripción: 1000 chars máximo
    - URL: 2048 chars máximo
    - _Requirements: 9.3_

  - [ ] 9.4 Implementar validación de URLs
    - Rechazar URLs unsafes o excedan límite
    - _Requirements: 9.5_

  - [ ] 9.5 Implementar schema validation
    - Predefined templates para expected formats
    - _Requirements: 9.6_

- [ ] 10. Checkpoint - Validar búsqueda y carga de episodios
  - Buscar anime, verificar < 2s
  - Abrir lista de episodios, verificar < 1.5s
  - Scroll lista, verificar virtual scrolling funciona
  - Preguntar al usuario si hay dudas

- [ ] 11. Implementar BackgroundPrefetcher
  - [ ] 11.1 Crear prefetcher con WorkManager
    - schedulePrefetch(): agendar cuando charging + WiFi
    - _Requirements: 7.1, 7.2_

  - [ ] 11.2 Implementar constraints
    - Requiere: device idle, battery not low, metered network
    - _Requirements: 7.2, 7.5_

  - [ ] 11.3 Implementar PrefetchWorker
    - Prefetch siguientes 5 episodios de watchlist
    - Delay 2000ms entre fetches para no sobrecargar
    - _Requirements: 7.4_

  - [ ] 11.4 Implementar rate limiting
    - Máximo 2MB/min de bandwidth
    - Pausar si usuario inicia playback
    - _Requirements: 7.3_

  - [ ] 11.5 Implementar configuración de usuario
    - Disabled por default en mobile
    - Enabled en WiFi
    - Permitir deshabilitar
    - _Requirements: 7.5_

- [ ] 12. Implementar PerformanceMetricsCollector
  - [ ] 12.1 Crear collector con telemetría
    - recordMetric(): registrar PerformanceMetric
    - Timestamp, operation, duration, sourceId, success, cacheHit
    - _Requirements: 8.1, 8.2_

  - [ ] 12.2 Implementar agregación de métricas
    - Calcular promedios, percentiles
    - Identificar operaciones lentas (> 3000ms)
    - _Requirements: 8.3_

  - [ ] 12.3 Implementar debug overlay
    - Mostrar en-time: cache hit rate, average load times, bandwidth usage
    - Enabled con debug mode
    - _Requirements: 8.5_

  - [ ] 12.4 Implementar memory monitoring
    - Detectar memory leaks
    - Track memory usage durante browsing
    - _Requirements: 8.6_

- [ ] 13. Implementar multi-source parallelization
  - [ ] 13.1 Crear ejecutor paralelo
    - Ejecutar requests a múltiples sources en paralelo
    - Retornar resultado del primero que responda
    - _Requirements: 5.3, 8.8_

  - [ ] 13.2 Implementar race condition handling
    - Cancelar requests pendientes cuando uno completa
    - _Requirements: 5.3_

  - [ ]* 13.3 Escribir property test para parallelización
    - **Property 8: Multi-Source Paralleliza**
    - **Validates: Requirements 5.3**

- [ ] 14. Implementar configuración de rendimiento del usuario
  - [ ] 14.1 Crear settings de cache y rendimiento
    - Cache size limit, image quality, prefetch behavior, timeouts
    - _Requirements: 11.1_

  - [ ] 14.2 Implementar perfiles predefinidos
    - "Low performance device": reduce memory cache, limit concurrency
    - "High bandwidth": increase prefetch, longer TTL
    - _Requirements: 11.2, 11.4_

  - [ ] 14.3 Implementar detección automática de red
    - Mobile/cellular: reduce image quality, disable prefetch
    - WiFi: normal behavior
    - _Requirements: 11.3_

  - [ ] 14.4 Implementar per-source timeouts
    - Permitir configurar timeout por source
    - _Requirements: 11.5_

- [ ] 15. Testing y validación
  - [ ] 15.1 Escribir unit tests para CacheChain
    - Testear Memory → Disk fallback
    - Testear TTL expiration
    - _Requirements: 3.1, 3.4_

  - [ ] 15.2 Escribir unit tests para SourceRequestHandler
    - Testear retry policy
    - Testear circuit breaker
    - Testear fallback a cache
    - _Requirements: 5.1, 5.4_

  - [ ] 15.3 Escribir unit tests para validator
    - Testear validación de estructura
    - Testear sanitización
    - _Requirements: 9.1, 9.2_

  - [ ] 15.4 Escribir integration tests
    - Testear flujo completo: búsqueda → cache → episodios
    - Testear manejo de source failure
    - _Requirements: 1.1, 5.6_

  - [ ] 15.5 Escribir stress tests
    - Testear con muchos resultados (300+ episodios)
    - Testear memory limits
    - _Requirements: 2.1, 3.2_

- [ ] 16. Final checkpoint - Todas las propiedades validadas
  - Ejecutar suite completa de property tests
  - Verificar búsqueda < 2s, episodios < 1.5s
  - Verificar que sources fallidos no ralentizan app
  - Preguntar al usuario si hay dudas

- [ ] 17. Performance tuning
  - [ ] 17.1 Optimizar queries de cache
    - Crear índices apropiados en Room DB
    - _Requirements: 3.1_

  - [ ] 17.2 Optimizar image loading pipeline
    - Usar coroutines para parallelización
    - Configurar OkHttp connection pooling
    - _Requirements: 6.1_

  - [ ] 17.3 Benchmark y profiling
    - Medir tiempos reales en devices
    - Identificar bottlenecks
    - _Requirements: 8.5_

## Notes

- Tareas marcadas con `*` son opcionales y pueden saltarse para MVP
- Límites críticos: búsqueda < 2s, episodios < 1.5s, link validation < 2s
- Memory cache: 50MB, Disk cache: 500MB, Dead links TTL: 24 horas
- Multi-source parallelization es clave para resiliencia
- Image compression debe lograr ratio >= 30% del original
- Todas las operaciones de store/network deben ser asyncrónicas
- PerformanceMetrics deben registrarse para cada operación

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "3.4", "4.1"] },
    { "id": 1, "tasks": ["1.4", "1.5", "2.1", "2.2", "3.1", "3.2"] },
    { "id": 2, "tasks": ["2.3", "2.4", "2.5", "2.6", "2.7", "2.8"] },
    { "id": 3, "tasks": ["3.3", "3.5", "3.6", "4.2", "4.3"] },
    { "id": 4, "tasks": ["4.4", "4.5", "6.1", "6.2", "6.3"] },
    { "id": 5, "tasks": ["6.4", "6.5", "7.1", "7.2", "7.3"] },
    { "id": 6, "tasks": ["7.4", "7.5", "8.1", "8.2", "8.3"] },
    { "id": 7, "tasks": ["9.1", "9.2", "9.3", "9.4", "9.5"] },
    { "id": 8, "tasks": ["11.1", "11.2", "11.3", "11.4", "11.5"] },
    { "id": 9, "tasks": ["12.1", "12.2", "12.3", "12.4", "13.1", "13.2"] },
    { "id": 10, "tasks": ["13.3", "14.1", "14.2", "14.3", "14.4"] },
    { "id": 11, "tasks": ["15.1", "15.2", "15.3", "15.4", "15.5"] },
    { "id": 12, "tasks": ["17.1", "17.2", "17.3"] }
  ]
}
```
