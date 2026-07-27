# Requisitos: Persistencia de Progreso de Reproducción

## Introducción

AniYomi necesita guardar y restaurar la posición actual de reproducción de videos para proporcionar una experiencia de usuario continua. Esta funcionalidad permite que los usuarios cierren y abran la aplicación, reinicien el dispositivo, o cambien entre episodios sin perder su progreso. El sistema debe soportar múltiples tipos de reinicio (normal, fuerza-reinicio, reinicio del sistema) y garantizar que la posición se restaure correctamente.

## Glosario

- **Video_Player**: Componente de reproducción multimedia de AniYomi (mpv-android)
- **Playback_Position**: Posición actual del video en milisegundos desde el inicio
- **Episode**: Episodio específico del anime con identificador único
- **Storage_Layer**: Capa de persistencia de datos (base de datos local o preferencias compartidas)
- **Resume_Threshold**: Umbral de tiempo (en segundos) para determinar si se debe ofrecer reanudar
- **Playback_Session**: Sesión individual de reproducción con metadatos asociados

## Requisitos

### Requisito 1: Guardar Posición de Reproducción

**Historia de Usuario:** Como usuario, quiero que AniYomi guarde mi posición actual de reproducción, para que pueda continuar desde donde lo dejé la próxima vez que abra el episodio.

#### Criterios de Aceptación

1. WHEN THE Video_Player detects a playback position change, THE Storage_Layer SHALL write the Playback_Position to persistent storage within 1000ms.

2. WHEN THE Video_Player is playing, THE Storage_Layer SHALL update the Playback_Position every 5 seconds to ensure recent progress is captured.

3. WHEN THE User pauses the Video_Player, THE Storage_Layer SHALL immediately capture the current Playback_Position.

4. THE Storage_Layer SHALL store the following metadata with each Playback_Position: Episode identifier, timestamp of last update, total video duration, and device identifier.

5. IF THE Storage_Layer write operation fails, THEN THE Video_Player SHALL continue playback without interruption, and retry the write operation on the next position update.

---

### Requisito 2: Restaurar Posición al Reabrir Episodio

**Historia de Usuario:** Como usuario, quiero que cuando abra nuevamente un episodio, se me pregunta si deseo continuar desde donde lo dejé o comenzar desde el inicio.

#### Criterios de Aceptación

1. WHEN THE User opens an Episode that has a saved Playback_Position, THE Video_Player SHALL detect the saved position within 500ms.

2. IF THE saved Playback_Position is greater than 10 seconds (Resume_Threshold), THEN THE Video_Player SHALL display a resume dialog offering two options: "Resume" and "Start from beginning".

3. WHEN THE User selects "Resume", THE Video_Player SHALL seek to the saved Playback_Position and begin playback within 1500ms.

4. WHEN THE User selects "Start from beginning", THE Video_Player SHALL discard the saved Playback_Position, seek to position 0, and begin playback.

5. IF THE saved Playback_Position exceeds the total video duration, THEN THE Video_Player SHALL default to seeking to 90% of the total duration to prevent seeking beyond the video.

6. WHERE the Episode has no saved Playback_Position, THE Video_Player SHALL begin playback from position 0 without displaying the resume dialog.

---

### Requisito 3: Soportar Reinicio Normal

**Historia de Usuario:** Como usuario, quiero que mi progreso se preserve cuando cierro y reabrí la aplicación normalmente.

#### Criterios de Aceptación

1. WHEN THE User closes the app normally (back button, app switcher), THE Video_Player SHALL save the current Playback_Position to Storage_Layer before termination.

2. WHEN THE Storage_Layer detects app termination, THE Storage_Layer SHALL flush all pending playback data within 2000ms.

3. WHEN THE User reopens the app and navigates to the same Episode, THE Storage_Layer SHALL retrieve the saved Playback_Position within 500ms.

4. THE Storage_Layer SHALL preserve Playback_Position data even if the app is removed from recent apps (not force-stopped).

---

### Requisito 4: Soportar Fuerza-Reinicio de Aplicación

**Historia de Usuario:** Como usuario, incluso si fuerzo-reinicio la aplicación, quiero que mi progreso se preserve (con máximo 5 segundos de pérdida).

#### Criterios de Aceptación

1. WHEN THE User force-stops the app, THE Storage_Layer SHALL have persisted Playback_Position data that is at most 5 seconds older than the last known position.

2. WHEN THE User reopens the app after a force-stop, THE Video_Player SHALL retrieve the last saved Playback_Position and display the resume dialog.

3. IF THE Storage_Layer detects no recent Playback_Position data (older than 2 minutes), THEN THE Video_Player SHALL treat the session as expired and begin playback from position 0.

---

### Requisito 5: Soportar Reinicio del Sistema

**Historia de Usuario:** Como usuario, quiero que mi progreso se preserve incluso cuando el dispositivo se reinicia.

#### Criterios de Aceptación

1. WHEN THE device reboots, THE Storage_Layer SHALL preserve all Playback_Position data in persistent storage that survives system shutdown.

2. WHEN THE User reopens the app after a device reboot, THE Storage_Layer SHALL retrieve the saved Playback_Position within 1000ms.

3. THE Storage_Layer SHALL use database transactions or atomic writes to ensure Playback_Position data consistency during device shutdown.

4. IF THE Storage_Layer detects corrupted Playback_Position data after reboot, THEN THE Video_Player SHALL discard the corrupted entry and begin playback from position 0.

---

### Requisito 6: Eliminar Posición Guardada al Completar Episodio

**Historia de Usuario:** Como usuario, quiero que cuando termine un episodio, se limpie la posición guardada para empezar fresco si lo vuelvo a ver.

#### Criterios de Aceptación

1. WHEN THE Video_Player reaches 95% of the video duration, THE Storage_Layer SHALL consider the Episode as watched.

2. WHEN THE Episode is marked as watched, THE Storage_Layer SHALL delete the saved Playback_Position for that Episode.

3. WHEN THE User reopens a watched Episode, THE Video_Player SHALL begin playback from position 0 without showing a resume dialog.

4. THE Storage_Layer SHALL preserve the Episode "watched" status independent of the Playback_Position.

---

### Requisito 7: Manejo de Sincronización Multi-Dispositivo

**Historia de Usuario:** Como usuario que veo anime en múltiples dispositivos, quiero que el progreso se guarde de forma que no cause conflictos entre dispositivos.

#### Criterios de Aceptación

1. THE Storage_Layer SHALL store the device identifier with each Playback_Position record.

2. WHEN THE User opens an Episode on a different device, THE Video_Player SHALL recognize the device change and not automatically resume from a position saved on another device.

3. WHERE an Episode has saved positions from multiple devices, THE Storage_Layer SHALL display the most recent position saved on the current device, not positions from other devices.

4. IF THE current device has no saved position for an Episode but another device does, THEN THE Video_Player SHALL begin playback from position 0 on the current device.

---

### Requisito 8: Recuperación ante Fallos de Escritura

**Historia de Usuario:** Como usuario, quiero que AniYomi maneje gracefully los fallos de escritura en la base de datos sin interrumpir mi reproducción.

#### Criterios de Aceptación

1. IF THE Storage_Layer write operation fails, THEN THE Video_Player SHALL log the error and attempt to retry the write on the next position update.

2. WHEN THE Storage_Layer has consecutive write failures (3 or more in 30 seconds), THE Video_Player SHALL display a non-blocking warning notification.

3. IF THE Storage_Layer becomes unavailable, THEN THE Video_Player SHALL continue playback and queue position updates in memory.

4. WHEN THE Storage_Layer becomes available again, THE Video_Player SHALL flush all queued position updates to persistent storage.

5. THE Storage_Layer SHALL validate Playback_Position data before writing (position must be between 0 and total duration).

