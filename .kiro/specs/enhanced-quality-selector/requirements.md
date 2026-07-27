# Requisitos: Selector de Calidad Mejorado

## Introducción

AniYomi requiere un sistema robusto para detectar, presentar y gestionar múltiples opciones de calidad de video desde diferentes fuentes de anime. El selector de calidad debe proporcionar una interfaz intuitiva para que los usuarios seleccionen su calidad preferida, y guardar esa preferencia para futuras reproducciones. El sistema debe soportar diferentes formatos de resolución (720p, 1080p, 2K, 4K, etc.) y tipos de codificación.

## Glosario

- **Video_Source**: Fuente de proveedor de anime (extensión de fuente de Aniyomi)
- **Quality_Option**: Representación de una resolución/formato específico disponible en la fuente
- **Quality_Selector**: Interfaz UI para seleccionar entre opciones de calidad disponibles
- **Preferred_Quality**: Preferencia del usuario guardada para futuras reproducciones
- **Resolution**: Dimensión de video en píxeles (ej: 1920x1080, 1280x720)
- **Bitrate**: Velocidad de datos del video en Mbps
- **Video_Format**: Tipo de codificación (ej: H.264, H.265, VP9)
- **Playback_Engine**: Motor de reproducción (mpv-android en AniYomi)

## Requisitos

### Requisito 1: Detectar Calidades Disponibles

**Historia de Usuario:** Como usuario, quiero que AniYomi detecte automáticamente todas las opciones de calidad disponibles de la fuente de anime antes de reproducir.

#### Criterios de Aceptación

1. WHEN THE Video_Source provides an Episode, THE Quality_Detector SHALL fetch all available Quality_Options from the source within 3000ms.

2. THE Quality_Detector SHALL extract resolution, bitrate, and Video_Format information from each Quality_Option provided by the Video_Source.

3. WHEN THE Video_Source returns multiple Quality_Options, THE Quality_Detector SHALL parse and normalize the resolution labels (720p, 1080p, 2K, 4K) into a consistent format.

4. IF THE Video_Source provides Quality_Options without standard resolution labels, THEN THE Quality_Detector SHALL infer the resolution from available metadata (width, height, bitrate) within 500ms.

5. THE Quality_Detector SHALL store the complete list of detected Quality_Options in memory for the current Episode session.

6. WHEN THE Video_Source does not provide Quality_Options (single quality stream), THE Quality_Detector SHALL treat it as a single-quality Episode and proceed without displaying selector UI.

---

### Requisito 2: Presentar Interfaz de Selección de Calidad

**Historia de Usuario:** Como usuario, quiero seleccionar la calidad de video mediante una interfaz clara y accesible antes de reproducir.

#### Criterios de Aceptación

1. WHEN THE Episode has multiple Quality_Options available, THE Quality_Selector SHALL display an overlay UI with all available Quality_Options within 500ms of opening the player.

2. THE Quality_Selector SHALL list each Quality_Option with its resolution, bitrate (if available), and Video_Format in a readable format.

3. THE Quality_Selector UI SHALL be positioned in the player control area and dismissible without selecting a quality.

4. WHEN THE User taps a Quality_Option, THE Quality_Selector SHALL highlight the selected option visually.

5. WHEN THE User confirms their quality selection by tapping "Apply" or "OK", THE Playback_Engine SHALL load the selected Quality_Option within 2000ms.

6. IF THE Playback_Engine fails to load the selected Quality_Option, THEN THE Quality_Selector SHALL display an error message and revert to the previous Quality_Option, allowing the user to retry or select an alternative.

7. WHERE THE User dismisses the Quality_Selector without selecting, THE Playback_Engine SHALL use the Preferred_Quality or the highest available quality as fallback.

---

### Requisito 3: Guardar Preferencia de Calidad

**Historia de Usuario:** Como usuario, quiero que AniYomi recuerde mi preferencia de calidad para que no tenga que seleccionarla en cada episodio.

#### Criterios de Aceptación

1. WHEN THE User selects a Quality_Option and confirms, THE Storage_Layer SHALL save the selected quality as Preferred_Quality within 500ms.

2. THE Storage_Layer SHALL save the Preferred_Quality at two levels: global (para toda la aplicación) y por-fuente (específica para cada Video_Source).

3. WHEN THE Storage_Layer saves Preferred_Quality, IT SHALL store the resolution label (720p, 1080p, etc.) rather than an index, ensuring compatibility if the quality list changes.

4. THE Storage_Layer SHALL save Preferred_Quality even if it differs from available Quality_Options for future episodes (e.g., user prefers 1080p even if not available).

5. WHEN THE User opens a new Episode, THE Quality_Selector SHALL pre-select the Preferred_Quality if it exists in the available Quality_Options.

---

### Requisito 4: Aplicar Preferencia Automáticamente

**Historia de Usuario:** Como usuario, quiero que se use mi preferencia de calidad automáticamente sin pedirme confirmación cada vez.

#### Criterios de Aceptación

1. WHEN THE User has set a Preferred_Quality and opens an Episode with matching Quality_Options, THE Playback_Engine SHALL automatically load the matching quality without displaying the Quality_Selector.

2. WHEN THE Playback_Engine auto-loads the Preferred_Quality, IT SHALL display a brief notification (2 seconds) indicating the selected quality.

3. IF THE Preferred_Quality is not available in the current Episode's Quality_Options, THEN THE Quality_Selector SHALL display all available options and allow the user to select.

4. WHERE THE Preferred_Quality matches a Quality_Option (by resolution label), THE Playback_Engine SHALL select it automatically without user interaction.

5. IF THE Video_Source provides Quality_Options but none match the Preferred_Quality, THEN THE Playback_Engine SHALL select the closest available quality by resolution (downgrade to lower quality if necessary, upgrade if Preferred_Quality is higher).

---

### Requisito 5: Actualizar Preferencia Durante Reproducción

**Historia de Usuario:** Como usuario, quiero poder cambiar la calidad incluso durante la reproducción sin interrumpir significativamente la experiencia.

#### Criterios de Aceptación

1. WHEN THE User opens the Quality_Selector during playback, THE Quality_Selector SHALL display the current Quality_Options with the currently playing quality highlighted.

2. WHEN THE User selects a different Quality_Option during playback, THE Playback_Engine SHALL seek to the current Playback_Position in the new quality stream within 3000ms.

3. WHEN THE Playback_Engine switches to a new Quality_Option, IT SHALL pause playback momentarily (less than 1 second) while buffering the new quality.

4. WHEN THE User confirms a new Quality_Option during playback, THE Storage_Layer SHALL update the Preferred_Quality.

5. IF THE Playback_Engine encounters a buffering delay while switching quality (more than 5 seconds), THEN THE Quality_Selector SHALL display a message indicating buffering status.

---

### Requisito 6: Validar Compatibilidad de Calidad

**Historia de Usuario:** Como usuario, quiero que AniYomi valide que la calidad seleccionada sea compatible con mi dispositivo antes de intentar reproducir.

#### Criterios de Aceptación

1. WHEN THE User selects a Quality_Option, THE Playback_Engine SHALL validate that the Video_Format (H.264, H.265, VP9) is supported by the device within 500ms.

2. IF THE selected Video_Format is not supported by the device, THEN THE Quality_Selector SHALL display a warning and suggest compatible alternatives.

3. WHEN THE User's device does not support high-resolution playback (4K, 2K due to hardware limitations), THE Quality_Selector SHALL filter out unsupported resolutions before display.

4. THE Playback_Engine SHALL check device capabilities (codecs, max resolution, max bitrate) once during app startup and cache the results.

5. IF THE Playback_Engine attempts to play an unsupported Quality_Option, THEN IT SHALL automatically downgrade to the highest supported quality and log the downgrade event.

---

### Requisito 7: Gestionar Fuentes sin Múltiples Calidades

**Historia de Usuario:** Como usuario, quiero que AniYomi maneje correctamente las fuentes que solo ofrecen una calidad de video.

#### Criterios de Aceptación

1. WHEN THE Video_Source provides only one Quality_Option, THE Quality_Selector SHALL NOT display any UI overlay.

2. WHEN THE Video_Source provides no Quality_Options metadata, THE Playback_Engine SHALL attempt playback directly without quality selection.

3. WHEN THE Video_Source initially reports no Quality_Options but later provides them during loading, THE Quality_Selector SHALL display available options to the User.

4. THE Quality_Selector SHALL gracefully handle Quality_Sources that change their Quality_Options format or structure.

---

### Requisito 8: Mostrar Información de Calidad Actual

**Historia de Usuario:** Como usuario, quiero ver qué calidad se está reproduciendo actualmente durante el playback.

#### Criterios de Aceptación

1. WHEN THE Episode is playing, THE Playback_Engine SHALL display the current Quality_Option (resolution and bitrate) in the player UI.

2. THE current quality display SHALL update within 500ms if the quality changes automatically due to buffering conditions.

3. WHERE THE Playback_Engine is in low-bandwidth mode and downgrading quality, IT SHALL indicate this status visually in the player.

4. WHEN THE User long-presses the quality display, IT SHALL show additional metadata: codec, bitrate, frame rate, and source name.

---

### Requisito 9: Manejo de Fallos en Carga de Calidad

**Historia de Usuario:** Como usuario, quiero que si la calidad seleccionada falla al cargar, el sistema intente automáticamente alternativas.

#### Criterios de Aceptación

1. IF THE Playback_Engine fails to load the selected Quality_Option (connection error, source unavailable), THEN IT SHALL automatically attempt the next closest Quality_Option within 3000ms.

2. WHEN THE Playback_Engine downgrades quality automatically, IT SHALL log the failure and notify the user via notification.

3. IF ALL available Quality_Options fail to load, THEN THE Playback_Engine SHALL display an error message with options to retry or cancel.

4. THE Playback_Engine SHALL implement exponential backoff when retrying failed Quality_Options (first retry: 500ms, second: 1000ms, third: 2000ms).

5. WHEN THE connection is restored after a Quality_Option failure, THE Playback_Engine SHALL attempt to upgrade back to the Preferred_Quality.

---

### Requisito 10: Integración con Preferencias de Ancho de Banda

**Historia de Usuario:** Como usuario con ancho de banda limitado, quiero que AniYomi adapte la calidad según mis preferencias de consumo de datos.

#### Criterios de Aceptación

1. WHERE THE User enables "data saver mode" in settings, THE Quality_Selector SHALL prioritize lower bitrate Quality_Options and set them as default.

2. WHEN THE User has "data saver mode" enabled, THE Playback_Engine SHALL NOT automatically upgrade to higher quality Quality_Options even if network conditions improve.

3. IF THE User manually selects a high-bitrate Quality_Option while in "data saver mode", THEN THE Playback_Engine SHALL display a warning about potential data usage.

4. THE Storage_Layer SHALL maintain separate Preferred_Quality settings for normal mode and data saver mode.

