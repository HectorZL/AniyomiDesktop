# Documento de Requisitos

## Introducción

Este documento especifica el comportamiento observable para descubrir, presentar y aplicar cambios de extensiones de forma recuperable y compatible con datos previos. El alcance queda restringido a la aplicación de escritorio. Este documento no define la implementación.

## Glosario

- **app-desktop**: Módulo JVM con interfaz Compose Desktop al que se limita esta funcionalidad.
- **AppSettings**: Modelo persistente existente de configuración de app-desktop.
- **ExtensionManager**: Componente existente de app-desktop que consulta índices, descarga artefactos, convierte APK a JAR, carga fuentes y desinstala extensiones.
- **refreshExtensions**: Operación existente de app-desktop que vuelve a leer los JAR activos y reconstruye las colecciones de fuentes de anime y manga.
- **Sistema_de_Actualización_de_Extensiones**: Funcionalidad de app-desktop especificada en este documento y coordinada con ExtensionManager, refreshExtensions y AppSettings.
- **Package_ID**: Identificador estable de una extensión contenido en el campo `pkg` del índice y usado como identidad entre repositorios.
- **APK**: Artefacto descargable publicado por un repositorio de extensiones.
- **JAR**: Artefacto ejecutable por app-desktop obtenido al convertir un APK.
- **Extensión**: Unidad instalable identificada por un Package_ID y capaz de aportar una o más fuentes de anime o manga.
- **Fuente**: Proveedor de contenido de anime o manga cargado desde un JAR.
- **Pantalla_de_Extensiones_Existente**: Sección «Extensiones» de la pantalla «Examinar» de app-desktop.
- **Pantalla_de_Configuración_Existente**: Pantalla «Ajustes» de app-desktop que ya administra el directorio y los repositorios de extensiones.
- **Repositorio_de_Extensiones**: URL configurada en `animeRepos` o `mangaRepos` que apunta a un índice JSON.
- **Repositorio_Oficial**: Repositorio de extensiones incluido como valor predeterminado por app-desktop.
- **Repositorio_de_Confianza**: Repositorio de extensiones que el usuario ha autorizado explícitamente o que app-desktop reconoce como Repositorio_Oficial durante una migración.
- **URL_Normalizada**: URL de repositorio después de eliminar espacios exteriores y fragmentos, normalizar esquema, host, puerto predeterminado y segmentos de ruta, y conservar la consulta.
- **Índice_de_Repositorio**: Documento JSON de un Repositorio_de_Extensiones, incluido el formato existente `index.min.json`.
- **Entrada_de_Índice**: Registro de una extensión dentro de un Índice_de_Repositorio.
- **Artefacto_Remoto**: APK indicado por una Entrada_de_Índice y resuelto respecto del Repositorio_de_Extensiones de origen.
- **JAR_Instalado**: Archivo `{Package_ID}.jar` presente en el directorio de extensiones configurado.
- **Metadatos_Locales**: Registro persistente asociado a un JAR_Instalado que contiene como mínimo Package_ID, versión textual, Orden_de_Versión opcional, repositorio de origen y Estado_de_Verificación.
- **JAR_Heredado**: JAR_Instalado que carece de Metadatos_Locales válidos por haber sido instalado antes de esta funcionalidad o por tener metadatos ausentes, corruptos o incoherentes.
- **Versión_Local**: Versión recuperada de los Metadatos_Locales del JAR_Instalado.
- **Versión_Remota**: Versión textual publicada por una Entrada_de_Índice.
- **Orden_de_Versión**: Entero no negativo opcional publicado y persistido para ordenar versiones sin interpretar el texto de versión.
- **Versión_Textual_Comparable**: Versión textual que, tras retirar espacios y un prefijo `v` opcional, contiene solo componentes enteros no negativos separados por puntos; la comparación evalúa componentes numéricos de izquierda a derecha y considera como cero los componentes finales ausentes.
- **Candidato_Remoto**: Entrada_de_Índice seleccionada de forma determinista para representar un Package_ID y su Artefacto_Remoto.
- **Disponible**: Estado de inventario de un Package_ID con Candidato_Remoto y sin JAR_Instalado.
- **Instalada**: Estado de inventario de un Package_ID con JAR_Instalado para el que no existe una versión remota demostrablemente superior.
- **Desactualizada**: Estado de inventario de un Package_ID con JAR_Instalado y Candidato_Remoto cuya versión es demostrablemente superior a la Versión_Local.
- **Conflicto_de_Repositorio**: Estado en el que varias entradas del mismo Package_ID no permiten seleccionar un Candidato_Remoto inequívoco por versiones no comparables o por descriptores de integridad contradictorios para la misma versión.
- **Metadatos_de_Integridad**: Campos opcionales del índice que describen un hash, una firma digital o ambos para los bytes del Artefacto_Remoto.
- **Hash**: Resumen criptográfico del Artefacto_Remoto calculado con un algoritmo declarado y admitido por app-desktop.
- **Firma_Digital**: Firma del Artefacto_Remoto comprobable con una Clave_de_Confianza asociada al repositorio.
- **Clave_de_Confianza**: Clave pública incluida por app-desktop o asociada explícitamente a un Repositorio_de_Confianza.
- **Metadatos_de_Integridad_Inválidos**: Metadatos_de_Integridad mal formados, incompletos, basados en un algoritmo no admitido, asociados a una clave ausente o no confiable, o cuyo hash o firma no coincide con el Artefacto_Remoto.
- **Estado_de_Verificación**: Resultado visible de integridad con uno de los valores «Verificado por hash», «Verificado por firma», «Verificado por hash y firma», «No verificado por el índice» o «Bloqueado por integridad».
- **Artefacto_Temporal**: Archivo de descarga completo almacenado en una ruta distinta de la ruta del JAR_Instalado.
- **JAR_Candidato**: JAR generado desde un Artefacto_Temporal y almacenado en una ruta distinta de la ruta final `{Package_ID}.jar`.
- **Validación_del_Candidato**: Comprobación de que el APK corresponde al Package_ID esperado, la conversión finalizó, el JAR_Candidato es un archivo JAR legible y ExtensionManager puede cargar al menos una Fuente sin un error fatal de archivo, identidad o carga.
- **Copia_de_Respaldo**: Copia byte a byte del JAR_Instalado anterior y de sus Metadatos_Locales, conservada hasta confirmar la nueva carga.
- **Transacción_de_Extensión**: Instalación o actualización aislada que termina con el nuevo JAR y sus metadatos activos, o con el estado anterior restaurado.
- **Comprobación_Automática**: Preferencia persistente que habilita la consulta de repositorios durante el inicio sin autorizar instalaciones ni actualizaciones.
- **Indicador_de_Actualizaciones**: Elemento visible que comunica el número de extensiones Desactualizadas o el estado de una comprobación incompleta o fallida.
- **Actualización_Masiva**: Acción «Actualizar todas» aplicada a un conjunto confirmado de extensiones Desactualizadas.
- **Fallo_Parcial**: Resultado en el que falla un repositorio o una extensión y las demás consultas u operaciones conservan resultados independientes.
- **Lista_Negra**: Colección persistente existente `blacklistedExtensions` que impide cargar determinados Package_ID.

## Requisitos

### Requisito 1: Alcance e integración con componentes existentes

**Historia de usuario:** Como responsable de app-desktop, quiero ampliar el flujo existente de extensiones, para que la actualización segura no cree administradores ni pantallas paralelas.

#### Criterios de aceptación

1. EL Sistema_de_Actualización_de_Extensiones DEBERÁ proporcionar todas las capacidades de este documento exclusivamente en app-desktop.
2. EL Sistema_de_Actualización_de_Extensiones DEBERÁ canalizar la consulta, instalación, actualización, carga y desinstalación de extensiones mediante ExtensionManager.
3. EL Sistema_de_Actualización_de_Extensiones DEBERÁ ampliar la Pantalla_de_Extensiones_Existente para presentar el inventario y las acciones de extensiones.
4. EL Sistema_de_Actualización_de_Extensiones DEBERÁ ampliar la Pantalla_de_Configuración_Existente para administrar la Comprobación_Automática y los Repositorios_de_Confianza.
5. EL Sistema_de_Actualización_de_Extensiones DEBERÁ leer y persistir `animeRepos`, `mangaRepos` y `blacklistedExtensions` mediante AppSettings.
6. CUANDO una operación confirmada cambie el conjunto de JAR activos, EL Sistema_de_Actualización_de_Extensiones DEBERÁ usar refreshExtensions para reconstruir las fuentes de anime y manga.

### Requisito 2: Configuración, confianza y migración

**Historia de usuario:** Como usuario de escritorio, quiero controlar las comprobaciones automáticas y los repositorios confiables, para que la aplicación solo consulte orígenes autorizados.

#### Criterios de aceptación

1. EL Sistema_de_Actualización_de_Extensiones DEBERÁ persistir el estado habilitado o deshabilitado de la Comprobación_Automática en AppSettings.
2. CUANDO AppSettings no contenga una preferencia de Comprobación_Automática, EL Sistema_de_Actualización_de_Extensiones DEBERÁ cargar la Comprobación_Automática como deshabilitada.
3. EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar cada Repositorio_de_Extensiones configurado con un control de confianza identificable por URL_Normalizada.
4. CUANDO el usuario cambie la confianza de un Repositorio_de_Extensiones, EL Sistema_de_Actualización_de_Extensiones DEBERÁ persistir la selección antes de la siguiente comprobación.
5. CUANDO una configuración existente carezca de datos de confianza, EL Sistema_de_Actualización_de_Extensiones DEBERÁ marcar los Repositorios_Oficiales como Repositorios_de_Confianza.
6. CUANDO una configuración existente carezca de datos de confianza, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener los repositorios no oficiales como no confiables hasta una autorización explícita.
7. CUANDO una configuración existente se migre, EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar los valores de `animeRepos`, `mangaRepos` y `blacklistedExtensions`.
8. MIENTRAS un Repositorio_de_Extensiones no sea un Repositorio_de_Confianza, EL Sistema_de_Actualización_de_Extensiones DEBERÁ excluir el repositorio de las consultas y de la adquisición de Artefactos_Remotos.

### Requisito 3: Comprobación de actualizaciones y fallos de repositorio

**Historia de usuario:** Como usuario de escritorio, quiero conocer las actualizaciones disponibles al iniciar o bajo demanda, para decidir cuándo aplicarlas.

#### Criterios de aceptación

1. MIENTRAS la Comprobación_Automática esté habilitada, CUANDO app-desktop complete el inicio, EL Sistema_de_Actualización_de_Extensiones DEBERÁ consultar una vez cada URL_Normalizada de Repositorio_de_Confianza presente en `animeRepos` o `mangaRepos`.
2. MIENTRAS la Comprobación_Automática esté deshabilitada, EL Sistema_de_Actualización_de_Extensiones DEBERÁ omitir las consultas de repositorios durante el inicio.
3. CUANDO el usuario solicite una comprobación manual, EL Sistema_de_Actualización_de_Extensiones DEBERÁ consultar los Repositorios_de_Confianza aunque la Comprobación_Automática esté deshabilitada.
4. MIENTRAS una comprobación esté en curso, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener operativas las funciones de la interfaz no dependientes del resultado de la comprobación.
5. EL Sistema_de_Actualización_de_Extensiones DEBERÁ limitar cada comprobación a índices y metadatos sin descargar Artefactos_Remotos.
6. SI falla la consulta o el análisis de un Índice_de_Repositorio, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar los resultados obtenidos de los demás Repositorios_de_Confianza.
7. CUANDO finalice una comprobación con al menos un repositorio procesado correctamente, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar en el Indicador_de_Actualizaciones el número de Package_ID clasificados como Desactualizada.
8. CUANDO una comprobación tenga un Fallo_Parcial de repositorio, EL Sistema_de_Actualización_de_Extensiones DEBERÁ identificar el resultado como incompleto junto al Indicador_de_Actualizaciones.
9. SI fallan todos los Repositorios_de_Confianza consultados, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar un estado de comprobación fallida en lugar de un contador cero.
10. CUANDO una comprobación detecte al menos una extensión Desactualizada, EL Sistema_de_Actualización_de_Extensiones DEBERÁ notificar la disponibilidad de actualizaciones sin iniciar una Transacción_de_Extensión.

### Requisito 4: Inventario, versiones, deduplicación y JAR heredados

**Historia de usuario:** Como usuario de extensiones, quiero un inventario inequívoco con versiones locales y remotas, para distinguir instalaciones, disponibilidades y actualizaciones reales.

#### Criterios de aceptación

1. CUANDO el inventario se reconstruya, EL Sistema_de_Actualización_de_Extensiones DEBERÁ combinar los JAR_Instalados con las Entradas_de_Índice de todos los Repositorios_de_Confianza procesados correctamente.
2. EL Sistema_de_Actualización_de_Extensiones DEBERÁ representar cada Package_ID una sola vez aunque el Package_ID aparezca en varios repositorios o en los índices de anime y manga.
3. CUANDO varias Entradas_de_Índice de un Package_ID tengan versiones mutuamente comparables, EL Sistema_de_Actualización_de_Extensiones DEBERÁ seleccionar como Candidato_Remoto la versión mayor.
4. CUANDO varias entradas equivalentes permanezcan después de comparar versiones e integridad, EL Sistema_de_Actualización_de_Extensiones DEBERÁ seleccionar la entrada del primer Repositorio_de_Confianza según el orden persistido de repositorios.
5. SI dos entradas de la misma versión mayor publican hashes o firmas contradictorios, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ marcar el Package_ID con Conflicto_de_Repositorio.
6. SI las versiones remotas de un Package_ID no permiten un orden inequívoco, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ marcar el Package_ID con Conflicto_de_Repositorio.
7. MIENTRAS un Package_ID tenga Conflicto_de_Repositorio, EL Sistema_de_Actualización_de_Extensiones DEBERÁ bloquear las acciones Instalar y Actualizar para el Package_ID.
8. CUANDO la Versión_Local y la Versión_Remota contengan Orden_de_Versión, EL Sistema_de_Actualización_de_Extensiones DEBERÁ comparar los valores de Orden_de_Versión como enteros.
9. CUANDO falte Orden_de_Versión y ambas versiones sean Versión_Textual_Comparable, EL Sistema_de_Actualización_de_Extensiones DEBERÁ comparar los componentes numéricos definidos por Versión_Textual_Comparable.
10. SI una Versión_Local y una Versión_Remota no son comparables, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ tratar el resultado de versión como desconocido.
11. SI existe un Candidato_Remoto y no existe un JAR_Instalado, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ clasificar el Package_ID como Disponible.
12. SI existe un JAR_Instalado y la versión seleccionada es demostrablemente superior a la Versión_Local, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ clasificar el Package_ID como Desactualizada.
13. SI existe un JAR_Instalado y no existe una versión seleccionada demostrablemente superior, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ clasificar el Package_ID como Instalada.
14. CUANDO un JAR_Heredado se detecte, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar la Versión_Local como «Desconocida».
15. MIENTRAS un JAR_Heredado carezca de una Versión_Local comparable, EL Sistema_de_Actualización_de_Extensiones DEBERÁ excluir el Package_ID del Indicador_de_Actualizaciones y de la Actualización_Masiva.
16. SI existen Metadatos_Locales sin el JAR_Instalado correspondiente, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ ignorar los Metadatos_Locales al clasificar el Package_ID.
17. CUANDO un JAR_Instalado no aparezca en ningún índice procesado correctamente, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar la Versión_Remota como «No disponible».
18. CUANDO no exista un JAR_Instalado, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar la Versión_Local como «No instalada».

### Requisito 5: Compatibilidad de índices y verificación de integridad

**Historia de usuario:** Como usuario que instala código de terceros, quiero que cada control de integridad publicado sea obligatorio, sin perder compatibilidad con índices antiguos que no publican controles.

#### Criterios de aceptación

1. CUANDO un Índice_de_Repositorio use el formato existente sin Orden_de_Versión ni Metadatos_de_Integridad, EL Sistema_de_Actualización_de_Extensiones DEBERÁ aceptar las Entradas_de_Índice que contengan los campos existentes requeridos.
2. CUANDO una Entrada_de_Índice contenga campos desconocidos, EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar la compatibilidad mediante la omisión de los campos desconocidos.
3. SI una Entrada_de_Índice carece de Package_ID, versión o referencia de APK válida, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ rechazar solamente la entrada inválida.
4. SI un Índice_de_Repositorio no es un documento JSON analizable, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ registrar un fallo para ese repositorio.
5. CUANDO un Candidato_Remoto no publique Metadatos_de_Integridad, EL Sistema_de_Actualización_de_Extensiones DEBERÁ asignar el estado «No verificado por el índice».
6. MIENTRAS un Candidato_Remoto tenga el estado «No verificado por el índice», EL Sistema_de_Actualización_de_Extensiones DEBERÁ permitir una instalación o actualización confirmada desde un Repositorio_de_Confianza.
7. CUANDO un Candidato_Remoto publique un Hash, EL Sistema_de_Actualización_de_Extensiones DEBERÁ comparar el Hash publicado con el Hash de los bytes completos del Artefacto_Temporal.
8. CUANDO un Candidato_Remoto publique una Firma_Digital, EL Sistema_de_Actualización_de_Extensiones DEBERÁ verificar la Firma_Digital de los bytes completos del Artefacto_Temporal con la Clave_de_Confianza asociada.
9. CUANDO un Candidato_Remoto publique Hash y Firma_Digital, EL Sistema_de_Actualización_de_Extensiones DEBERÁ exigir que ambas verificaciones sean satisfactorias.
10. SI un Candidato_Remoto contiene Metadatos_de_Integridad_Inválidos, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ asignar el estado «Bloqueado por integridad».
11. MIENTRAS un Candidato_Remoto tenga el estado «Bloqueado por integridad», EL Sistema_de_Actualización_de_Extensiones DEBERÁ impedir la modificación del JAR_Instalado asociado.
12. CUANDO finalice una verificación satisfactoria, EL Sistema_de_Actualización_de_Extensiones DEBERÁ persistir el Estado_de_Verificación correspondiente en los Metadatos_Locales.

### Requisito 6: Presentación de acciones y confirmación explícita

**Historia de usuario:** Como usuario de escritorio, quiero revisar el origen, las versiones y la verificación antes de aprobar una operación, para evitar cambios silenciosos.

#### Criterios de aceptación

1. EL Sistema_de_Actualización_de_Extensiones DEBERÁ permitir listar o filtrar por los estados Instalada, Disponible y Desactualizada en la Pantalla_de_Extensiones_Existente.
2. EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar para cada Package_ID el nombre, Package_ID, Versión_Local, Versión_Remota, repositorio de origen y Estado_de_Verificación.
3. CUANDO un Package_ID esté Disponible, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ofrecer la acción Instalar.
4. CUANDO un Package_ID tenga un JAR_Instalado, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ofrecer la acción Desinstalar.
5. CUANDO un Package_ID esté Desactualizada, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ofrecer la acción Actualizar.
6. CUANDO un JAR_Heredado tenga un Candidato_Remoto sin Conflicto_de_Repositorio, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ofrecer una acción Actualizar identificada como actualización desde versión desconocida.
7. CUANDO el usuario seleccione Instalar o Actualizar, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar una confirmación antes de descargar el Artefacto_Remoto.
8. EL Sistema_de_Actualización_de_Extensiones DEBERÁ incluir en cada confirmación el Package_ID, el cambio de versión, el Repositorio_de_Confianza y el Estado_de_Verificación esperado.
9. CUANDO una confirmación incluya un Candidato_Remoto sin Metadatos_de_Integridad, EL Sistema_de_Actualización_de_Extensiones DEBERÁ identificar el Artefacto_Remoto como «No verificado por el índice».
10. SI el usuario cancela o descarta una confirmación, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar sin cambios el JAR_Instalado y los Metadatos_Locales.
11. SI el Candidato_Remoto cambia después de la confirmación y antes de la descarga, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ solicitar una nueva confirmación para el candidato cambiado.
12. EL Sistema_de_Actualización_de_Extensiones DEBERÁ iniciar cada instalación o actualización únicamente a partir de una confirmación explícita asociada a la operación vigente.

### Requisito 7: Instalación y actualización transaccionales

**Historia de usuario:** Como usuario con extensiones instaladas, quiero que cada cambio se valide antes de activarse y que cualquier fallo restaure la versión anterior, para mantener las fuentes utilizables.

#### Criterios de aceptación

1. CUANDO el usuario confirme una instalación o actualización, EL Sistema_de_Actualización_de_Extensiones DEBERÁ descargar el Artefacto_Remoto completo como Artefacto_Temporal en una ruta distinta de `{Package_ID}.jar`.
2. MIENTRAS la descarga del Artefacto_Temporal esté incompleta, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener sin cambios la ruta final `{Package_ID}.jar`.
3. CUANDO finalice la descarga del Artefacto_Temporal, EL Sistema_de_Actualización_de_Extensiones DEBERÁ evaluar todos los Metadatos_de_Integridad publicados antes de convertir el APK.
4. CUANDO la evaluación de integridad sea satisfactoria o no existan Metadatos_de_Integridad, EL Sistema_de_Actualización_de_Extensiones DEBERÁ convertir el Artefacto_Temporal en un JAR_Candidato temporal.
5. CUANDO finalice la conversión, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ejecutar la Validación_del_Candidato antes de modificar la ruta final `{Package_ID}.jar`.
6. MIENTRAS la Validación_del_Candidato no haya finalizado satisfactoriamente, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener sin cambios el JAR_Instalado anterior.
7. CUANDO una actualización tenga un JAR_Instalado y un JAR_Candidato válido, EL Sistema_de_Actualización_de_Extensiones DEBERÁ crear una Copia_de_Respaldo antes de reemplazar el JAR_Instalado.
8. SI la creación de la Copia_de_Respaldo falla, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ finalizar la Transacción_de_Extensión con el JAR_Instalado anterior sin cambios.
9. CUANDO el JAR_Candidato sea válido y la Copia_de_Respaldo requerida esté completa, EL Sistema_de_Actualización_de_Extensiones DEBERÁ promover el JAR_Candidato de modo que la ruta final contenga un JAR completo.
10. CUANDO el JAR_Candidato ocupe la ruta final, EL Sistema_de_Actualización_de_Extensiones DEBERÁ persistir Metadatos_Locales correspondientes al JAR activo.
11. CUANDO el JAR_Candidato y los Metadatos_Locales estén activos, EL Sistema_de_Actualización_de_Extensiones DEBERÁ invocar refreshExtensions antes de informar éxito.
12. MIENTRAS una Transacción_de_Extensión no haya informado éxito, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener fuera de las colecciones visibles cualquier Fuente cargada solo para la Validación_del_Candidato.
13. SI una actualización falla después de modificar la ruta final, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ restaurar byte a byte el JAR_Instalado anterior y sus Metadatos_Locales desde la Copia_de_Respaldo.
14. CUANDO una actualización restaure una Copia_de_Respaldo, EL Sistema_de_Actualización_de_Extensiones DEBERÁ invocar refreshExtensions para volver a cargar las fuentes anteriores.
15. SI una instalación nueva falla después de crear la ruta final, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ retirar el JAR fallido y los Metadatos_Locales incompletos.
16. SI refreshExtensions falla para el JAR_Candidato, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ tratar el fallo como un fallo de la Transacción_de_Extensión.
17. CUANDO una Transacción_de_Extensión termine con éxito, EL Sistema_de_Actualización_de_Extensiones DEBERÁ eliminar los Artefactos_Temporales y la Copia_de_Respaldo de la transacción.
18. CUANDO una Transacción_de_Extensión termine con el estado anterior restaurado, EL Sistema_de_Actualización_de_Extensiones DEBERÁ eliminar los Artefactos_Temporales de la transacción.
19. SI app-desktop termina durante una Transacción_de_Extensión, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ restaurar el estado anterior antes de cargar extensiones en el siguiente inicio.
20. SI una restauración no puede completarse en el intento actual, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar la Copia_de_Respaldo, bloquear la carga del JAR_Candidato y mostrar el estado «Recuperación pendiente».

### Requisito 8: Actualización masiva, concurrencia y fallos parciales

**Historia de usuario:** Como usuario con varias extensiones desactualizadas, quiero actualizarlas en conjunto sin que un fallo dañe o detenga las demás, para resolver el mantenimiento con un resultado claro.

#### Criterios de aceptación

1. CUANDO exista al menos una extensión Desactualizada sin Conflicto_de_Repositorio, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ofrecer la acción «Actualizar todas».
2. CUANDO el usuario seleccione «Actualizar todas», EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar una única confirmación con cada Package_ID, cambio de versión, repositorio y Estado_de_Verificación incluido.
3. SI el usuario cancela la confirmación de la Actualización_Masiva, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ omitir todas las Transacciones_de_Extensión del conjunto.
4. CUANDO el usuario confirme la Actualización_Masiva, EL Sistema_de_Actualización_de_Extensiones DEBERÁ ejecutar como máximo una Transacción_de_Extensión por Package_ID.
5. EL Sistema_de_Actualización_de_Extensiones DEBERÁ aislar cada elemento de una Actualización_Masiva en una Transacción_de_Extensión independiente.
6. SI una Transacción_de_Extensión de una Actualización_Masiva falla, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ continuar con los elementos confirmados restantes.
7. CUANDO finalice una Actualización_Masiva, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar por Package_ID los resultados de éxito, fallo o exclusión con un motivo.
8. MIENTRAS una comprobación tenga Fallo_Parcial, EL Sistema_de_Actualización_de_Extensiones DEBERÁ limitar la Actualización_Masiva a Candidatos_Remotos obtenidos de repositorios procesados correctamente.
9. MIENTRAS una Transacción_de_Extensión esté activa para un Package_ID, EL Sistema_de_Actualización_de_Extensiones DEBERÁ bloquear una segunda instalación, actualización o desinstalación del mismo Package_ID.
10. SI un Package_ID deja de cumplir las condiciones confirmadas antes de iniciar la descarga, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ excluir el Package_ID y registrar el cambio como motivo.

### Requisito 9: Resultados, desinstalación y coherencia posterior

**Historia de usuario:** Como usuario de extensiones, quiero conocer el resultado de cada operación y conservar el comportamiento existente de desinstalación y recarga, para poder recuperar cualquier incidencia.

#### Criterios de aceptación

1. MIENTRAS una operación esté en curso, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar el estado de progreso del Package_ID afectado.
2. CUANDO una instalación o actualización termine, EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar el resultado y el Estado_de_Verificación final del Package_ID.
3. SI una instalación falla, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ mantener el Package_ID como Disponible cuando el Candidato_Remoto siga vigente.
4. SI una actualización falla y la restauración finaliza, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ mostrar la Versión_Local restaurada como Instalada.
5. CUANDO el usuario seleccione Desinstalar, EL Sistema_de_Actualización_de_Extensiones DEBERÁ solicitar confirmación antes de invocar la desinstalación existente de ExtensionManager.
6. CUANDO ExtensionManager elimine físicamente un JAR_Instalado, EL Sistema_de_Actualización_de_Extensiones DEBERÁ eliminar los Metadatos_Locales asociados.
7. CUANDO una desinstalación finalice, EL Sistema_de_Actualización_de_Extensiones DEBERÁ invocar refreshExtensions para reconstruir las fuentes activas.
8. SI ExtensionManager no puede eliminar un JAR_Instalado por un bloqueo del sistema, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ conservar el flujo existente de Lista_Negra y eliminación diferida.
9. SI una eliminación queda diferida, ENTONCES EL Sistema_de_Actualización_de_Extensiones DEBERÁ informar que app-desktop requiere un reinicio para completar la eliminación física.
10. CUANDO finalice una instalación, actualización o desinstalación, EL Sistema_de_Actualización_de_Extensiones DEBERÁ recalcular el inventario y el Indicador_de_Actualizaciones.
11. MIENTRAS un Package_ID permanezca en la Lista_Negra, EL Sistema_de_Actualización_de_Extensiones DEBERÁ impedir que refreshExtensions cargue las Fuentes del Package_ID.
