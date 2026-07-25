# Plan de implementación: actualización segura de extensiones

## Resumen

El trabajo se limita a `app-desktop` y amplía `ExtensionManager`, `main.kt`/`BrowseTab`/`ExtensionsSection`, `SettingsTab` y `AppSettings` existentes. La secuencia mantiene el módulo compilable: primero introduce contratos y almacenamiento puro; después índices, versiones e inventario; luego la transacción recuperable; finalmente conecta controlador, arranque, UI, lotes y desinstalación. `ExtensionUpdateController` será un coordinador compartido, no otro administrador, y ninguna pantalla nueva sustituirá las pantallas existentes.

## Tareas

- [ ] 1. Preparar los contratos de dominio, la infraestructura de pruebas y el almacenamiento puro
  - [x] 1.1 Configurar el source set `desktopTest` y sus dependencias de prueba
    - Actualizar `app-desktop/build.gradle.kts` y el catálogo de versiones para usar `kotlin.test`, pruebas de coroutines y `io.kotest:kotest-property:5.9.1`, alineado con la versión de Kotest ya presente.
    - Establecer la convención de al menos 100 iteraciones y el nombre `Feature: actualizacion-segura-extensiones, Property N: ...` para cada propiedad.
    - Mantener todas las dependencias y fuentes de esta funcionalidad dentro del módulo de escritorio.
    - _Requisitos: 1.1_
  - [x] 1.2 Crear los modelos, resultados y puertos Kotlin independientes de Compose
    - Añadir en `eu.kanade.tachiyomi.extension.update.model` las identidades, repositorios, versiones, integridad, candidatos, inventario, confirmaciones, progreso, errores y estados descritos en el diseño.
    - Definir los contratos que ampliará `ExtensionManager`, incluido `ExtensionRuntimePort`, sin importar tipos de Compose ni crear una segunda fachada de gestión.
    - Conservar adaptadores para las APIs existentes mientras se migra incrementalmente el flujo.
    - _Requisitos: 1.1, 1.2, 6.2, 9.1, 9.2_
  - [-] 1.3 Implementar primitivas seguras de archivos y la disposición de estado local
    - Crear `update/storage/TransactionFileStore.kt` con rutas confinadas al directorio de extensiones, temporales en el mismo volumen, escrituras atómicas, `fsync`, movimientos recuperables y locks globales/por paquete.
    - Rechazar traversal, enlaces inesperados y nombres no válidos de Windows; no derivar rutas locales de nombres remotos.
    - Crear la estructura `.aniyomi-extension-state` sin dejar JAR de respaldo en la raíz escaneable.
    - _Requisitos: 7.1, 7.2, 7.7, 7.8, 7.9, 7.17, 7.18, 7.20_
  - [~] 1.4 Escribir pruebas obligatorias de las primitivas de almacenamiento
    - Probar en directorios temporales confinamiento, escritura atómica, fallo antes/después de movimiento, locks y limpieza segura sin tocar un JAR activo.
    - Incluir casos de nombres reservados y rutas de Windows mediante pruebas condicionadas por plataforma.
    - _Requisitos: 7.2, 7.8, 7.9, 7.17, 7.18, 7.20_

- [ ] 2. Implementar ajustes retrocompatibles, confianza y planificación de repositorios
  - [-] 2.1 Ampliar `AppSettings` y convertir su persistencia en atómica y migrable
    - Añadir `ExtensionUpdateSettings`, preferencia automática deshabilitada por defecto, URLs confiables y claves asociadas en `Models.kt`.
    - Extraer un `AppSettingsStore` comprobable que preserve `animeRepos`, `mangaRepos` y `blacklistedExtensions`, migre solo repositorios oficiales como confiables y no consulte red si falla la persistencia.
    - Mantener los adaptadores `loadSettings`/`saveSettings` para el código existente hasta completar el cableado.
    - _Requisitos: 1.5, 2.1, 2.2, 2.4, 2.5, 2.6, 2.7_
  - [~] 2.2 Implementar normalización, confianza y `RepositoryPlanner`
    - Crear la normalización canónica de URL y fusionar `animeRepos + mangaRepos` conservando primer orden y categorías.
    - Deduplicar por URL normalizada, excluir repositorios no confiables y tratar una URL editada como una identidad nueva no confiable.
    - Persistir un cambio de confianza antes de exponerlo a cualquier comprobación.
    - _Requisitos: 2.3, 2.4, 2.8, 3.1_
  - [~] 2.3 Escribir la prueba basada en propiedades de normalización
    - **Propiedad 1: Normalización canónica e idempotente**
    - Generar URLs equivalentes, distintas por consulta e inválidas; verificar identidad canónica e idempotencia.
    - **Valida: Requisitos 2.3**
  - [~] 2.4 Escribir la prueba basada en propiedades de round-trip de ajustes
    - **Propiedad 2: Round-trip de ajustes de actualización**
    - Generar ajustes válidos y comprobar que serializar/cargar conserva preferencias, orden, listas, confianza y claves.
    - **Valida: Requisitos 1.5, 2.1, 2.7**
  - [~] 2.5 Escribir la prueba basada en propiedades de migración
    - **Propiedad 3: Migración conservadora de confianza**
    - Generar configuraciones heredadas y comprobar defaults, conservación de listas y confianza exclusiva de repositorios oficiales normalizados.
    - **Valida: Requisitos 2.2, 2.5, 2.6, 2.7**
  - [~] 2.6 Escribir la prueba basada en propiedades del plan de consulta
    - **Propiedad 4: Plan confiable y sin duplicados**
    - Generar listas anime/manga y conjuntos de confianza; comprobar unicidad, orden de primera aparición y exclusión de URLs no confiables.
    - **Valida: Requisitos 2.8, 3.1**
  - [~] 2.7 Escribir pruebas de integración del almacén de ajustes
    - Verificar migración real sobre archivos temporales, reemplazo atómico, recuperación de corrupción y que un fallo de guardado revierta el cambio e impida el siguiente fetch.
    - _Requisitos: 2.1, 2.4, 2.5, 2.6, 2.7, 2.8_

- [ ] 3. Implementar índices retrocompatibles, versiones y selección determinista
  - [~] 3.1 Ampliar la consulta y el parseo de índices detrás de `ExtensionManager`
    - Añadir DTO nullable y `RepositoryIndexParser` que acepte el índice legado, ignore campos desconocidos, aísle entradas inválidas y distinga fallo del documento completo.
    - Resolver referencias APK de forma segura y producir `RepositoryIndexResult`; adaptar `fetchRepository` sin crear otro cliente o manager.
    - Garantizar que esta ruta solo obtiene índices/metadatos y nunca artefactos.
    - _Requisitos: 1.2, 3.5, 3.6, 5.1, 5.2, 5.3, 5.4_
  - [~] 3.2 Implementar `VersionComparator`
    - Comparar `versionCode` cuando exista en ambos lados; si no, comparar textos numéricos con `BigInteger`, prefijo `v` opcional y ceros finales.
    - Devolver `Unknown` sin comparación lexicográfica para cualquier par no comparable.
    - _Requisitos: 4.8, 4.9, 4.10_
  - [~] 3.3 Implementar `RemoteCandidateSelector`
    - Agrupar por `Package_ID`, seleccionar un máximo inequívoco, detectar integridad contradictoria y desempatar por fuerza de descriptor, rango persistido e índice ordinal.
    - Mantener el resultado independiente del orden de respuestas y bloquear candidatos ambiguos.
    - _Requisitos: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7_
  - [~] 3.4 Escribir la prueba basada en propiedades de selección máxima
    - **Propiedad 8: Selección máxima y determinista**
    - Permutar respuestas y entradas comparables; comprobar máximo, pertenencia al grupo, integridad más fuerte y desempate persistido.
    - **Valida: Requisitos 4.3, 4.4**
  - [~] 3.5 Escribir la prueba basada en propiedades de conflicto seguro
    - **Propiedad 9: Ambigüedad produce conflicto seguro**
    - Generar máximos no demostrables o integridad contradictoria y comprobar conflicto sin candidato accionable.
    - **Valida: Requisitos 4.5, 4.6, 4.7**
  - [~] 3.6 Escribir la prueba basada en propiedades del comparador
    - **Propiedad 10: Comparación de versión coherente**
    - Generar códigos, componentes arbitrariamente grandes, prefijos y textos; comprobar signo, ceros finales, `Unknown`, antisimetría y transitividad.
    - **Valida: Requisitos 4.8, 4.9, 4.10**
  - [~] 3.7 Escribir la prueba basada en propiedades de compatibilidad del índice
    - **Propiedad 13: Compatibilidad de índices hacia atrás y delante**
    - Generar entradas legadas válidas y campos desconocidos; comprobar que el dominio conocido no cambia.
    - **Valida: Requisitos 5.1, 5.2**
  - [~] 3.8 Escribir la prueba basada en propiedades de aislamiento de entradas
    - **Propiedad 14: Aislamiento de entradas inválidas**
    - Mezclar entradas válidas e inválidas y comprobar que solo se rechazan las inválidas con una incidencia por posición.
    - **Valida: Requisitos 5.3**
  - [~] 3.9 Escribir pruebas de integración HTTP y de parseo
    - Usar servidor local controlado para raíz mal formada/no lista, 200/304, timeout, cuerpo truncado, redirección no confiable y fallos parciales.
    - Comprobar que ninguna prueba de comprobación invoca descarga de APK y que el adaptador existente sigue compilando.
    - _Requisitos: 2.8, 3.5, 3.6, 5.1, 5.2, 5.3, 5.4_

- [ ] 4. Implementar metadatos locales e inventario unificado
  - [~] 4.1 Crear `LocalMetadataStore` y el escaneo de instalaciones
    - Leer/escribir sidecars atómicos con paquete, versión, origen, verificación, digest, tamaño y transacción.
    - Detectar JAR heredado por sidecar ausente/corrupto/incoherente e ignorar sidecars huérfanos al clasificar.
    - No inferir versiones desde nombres de archivo y no limpiar estado perteneciente a una transacción activa.
    - _Requisitos: 4.14, 4.16, 5.12, 7.10_
  - [~] 4.2 Implementar `InventoryBuilder` y las acciones derivadas
    - Fusionar instalaciones y candidatos en una fila por `Package_ID`; clasificar `Available`, `Installed`, `Outdated` y conflicto.
    - Producir etiquetas local/remota, categorías y acciones, excluyendo heredados del contador/lote pero permitiendo actualización manual desde versión desconocida.
    - _Requisitos: 4.1, 4.2, 4.7, 4.11, 4.12, 4.13, 4.14, 4.15, 4.16, 4.17, 4.18, 6.3, 6.4, 6.5, 6.6_
  - [~] 4.3 Escribir la prueba basada en propiedades de unión del inventario
    - **Propiedad 7: Unión de inventario con identidad única**
    - Generar JAR, sidecars y candidatos; comprobar una fila por identidad y neutralidad de sidecars sin JAR.
    - **Valida: Requisitos 4.1, 4.2, 4.16**
  - [~] 4.4 Escribir la prueba basada en propiedades de clasificación
    - **Propiedad 11: Clasificación completa del inventario**
    - Recorrer combinaciones local/remoto/relación y comprobar estados y etiquetas requeridas.
    - **Valida: Requisitos 4.11, 4.12, 4.13, 4.17, 4.18**
  - [~] 4.5 Escribir la prueba basada en propiedades de JAR heredados
    - **Propiedad 12: Tratamiento seguro de JAR heredados**
    - Generar sidecars ausentes, corruptos o incoherentes y comprobar versión desconocida, exclusión de contador/lote y única actualización manual elegible.
    - **Valida: Requisitos 4.14, 4.15, 6.6**
  - [~] 4.6 Escribir la prueba basada en propiedades de acciones
    - **Propiedad 17: Acciones derivadas sin contradicción**
    - Generar ítems de todos los estados y comprobar instalar/desinstalar/actualizar, incluida la prohibición por conflicto.
    - **Valida: Requisitos 4.7, 6.3, 6.4, 6.5, 6.6**
  - [~] 4.7 Escribir pruebas de integración de sidecars e inventario
    - Probar digest/tamaño incorrectos, corrupción, JAR sin índice, sidecar sin JAR y escrituras interrumpidas sobre directorios temporales.
    - _Requisitos: 4.14, 4.15, 4.16, 4.17, 4.18, 5.12_

- [~] 5. Punto de control de lógica pura
  - Asegurar que todas las pruebas pasen; consultar al usuario si surgen preguntas.

- [ ] 6. Implementar verificación de integridad y validación aislada del candidato
  - [~] 6.1 Añadir adquisición temporal y política estricta de integridad a `ExtensionManager`
    - Implementar descarga completa a `.part`, promoción a temporal completo y verificación SHA-256/Ed25519 sobre los mismos bytes antes de convertir.
    - Resolver claves por repositorio normalizado y `keyId`; bloquear descriptor mal formado/no confiable/no coincidente sin bypass y representar ausencia como no verificada.
    - Mantener la ruta final intacta y preparar el estado de verificación que se persistirá tras commit.
    - _Requisitos: 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.11, 5.12, 7.1, 7.2, 7.3, 7.4_
  - [~] 6.2 Implementar `IsolatedCandidateValidator` y su proceso JVM auxiliar
    - Validar JAR/ZIP, identidad y al menos una fuente mediante protocolo JSON, timeout y classpath mínimo; tratar crash, timeout, cero fuentes o error fatal como fallo.
    - Cerrar streams/classloaders y devolver solo un informe, sin registrar fuentes ni handles en el runtime principal.
    - Integrar la conversión existente `translateApkToJar` mediante el puerto del diseño y escribir siempre `candidate.jar` temporal.
    - _Requisitos: 1.2, 7.4, 7.5, 7.6, 7.12_
  - [~] 6.3 Escribir la prueba basada en propiedades de integridad
    - **Propiedad 15: Política total y estricta de integridad**
    - Generar bytes, hashes, pares de claves, firmas y mutaciones; comprobar todos los estados de la tabla y la obligación conjunta de hash+firma.
    - **Valida: Requisitos 5.5, 5.7, 5.8, 5.9, 5.10**
  - [~] 6.4 Escribir la prueba basada en propiedades de autorización sin integridad
    - **Propiedad 16: Compatibilidad sin integridad solo con autorización**
    - Generar confianza, conflicto, bloqueo y confirmación; comprobar que solo el caso confiable, elegible y confirmado puede continuar.
    - **Valida: Requisitos 5.6, 5.11, 6.9, 6.12**
  - [~] 6.5 Escribir la prueba basada en propiedades de aislamiento de validación
    - **Propiedad 21: La validación no publica fuentes**
    - Generar candidatos y resultados del auxiliar; comprobar invariancia de colecciones visibles y handles principales antes del commit.
    - **Valida: Requisitos 7.12**
  - [~] 6.6 Escribir pruebas de integración criptográficas y del proceso auxiliar
    - Usar vectores válidos/alterados y fixtures APK/JAR para éxito, JAR ilegible, identidad distinta, cero fuentes, carga fatal, timeout y terminación abrupta.
    - Verificar cierre del proceso y de todos los handles al finalizar cada caso.
    - _Requisitos: 5.7, 5.8, 5.9, 5.10, 7.3, 7.4, 7.5, 7.6, 7.12_

- [ ] 7. Implementar la transacción segura, commit y rollback
  - [~] 7.1 Crear el diario durable y la prefase transaccional de `ExtensionManager`
    - Implementar `TransactionJournalStore` y `ExtensionTransactionEngine` con locks, preflight, descarga, verificación, conversión, validación y respaldo verificado antes de cualquier cambio final.
    - Escribir cada transición durable antes del efecto correspondiente y mantener cancelable solo la fase previa a activación.
    - Emitir progreso tipado sin exponer rutas, secretos o stack traces.
    - _Requisitos: 1.2, 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 9.1_
  - [~] 7.2 Implementar activación, handles por paquete y commit posterior a recarga
    - Sustituir el seguimiento por nombre de JAR por handles de paquete/generación dentro de `ExtensionManager`; desvincular referencias y cerrar todos los classloaders antes de probar movilidad.
    - Respaldar, retirar, promover JAR/sidecar completos y llamar a `ExtensionRuntimePort.refreshExtensions`; informar éxito y limpiar solo tras recarga satisfactoria.
    - En Windows, abortar antes de sobrescribir si el JAR anterior sigue bloqueado; no usar GC como condición de corrección.
    - _Requisitos: 1.2, 1.6, 5.12, 7.7, 7.9, 7.10, 7.11, 7.12, 7.16, 7.17_
  - [~] 7.3 Implementar rollback de actualización e instalación
    - Restaurar byte a byte JAR y sidecar anteriores y recargar; para instalación nueva, retirar cualquier final incompleto y recargar el conjunto restante.
    - Convertir fallos o cancelaciones de la sección crítica en commit completo o rollback; conservar respaldo/diario si la restauración no puede terminar.
    - Limpiar temporales únicamente cuando el estado terminal lo permita.
    - _Requisitos: 7.13, 7.14, 7.15, 7.16, 7.18, 7.20, 9.3, 9.4_
  - [~] 7.4 Escribir la prueba basada en propiedades de prefase
    - **Propiedad 19: Prefase transaccional no destructiva y ordenada**
    - Inyectar fallos antes de promoción y comprobar orden de etapas, rutas separadas, respaldo previo y equivalencia del estado inicial.
    - **Valida: Requisitos 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8**
  - [~] 7.5 Escribir la prueba basada en propiedades de commit
    - **Propiedad 20: Commit coherente y posterior a recarga**
    - Generar ejecuciones terminales y comprobar JAR/sidecar coherentes, verificación persistida y recarga exitosa anterior al éxito.
    - **Valida: Requisitos 5.12, 7.9, 7.10, 7.11, 7.16**
  - [~] 7.6 Escribir la prueba basada en propiedades de rollback de actualización
    - **Propiedad 22: Rollback de actualización restaura identidad**
    - Inyectar cualquier fallo posterior a promoción y comprobar bytes, sidecar, recarga y limpieza equivalentes al estado inicial.
    - **Valida: Requisitos 7.13, 7.14, 7.16, 7.18**
  - [~] 7.7 Escribir la prueba basada en propiedades de rollback de instalación
    - **Propiedad 23: Rollback de instalación elimina el candidato**
    - Inyectar fallos tras crear finales y comprobar ausencia de JAR/sidecar/fuentes candidatas y limpieza de temporales.
    - **Valida: Requisitos 7.15, 7.16, 7.18**
  - [~] 7.8 Escribir la prueba basada en propiedades de limpieza de commit
    - **Propiedad 24: Éxito no deja estado transaccional residual**
    - Generar commits válidos y comprobar ausencia de APK, candidato, respaldo, retired JAR y diario activo.
    - **Valida: Requisitos 7.17**
  - [~] 7.9 Escribir la matriz obligatoria de fault injection transaccional
    - Fallar antes/después de escritura, `fsync`, backup, rename, sidecar, recarga y limpieza; verificar siempre estado anterior completo o candidato completo confirmado, nunca mezcla irrecuperable.
    - Cubrir instalación y actualización, incluido `refreshExtensions` fallido.
    - _Requisitos: 7.1–7.18_
  - [~] 7.10 Escribir pruebas obligatorias de reemplazo, rollback y classloaders en Windows
    - Mantener handles de archivo/classloader reales abiertos y comprobar que el JAR no se trunca ni sobrescribe, que el fallo ocurre antes de promoción o restaura el respaldo y que cerrar todos los handles permite el rename.
    - Verificar identidad byte a byte del JAR y sidecar tras rollback y ausencia de fuentes del candidato en el proceso principal.
    - Ejecutar estas pruebas solo en Windows, pero mantenerlas como cobertura requerida del módulo.
    - _Requisitos: 7.6, 7.7, 7.8, 7.9, 7.12, 7.13, 7.14, 7.16, 7.20_

- [ ] 8. Implementar recuperación previa a carga
  - [~] 8.1 Crear la recuperación idempotente de transacciones interrumpidas
    - Inspeccionar diarios antes de la primera carga y restaurar/eliminar según la etapa durable; terminar limpieza de commits solo tras validar digest/fingerprint.
    - En restauración imposible, conservar respaldo y diario, crear bloqueo de recuperación y excluir únicamente ese paquete de carga.
    - Exponer reintento de recuperación y permitir cargar el resto de extensiones.
    - _Requisitos: 7.19, 7.20_
  - [~] 8.2 Escribir la prueba basada en propiedades de recuperación
    - **Propiedad 25: Recuperación idempotente y segura**
    - Generar cierres en cada estado durable y comprobar restauración anterior o `RecoveryPending`, exclusión de carga e idempotencia al repetir.
    - **Valida: Requisitos 7.19, 7.20**
  - [~] 8.3 Escribir la matriz obligatoria de reinicio y recuperación
    - Recrear `ExtensionManager` desde disco en cada etapa para instalación, actualización, sidecar heredado, rollback interrumpido y commit con limpieza incompleta.
    - En Windows, incluir restauración bloqueada, conservación del backup y reintento posterior tras liberar el classloader/handle.
    - _Requisitos: 7.19, 7.20, 9.4, 9.11_

- [~] 9. Punto de control transaccional
  - Asegurar que todas las pruebas pasen; consultar al usuario si surgen preguntas.

- [ ] 10. Implementar el controlador compartido, comprobaciones y confirmaciones
  - [~] 10.1 Crear `ExtensionUpdateController` y coordinar comprobaciones
    - Exponer un único `StateFlow<ExtensionUpdateState>` con comprobación, inventario, operaciones, recuperación, revisiones y resultados.
    - Implementar arranque/manual con `RepositoryPlanner`, una sola comprobación concurrente, fetch de índices a través de `ExtensionManager`, agregación parcial e inventario recalculado.
    - No bloquear funciones UI independientes ni permitir que comprobar alcance descarga, conversión o transacción.
    - _Requisitos: 1.2, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10_
  - [~] 10.2 Implementar confirmaciones vigentes y exclusión mutua por paquete
    - Crear previews inmutables con fingerprint y revisiones; consumir una sola vez, cancelar sin efectos y revalidar índice/confianza/estado justo antes de descargar.
    - Devolver reconfirmación/exclusión si cambia el candidato y serializar mutaciones por paquete y activación global antes de delegar en `ExtensionManager`.
    - Mapear progreso y resultados al estado, sin ejecutar reglas de dominio desde Compose.
    - _Requisitos: 5.6, 5.11, 6.7, 6.8, 6.9, 6.10, 6.11, 6.12, 8.9, 8.10, 9.1, 9.2_
  - [~] 10.3 Escribir la prueba basada en propiedades de comprobación no destructiva
    - **Propiedad 5: Una comprobación nunca adquiere artefactos**
    - Generar índices, actualizaciones y fallos con espías de puertos; comprobar que solo se invoca fetch de metadatos.
    - **Valida: Requisitos 3.5, 3.10**
  - [~] 10.4 Escribir la prueba basada en propiedades de agregación parcial
    - **Propiedad 6: Agregación parcial e indicador veraz**
    - Generar combinaciones de éxitos/fallos; comprobar contribución de éxitos, marca incompleta, contador exacto y `Failed` sin cero falso.
    - **Valida: Requisitos 3.6, 3.7, 3.8, 3.9**
  - [~] 10.5 Escribir la prueba basada en propiedades de confirmación
    - **Propiedad 18: Confirmación completa, vigente y de un solo uso**
    - Generar candidatos/revisiones y comprobar contenido, cancelación, consumo único, invalidación por cambios y única vía hacia descarga.
    - **Valida: Requisitos 6.8, 6.9, 6.10, 6.11, 6.12, 8.10**
  - [~] 10.6 Escribir pruebas del controlador con red y reloj falsos
    - Cubrir comprobación automática habilitada/deshabilitada, manual, sin repositorios confiables, una consulta por URL normalizada, fallos parciales/totales, snapshot obsoleto no accionable y notificación sin transacción.
    - Verificar que una confirmación cancelada o stale no descarga y que una operación activa bloquea una segunda mutación del paquete.
    - _Requisitos: 2.8, 3.1–3.10, 6.7–6.12, 8.9, 8.10_

- [ ] 11. Integrar `refreshExtensions` y el arranque existente
  - [~] 11.1 Convertir `refreshExtensions` en la implementación suspendible de `ExtensionRuntimePort`
    - Refactorizar la función de `main.kt` para construir snapshots completos en IO, publicar anime/manga/JAR/errores como una unidad lógica y devolver fallo sin snapshot parcial.
    - Implementar `detach`, respetar `blacklistedExtensions` y recuperación pendiente, y cerrar handles de paquete mediante `ExtensionManager` antes de reemplazo/desinstalación.
    - Mantener las colecciones y flujo de carga existentes en lugar de crear un runtime paralelo.
    - _Requisitos: 1.2, 1.6, 7.11, 7.12, 7.14, 7.16, 9.7, 9.11_
  - [~] 11.2 Cablear una única instancia del controlador en `MainScreen`
    - Ordenar el inicio: cargar/migrar ajustes, configurar directorio, recuperar transacciones y borrados diferidos, refrescar, publicar inventario local y finalmente comprobar una vez si está habilitado.
    - Pasar el mismo estado/controlador al indicador, `BrowseTab`/`ExtensionsSection` y `SettingsTab`; retirar sus consultas/comparaciones independientes de extensiones.
    - Mantener cargables las extensiones no afectadas cuando haya recuperación pendiente.
    - _Requisitos: 1.3, 1.4, 1.5, 1.6, 2.1, 3.1, 3.2, 7.19, 7.20, 9.11_
  - [~] 11.3 Escribir la prueba basada en propiedades de reconciliación
    - **Propiedad 28: Reconciliación posterior y exclusión de carga**
    - Generar operaciones terminales y comprobar reinventory/indicador, vuelta a disponible, versión restaurada y ausencia de fuentes para lista negra/recuperación pendiente.
    - **Valida: Requisitos 9.3, 9.4, 9.10, 9.11**
  - [~] 11.4 Escribir pruebas de integración del runtime y el orden de arranque
    - Verificar intercambio all-or-nothing de fuentes anime/manga, errores de carga, blacklist, recuperación antes de carga y comprobación automática exactamente una vez.
    - Usar espías para demostrar que toda mutación confirmada o rollback llama a la operación existente de refresh en el orden requerido.
    - _Requisitos: 1.6, 3.1, 3.2, 7.11, 7.14, 7.16, 7.19, 7.20, 9.7, 9.10, 9.11_

- [ ] 12. Ampliar las pantallas existentes, configuración y confirmaciones
  - [~] 12.1 Añadir controles de actualización en `SettingsTab`
    - Incorporar el switch de comprobación automática y controles de confianza junto a cada repositorio existente, mostrando URL original/normalizada, oficialidad y claves/fingerprint.
    - Persistir antes de cambiar el estado efectivo; revertir visualmente e impedir comprobación si falla el guardado.
    - Mantener la edición actual de directorio, `animeRepos` y `mangaRepos`.
    - _Requisitos: 1.4, 1.5, 2.1, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_
  - [~] 12.2 Sustituir la lógica por repositorio de `ExtensionsSection` por el inventario compartido
    - Mantener `BrowseTab > Extensiones`; añadir búsqueda, filtros de estado/categoría/idioma/repositorio, orden estable y una sola tarjeta por paquete.
    - Mostrar nombre, paquete, versiones, origen y verificación; representar conflicto, heredado, remoto no disponible y desinstalación pendiente con acciones derivadas del controlador.
    - Añadir el indicador no bloqueante con contador, incompleto, fallo, spinner, última comprobación y reintento, sin iniciar actualizaciones.
    - _Requisitos: 1.3, 3.4, 3.7, 3.8, 3.9, 3.10, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 9.9_
  - [~] 12.3 Añadir confirmaciones, progreso y resultados a la UI existente
    - Mostrar diálogos individuales de instalar/actualizar/desinstalar con paquete, cambio de versión, origen y expectativa de verificación; cancelar o descartar no debe producir efectos.
    - Representar etapas y resultados por paquete, mensajes seguros, restauración, recuperación pendiente y reconfirmación cuando cambie el candidato.
    - No ofrecer bypass para integridad bloqueada ni invocar directamente descarga/transacción desde composables.
    - _Requisitos: 6.7, 6.8, 6.9, 6.10, 6.11, 6.12, 9.1, 9.2, 9.3, 9.4, 9.5, 9.9_
  - [~] 12.4 Escribir pruebas Compose obligatorias de las pantallas ampliadas
    - Probar filtros y unicidad, acciones por estado, conflicto/heredado, etiquetas de versión/verificación, indicador parcial/fallido y controles de confianza.
    - Probar que cancelación no descarga, candidato stale solicita nueva confirmación, persistencia fallida revierte el switch y la UI no bloquea acciones no dependientes durante una comprobación.
    - _Requisitos: 1.3, 1.4, 2.3, 2.4, 3.4, 3.7, 3.8, 3.9, 6.1–6.12, 9.1, 9.2_
  - [ ]* 12.5 Añadir pruebas Compose complementarias de presentación y accesibilidad
    - Cubrir navegación por teclado, semántica de controles, textos largos, listas vacías y combinaciones de filtros sin duplicar la cobertura funcional obligatoria.
    - _Requisitos: 3.4, 6.1, 6.2_

- [ ] 13. Implementar actualización masiva independiente y tolerante a fallos
  - [~] 13.1 Añadir planificación y ejecución masiva al controlador
    - Seleccionar solo `Outdated` elegibles del snapshot exitoso actual, deduplicar por paquete, congelar fingerprints y crear una única confirmación.
    - Ejecutar transacciones independientes secuencialmente; revalidar cada elemento, continuar tras fallo/exclusión y producir exactamente un resultado por paquete.
    - Reutilizar los mismos locks, confirmaciones y `ExtensionManager.installOrUpdate` del flujo individual.
    - _Requisitos: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 8.9, 8.10_
  - [~] 13.2 Integrar «Actualizar todas» y su resumen en `ExtensionsSection`
    - Mostrar una sola confirmación con todas las filas, advertencias de integridad y cancelación total sin transacciones.
    - Mostrar progreso y resumen final de éxito, fallo, rollback o exclusión con motivo, sin crear una pantalla paralela.
    - _Requisitos: 8.1, 8.2, 8.3, 8.7, 9.1, 9.2_
  - [~] 13.3 Escribir la prueba basada en propiedades del lote
    - **Propiedad 26: Lote independiente y tolerante a fallos**
    - Generar duplicados y patrones arbitrarios de éxito/fallo; comprobar cero transacciones al cancelar, máximo una por paquete, continuidad y un resultado terminal por confirmado.
    - **Valida: Requisitos 8.3, 8.4, 8.5, 8.6, 8.7**
  - [~] 13.4 Escribir la prueba basada en propiedades de elegibilidad y exclusión mutua
    - **Propiedad 27: Elegibilidad masiva, procedencia y exclusión mutua**
    - Generar inventarios/snapshots y comprobar aparición exacta de la acción, filas exactas, exclusión de repositorios fallidos y ausencia de mutaciones simultáneas del mismo paquete.
    - **Valida: Requisitos 8.1, 8.2, 8.8, 8.9**
  - [~] 13.5 Escribir pruebas de integración del lote con scheduler controlado
    - Cubrir candidato/trust/local cambiados, fallo de una transacción, rollback, repositorio parcialmente fallido y continuación de los elementos restantes.
    - Verificar resumen Compose y recálculo de inventario/indicador tras cada resultado terminal.
    - _Requisitos: 8.1–8.10, 9.10_

- [ ] 14. Integrar desinstalación segura y diferida reutilizando el flujo existente
  - [~] 14.1 Ampliar la desinstalación existente de `ExtensionManager`
    - Aceptar un comando confirmado, adquirir el mismo lock por paquete, ejecutar `detach`, cerrar todos los classloaders y reutilizar los reintentos de borrado existentes.
    - Eliminar sidecar solo tras borrado físico; si Windows mantiene el bloqueo, persistir lista negra y `pending-removals.json`, conservar metadatos y completar antes de cargar en el siguiente inicio.
    - Invocar `refreshExtensions` y reconciliar solo después de persistir el resultado necesario.
    - _Requisitos: 1.2, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11_
  - [~] 14.2 Cablear confirmación, progreso y resultado de desinstalación
    - Reutilizar el controlador y la tarjeta existentes para solicitar confirmación, bloquear una segunda mutación y mostrar éxito o reinicio requerido.
    - Retirar la entrada de lista negra y el sidecar pendiente solo cuando el borrado físico se confirme; recalcular inventario e indicador.
    - _Requisitos: 6.4, 8.9, 9.1, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11_
  - [~] 14.3 Escribir pruebas obligatorias de desinstalación y classloaders en Windows
    - Mantener un handle/classloader abierto para forzar borrado diferido; comprobar JAR intacto, lista negra persistida, fuentes excluidas y mensaje de reinicio.
    - Recrear el manager tras liberar el bloqueo y comprobar borrado físico, limpieza de sidecar/pendiente/lista negra y refresh posterior.
    - _Requisitos: 9.5, 9.6, 9.7, 9.8, 9.9, 9.11_
  - [~] 14.4 Escribir pruebas de integración de coherencia posterior a desinstalación
    - Usar espías del runtime y almacenamiento para comprobar confirmación, cancelación, progreso, persistencia antes de refresh, inventario recalculado y exclusión de lista negra.
    - _Requisitos: 9.1, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10, 9.11_

- [ ] 15. Completar la validación automatizada del módulo de escritorio
  - [~] 15.1 Crear una suite de aceptación de componentes de extremo a extremo
    - Ejercitar mediante controlador, `ExtensionManager`, runtime falso/real controlado y directorios temporales: comprobación, instalación, actualización, rollback, recuperación, lote y desinstalación.
    - Verificar que no existe otra ruta que descargue o mutile JAR fuera de una confirmación vigente y que las pantallas existentes consumen el mismo estado.
    - _Requisitos: 1.1–1.6, 2.1–2.8, 3.1–3.10, 4.1–4.18, 5.1–5.12, 6.1–6.12, 7.1–7.20, 8.1–8.10, 9.1–9.11_
  - [~] 15.2 Validar compilación, pruebas y matriz obligatoria de Windows
    - Ejecutar las tareas no interactivas de compilación y `desktopTest`; corregir regresiones del módulo sin iniciar la aplicación ni un watcher.
    - Asegurar que la automatización de Windows ejecuta explícitamente reemplazo, rollback, recuperación, classloaders y desinstalación diferida, y que las demás plataformas omiten solo los casos dependientes de locks de Windows.
    - _Requisitos: 1.1–1.6, 7.1–7.20, 9.5–9.11_
  - [ ]* 15.3 Añadir pruebas de estrés complementarias
    - Aumentar iteraciones de fault injection, órdenes concurrentes, lotes grandes y múltiples diarios sin convertir esta tarea opcional en sustituto de la cobertura obligatoria anterior.
    - _Requisitos: 7.19, 7.20, 8.4, 8.5, 8.6, 8.9_

- [~] 16. Punto de control final
  - Asegurar que todas las pruebas pasen; consultar al usuario si surgen preguntas.

## Notas

- Las tareas marcadas con `*` son pruebas complementarias opcionales; todas las pruebas basadas en las 28 propiedades, la matriz transaccional y las validaciones de Windows son obligatorias.
- Cada propiedad debe vivir en una clase/archivo de prueba dedicado para conservar trazabilidad y permitir la paralelización indicada en el grafo.
- Ninguna tarea crea otra aplicación, otro administrador o pantallas paralelas: `ExtensionManager` sigue siendo la fachada de operaciones y `ExtensionUpdateController` solo coordina estado/casos de uso.
- Las validaciones son automatizadas; no se requiere ejecutar manualmente la aplicación ni desplegarla.
- Cada tarea está pensada como un prompt incremental para un agente de código y debe dejar `app-desktop` compilable antes de avanzar a la siguiente ola.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1"] },
    { "id": 2, "tasks": ["1.4", "2.2", "2.4"] },
    { "id": 3, "tasks": ["2.3", "2.5", "2.6", "2.7"] },
    { "id": 4, "tasks": ["3.1", "3.2", "4.1"] },
    { "id": 5, "tasks": ["3.3", "3.7", "3.8", "4.7"] },
    { "id": 6, "tasks": ["3.4", "3.5", "3.6", "3.9", "4.2"] },
    { "id": 7, "tasks": ["4.3", "4.4", "4.5", "4.6"] },
    { "id": 8, "tasks": ["6.1", "6.2"] },
    { "id": 9, "tasks": ["6.3", "6.4", "6.5", "6.6"] },
    { "id": 10, "tasks": ["7.1"] },
    { "id": 11, "tasks": ["7.2"] },
    { "id": 12, "tasks": ["7.3"] },
    { "id": 13, "tasks": ["7.4", "7.5", "7.6", "7.7", "7.8", "7.9", "7.10"] },
    { "id": 14, "tasks": ["8.1"] },
    { "id": 15, "tasks": ["8.2", "8.3"] },
    { "id": 16, "tasks": ["10.1"] },
    { "id": 17, "tasks": ["10.2"] },
    { "id": 18, "tasks": ["10.3", "10.4", "10.5", "10.6"] },
    { "id": 19, "tasks": ["11.1"] },
    { "id": 20, "tasks": ["11.2"] },
    { "id": 21, "tasks": ["11.3", "11.4"] },
    { "id": 22, "tasks": ["12.1", "12.2"] },
    { "id": 23, "tasks": ["12.3"] },
    { "id": 24, "tasks": ["12.4", "12.5"] },
    { "id": 25, "tasks": ["13.1"] },
    { "id": 26, "tasks": ["13.2"] },
    { "id": 27, "tasks": ["13.3", "13.4", "13.5"] },
    { "id": 28, "tasks": ["14.1"] },
    { "id": 29, "tasks": ["14.2"] },
    { "id": 30, "tasks": ["14.3", "14.4"] },
    { "id": 31, "tasks": ["15.1"] },
    { "id": 32, "tasks": ["15.2", "15.3"] }
  ]
}
```
