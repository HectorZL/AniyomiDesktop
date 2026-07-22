# AniYomi Desktop

Aplicación desktop experimental basada en AniYomi/Mihon para explorar anime, cargar extensiones y reproducir vídeos desde Windows. El proyecto combina Kotlin Multiplatform, Compose Desktop y compatibilidad adicional para ejecutar extensiones que dependen de APIs de Android y `WebView`.

> **Estado:** proyecto en desarrollo. La versión desktop funciona como prototipo y todavía puede requerir ajustes según la fuente o el reproductor utilizado.

## Funciones actuales

- Interfaz desktop con Compose Multiplatform.
- Carga y administración de extensiones de anime.
- Puente `android.webkit.WebView` basado en JavaFX WebEngine para extensiones que necesitan JavaScript.
- Proxy HTTP para vídeos y playlists HLS.
- Reproducción de vídeos mediante Compose Media Player.
- Guardado y reanudación del progreso de reproducción por episodio.
- Historial y seguimiento de anime.
- Compatibilidad desktop para varias clases Android utilizadas por las extensiones.
- Integración de la fuente JKanime con extracción de directorio y episodios mediante sus respuestas web/AJAX.

## Requisitos

- Windows 10/11 de 64 bits.
- JDK 17 de 64 bits.
- Android SDK para compilar el módulo Android.
- Git.

El proyecto incluye Gradle Wrapper, por lo que no es necesario instalar Gradle manualmente.

## Ejecutar AniYomi Desktop

Desde PowerShell, en la raíz del proyecto:

```powershell
.\gradlew.bat :app-desktop:run
```

Para comprobar únicamente la compilación Kotlin desktop:

```powershell
.\gradlew.bat :app-desktop:compileKotlinDesktop
```

Para generar el JAR desktop:

```powershell
.\gradlew.bat :app-desktop:desktopJar
```

## Compilar la aplicación Android

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK de debug se genera dentro de `app/build/outputs/apk/debug/`.

## Estructura principal

```text
app/          Aplicación Android y UI compartida de AniYomi.
app-desktop/  Aplicación desktop Compose y adaptaciones para Windows.
source-api/   Modelos y contratos de las fuentes de anime.
i18n/         Recursos de internacionalización.
i18n-aniyomi/ Recursos de idioma específicos de AniYomi.
gradle/       Configuración y wrapper de Gradle.
```

## Desarrollo desktop

La configuración desktop utiliza JavaFX 21 para el WebEngine en Windows. Las clases de compatibilidad ubicadas en `app-desktop/src/desktopMain/kotlin/android/` proporcionan las APIs mínimas que algunas extensiones esperan encontrar en Android.

Cuando se trabaja con vídeo, el flujo principal es:

1. La extensión obtiene uno o más vídeos.
2. `VideoProxyServer` registra la URL y sirve el contenido al reproductor local.
3. El reproductor abre la URL proxy.
4. El progreso se guarda en el historial y se utiliza para reanudar el episodio.

## Limitaciones conocidas

- La configuración actual de JavaFX está orientada a Windows.
- Algunas extensiones dependen de JavaScript, cookies, DRM o servidores externos que pueden no funcionar en desktop.
- La extracción de servidores externos de JKanime todavía necesita trabajo adicional.
- El reproductor nativo puede presentar problemas con determinados formatos o URLs multimedia.
- El proyecto puede mostrar advertencias de APIs deprecated durante la compilación.

## Estado del repositorio

Este repositorio contiene el trabajo experimental de AniYomi Desktop, incluyendo el puente WebView, el proxy de vídeo y la función de progreso y reanudación de reproducción.
