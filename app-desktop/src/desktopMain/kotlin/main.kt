import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeFlv
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Locale
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionInfo
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video


// AsyncImage loader for Compose Desktop
@Composable
fun AsyncImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var imageBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val client = Injekt.get<NetworkHelper>().client
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    client.newCall(request).execute().use { response: Response ->
                        if (response.isSuccessful) {
                            val bytes = response.body?.bytes()
                            if (bytes != null) {
                                val bufferedImage = ImageIO.read(bytes.inputStream())
                                if (bufferedImage != null) {
                                    val bitmap = bufferedImage.toComposeImageBitmap()
                                    withContext(Dispatchers.Main) {
                                        imageBitmap = bitmap
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fail silently
                }
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap!!,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

fun main() {
    // Initialize Injekt container with NetworkHelper singleton
    Injekt.addSingleton(NetworkHelper())
    Injekt.addSingleton(android.app.Application())
    Injekt.addSingleton(Json { ignoreUnknownKeys = true })

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Aniyomi Desktop (KMP Nativo - Multi-pestaña)"
        ) {
            var appSettings by remember { mutableStateOf(loadSettings()) }

            val primaryColor = when (appSettings.themeColor) {
                "Purple" -> Color(0xFF9C27B0)
                "Blue" -> Color(0xFF2196F3)
                "Green" -> Color(0xFF4CAF50)
                "Red" -> Color(0xFFE91E63)
                else -> Color(0xFFFF9800) // Orange
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = primaryColor,
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color(0xFFE3E3E3),
                    onSurface = Color(0xFFFFFFFF),
                    primaryContainer = primaryColor.copy(alpha = 0.3f),
                    onPrimaryContainer = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        appSettings = appSettings,
                        onSettingsChange = { newSettings ->
                            appSettings = newSettings
                            saveSettings(newSettings)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    appSettings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    var currentTab by remember { mutableStateOf("biblioteca") }
    var selectedAnime by remember { mutableStateOf<RealAnime?>(null) }
    var selectedMangaSource by remember { mutableStateOf<MangaSource?>(null) }
    var selectedEpisode by remember { mutableStateOf<RealEpisode?>(null) }
    var activeVideo by remember { mutableStateOf<RealVideo?>(null) }
    
    // Shared state managers loaded from disk
    val libraryList = remember { mutableStateListOf<RealAnime>().apply { addAll(loadLibrary()) } }
    val historyList = remember { mutableStateListOf<HistoryItem>().apply { addAll(loadHistory()) } }

    // Dynamic sources loaded from extensions
    val dynamicAnimeSources = remember { mutableStateListOf<AnimeHttpSource>() }
    val dynamicMangaSources = remember { mutableStateListOf<MangaSource>() }

    // Track installed JARs to update the UI reactively
    val installedJars = remember { mutableStateListOf<String>() }

    val scope = rememberCoroutineScope()

    fun refreshExtensions() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (appSettings.extensionDirPath.isNotEmpty()) {
                        eu.kanade.tachiyomi.extension.ExtensionManager.extensionsDir = File(appSettings.extensionDirPath)
                    }

                    val extDir = eu.kanade.tachiyomi.extension.ExtensionManager.extensionsDir

                    val files = extDir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()
                    val filteredFiles = files.filter { file ->
                        val pkgName = file.name.substringBeforeLast(".jar")
                        !appSettings.blacklistedExtensions.contains(pkgName)
                    }
                    val jarNames = filteredFiles.map { it.name }

                    val local = eu.kanade.tachiyomi.extension.ExtensionManager.loadLocalExtensions(appSettings.blacklistedExtensions)
                    println("[main.kt] Loaded local extensions: ${local.size} sources found.")

                    withContext(Dispatchers.Main) {
                        installedJars.clear()
                        installedJars.addAll(jarNames)
                        dynamicAnimeSources.clear()
                        dynamicMangaSources.clear()
                        local.forEach { loadedSource ->
                            when (loadedSource) {
                                is eu.kanade.tachiyomi.extension.ExtensionManager.LoadedSource.Anime -> {
                                    if (loadedSource.source is AnimeHttpSource) {
                                        dynamicAnimeSources.add(loadedSource.source)
                                    }
                                }
                                is eu.kanade.tachiyomi.extension.ExtensionManager.LoadedSource.Manga -> dynamicMangaSources.add(loadedSource.source)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    println("[main.kt] Error during startup extension loading: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    // Load local extensions on startup
    LaunchedEffect(Unit) {
        refreshExtensions()
    }

    // Auto-save library when list contents change
    LaunchedEffect(libraryList.toList()) {
        saveLibrary(libraryList)
    }

    // Auto-save history when list contents change
    LaunchedEffect(historyList.toList()) {
        saveHistory(historyList)
    }

    // Auto-create extensions directory
    LaunchedEffect(appSettings.extensionDirPath) {
        if (appSettings.extensionDirPath.isNotEmpty()) {
            try {
                val dir = File(appSettings.extensionDirPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val currentAnime = selectedAnime
    val currentEpisode = selectedEpisode
    if (activeVideo != null && currentEpisode != null && currentAnime != null) {
        // Player View
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val playerState = rememberVideoPlayerState()

            LaunchedEffect(activeVideo) {
                activeVideo?.let { video ->
                    val proxiedUrl = eu.kanade.tachiyomi.network.VideoProxyServer.registerVideo(video.url, video.headers)
                    playerState.openUri(proxiedUrl)
                }
            }

            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize()
            )

            // Controls overlay
            var showControls by remember { mutableStateOf(true) }
            
            // Auto-hide controls after 4 seconds of inactivity
            LaunchedEffect(showControls) {
                if (showControls) {
                    kotlinx.coroutines.delay(4000)
                    showControls = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showControls = !showControls
                    }
            ) {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Top Bar (smooth dark gradient)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                                    )
                                )
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                                .align(Alignment.TopStart),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        playerState.stop()
                                        activeVideo = null
                                    },
                                    modifier = Modifier.background(Color.White.copy(alpha = 0.15f), shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Volver",
                                        tint = Color.White
                                    )
                                }
                                Column {
                                    Text(
                                        text = currentAnime.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = currentEpisode.name,
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            
                            IconButton(
                                onClick = {
                                    try {
                                        Desktop.getDesktop().browse(URI(activeVideo!!.url))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Navegador",
                                    tint = Color.White
                                )
                            }
                        }

                        // Center Play/Pause button
                        IconButton(
                            onClick = {
                                if (playerState.isPlaying) {
                                    playerState.pause()
                                } else {
                                    playerState.play()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(72.dp)
                                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.25f), shape = CircleShape)
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        // Bottom Controls Bar (smooth dark gradient)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                    )
                                )
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                                .align(Alignment.BottomCenter)
                        ) {
                            // Subtitles & Audio Track Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Subtitle Selector
                                if (playerState.availableSubtitleTracks.isNotEmpty() || (activeVideo != null && activeVideo!!.subtitleTracks.isNotEmpty())) {
                                    var showSubtitleMenu by remember { mutableStateOf(false) }
                                    Box {
                                        Button(
                                            onClick = { showSubtitleMenu = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color.White.copy(alpha = 0.15f),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(20.dp)
                                        ) {
                                            Icon(Icons.Default.Subtitles, contentDescription = "Subtítulos", tint = Color.White)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = playerState.currentSubtitleTrack?.language ?: if (playerState.subtitlesEnabled) "Activos" else "Desactivados"
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showSubtitleMenu,
                                            onDismissRequest = { showSubtitleMenu = false },
                                            modifier = Modifier.background(Color(0xFF1E1E1E))
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Desactivar Subtítulos", color = Color.White) },
                                                onClick = {
                                                    playerState.subtitlesEnabled = false
                                                    playerState.disableSubtitles()
                                                    showSubtitleMenu = false
                                                }
                                            )
                                            // Embedded tracks
                                            playerState.availableSubtitleTracks.forEach { track ->
                                                DropdownMenuItem(
                                                    text = { Text(track.language.ifEmpty { "Desconocido" }, color = Color.White) },
                                                    onClick = {
                                                        playerState.subtitlesEnabled = true
                                                        playerState.selectSubtitleTrack(track)
                                                        showSubtitleMenu = false
                                                    }
                                                )
                                            }
                                            // External tracks from extension Video model
                                            activeVideo?.subtitleTracks?.forEach { track ->
                                                DropdownMenuItem(
                                                    text = { Text("[Ext] ${track.lang}", color = Color.White) },
                                                    onClick = {
                                                        playerState.subtitlesEnabled = true
                                                        val extTrack = SubtitleTrack(
                                                            label = track.lang,
                                                            language = track.lang,
                                                            src = track.url
                                                        )
                                                        playerState.selectSubtitleTrack(extTrack)
                                                        showSubtitleMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Progress Slider Row (full screen width slider)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Slider(
                                    value = playerState.sliderPos,
                                    onValueChange = {
                                        playerState.seekStart(it)
                                    },
                                    onValueChangeFinished = {
                                        playerState.seekFinished()
                                    },
                                    valueRange = 0f..1000f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Play/Pause, Skips, Volume, and Fullscreen Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Play/Pause button
                                    IconButton(
                                        onClick = {
                                            if (playerState.isPlaying) playerState.pause() else playerState.play()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Reproducir/Pausar",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }

                                    // Skip Back 10s button
                                    IconButton(
                                        onClick = {
                                            val delta = 10000f / (if (playerState.duration > 0) playerState.duration.toFloat() else 1f)
                                            playerState.seekTo((playerState.sliderPos - delta).coerceIn(0f, 1000f))
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Replay10,
                                            contentDescription = "Retroceder 10s",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Skip Forward 10s button
                                    IconButton(
                                        onClick = {
                                            val delta = 10000f / (if (playerState.duration > 0) playerState.duration.toFloat() else 1f)
                                            playerState.seekTo((playerState.sliderPos + delta).coerceIn(0f, 1000f))
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Forward10,
                                            contentDescription = "Adelantar 10s",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Volume Control
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isMuted = playerState.volume == 0f
                                        IconButton(
                                            onClick = {
                                                playerState.volume = if (isMuted) 0.5f else 0f
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isMuted) Icons.Default.VolumeOff else if (playerState.volume < 0.5f) Icons.Default.VolumeDown else Icons.Default.VolumeUp,
                                                contentDescription = "Volumen",
                                                tint = Color.White
                                            )
                                        }
                                        Slider(
                                            value = playerState.volume,
                                            onValueChange = { playerState.volume = it },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.width(100.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.White,
                                                activeTrackColor = Color.White,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                                            )
                                        )
                                    }

                                    // Time Indicator
                                    Text(
                                        text = "${playerState.positionText} / ${playerState.durationText}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // Fullscreen Toggle
                                IconButton(
                                    onClick = {
                                        playerState.isFullscreen = !playerState.isFullscreen
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = "Pantalla Completa",
                                        tint = Color.White,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (selectedMangaSource != null) {
        MangaSourceCatalogScreen(
            source = selectedMangaSource!!,
            onBack = { selectedMangaSource = null },
        )
    } else if (selectedAnime != null) {
        val activeSource = if (selectedAnime!!.sourceName == "AnimeFLV") {
            eu.kanade.tachiyomi.animesource.AnimeFlv()
        } else {
            dynamicAnimeSources.find { it.name == selectedAnime!!.sourceName } ?: eu.kanade.tachiyomi.animesource.AnimeFlv()
        }
        // Shared Anime Details Screen
        AnimeDetailsScreen(
            anime = selectedAnime!!,
            source = activeSource,
            libraryList = libraryList,
            historyList = historyList,
            onBack = { 
                selectedAnime = null 
                selectedEpisode = null
            },
            onPlayEpisode = { episode, video ->
                selectedEpisode = episode
                activeVideo = video
            }
        )
    } else {
        // Layout with Navigation Rail and tabs
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                header = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Aniyomi Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Aniyomi",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Favorite, contentDescription = "Biblioteca") },
                    label = { Text("Biblioteca") },
                    selected = currentTab == "biblioteca",
                    onClick = { currentTab = "biblioteca" }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Refresh, contentDescription = "Actualizaciones") },
                    label = { Text("Recientes") },
                    selected = currentTab == "actualizaciones",
                    onClick = { currentTab = "actualizaciones" }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "Historial") },
                    label = { Text("Historial") },
                    selected = currentTab == "historial",
                    onClick = { currentTab = "historial" }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Examinar") },
                    label = { Text("Examinar") },
                    selected = currentTab == "examinar",
                    onClick = { currentTab = "examinar" }
                )
                NavigationRailItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Configuración") },
                    label = { Text("Configurar") },
                    selected = currentTab == "configuracion",
                    onClick = { currentTab = "configuracion" }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (currentTab) {
                    "biblioteca" -> LibraryTab(libraryList, onAnimeClick = { selectedAnime = it })
                    "actualizaciones" -> UpdatesTab(onAnimeClick = { selectedAnime = it })
                    "historial" -> HistoryTab(
                        historyList = historyList,
                        onClearHistory = { historyList.clear() },
                        onEpisodeClick = { item ->
                            selectedAnime = item.anime
                            selectedEpisode = item.episode
                            activeVideo = RealVideo(name = "Historial", url = item.videoUrl)
                        }
                    )
                    "examinar" -> BrowseTab(
                        animeSources = dynamicAnimeSources,
                        mangaSources = dynamicMangaSources,
                        animeRepos = appSettings.animeRepos,
                        mangaRepos = appSettings.mangaRepos,
                        installedJars = installedJars,
                        onAnimeClick = { selectedAnime = it },
                        onMangaClick = { selectedMangaSource = it },
                        onInstallSuccess = { refreshExtensions() },
                        onUninstallSuccess = { refreshExtensions() }
                    )
                    "configuracion" -> SettingsTab(
                        appSettings = appSettings,
                        onSettingsChange = onSettingsChange
                    )
                }
            }
        }
    }
}

// --- TABS ---

@Composable
fun LibraryTab(libraryList: List<RealAnime>, onAnimeClick: (RealAnime) -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredList = remember(libraryList, searchQuery) {
        if (searchQuery.isBlank()) libraryList else {
            libraryList.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Biblioteca",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar en biblioteca...") },
                modifier = Modifier.width(250.dp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        if (libraryList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Tu biblioteca está vacía.\nExplora animes en la pestaña 'Examinar' para añadirlos.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else if (filteredList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No se encontraron animes con ese nombre.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredList) { anime ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnimeClick(anime) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AsyncImage(
                                url = anime.thumbnailUrl,
                                contentDescription = anime.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            )
                            Text(
                                text = anime.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdatesTab(onAnimeClick: (RealAnime) -> Unit) {
    var updatesList by remember { mutableStateOf<List<RealAnime>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val source = remember { AnimeFlv() }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val response = source.client.newCall(source.latestUpdatesRequest(1)).execute()
                val page = source.latestUpdatesParse(response)
                withContext(Dispatchers.Main) {
                    updatesList = page.animes.map {
                        RealAnime(
                            title = safeAnimeTitle(it, source.name),
                            description = "Último episodio agregado recientemente.",
                            thumbnailUrl = it.thumbnail_url ?: "",
                            url = it.url
                        )
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Recientes",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(updatesList) { anime ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnimeClick(anime) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            AsyncImage(
                                url = anime.thumbnailUrl,
                                contentDescription = anime.title,
                                modifier = Modifier
                                    .size(80.dp, 120.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                                Text(
                                    text = anime.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = anime.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryTab(
    historyList: List<HistoryItem>,
    onClearHistory: () -> Unit,
    onEpisodeClick: (HistoryItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Historial",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (historyList.isNotEmpty()) {
                Button(
                    onClick = onClearHistory,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Limpiar Historial")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar Historial")
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (historyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No has reproducido ningún episodio aún.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyList) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEpisodeClick(item) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                url = item.anime.thumbnailUrl,
                                contentDescription = item.anime.title,
                                modifier = Modifier
                                    .size(50.dp, 75.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.anime.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.episode.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = item.timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class BrowserSourceItem(
    val name: String,
    val lang: String,
    val onClick: (() -> Unit)? = null,
)

private fun languageDisplayName(lang: String): String {
    return when (lang) {
        "all" -> "All"
        "other" -> "Other"
        "" -> "System"
        else -> {
            val locale = when (lang) {
                "zh-CN" -> Locale.forLanguageTag("zh-Hans")
                "zh-TW" -> Locale.forLanguageTag("zh-Hant")
                else -> Locale.forLanguageTag(lang)
            }
            locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
        }
    }
}

private fun safeAnimeTitle(anime: SAnime, fallback: String): String {
    return runCatching { anime.title }.getOrElse { fallback }.ifBlank { fallback }
}

@Composable
fun BrowseTab(
    animeSources: List<AnimeHttpSource>,
    mangaSources: List<MangaSource>,
    animeRepos: List<String>,
    mangaRepos: List<String>,
    installedJars: List<String>,
    onAnimeClick: (RealAnime) -> Unit,
    onMangaClick: (MangaSource) -> Unit,
    onInstallSuccess: () -> Unit,
    onUninstallSuccess: () -> Unit,
) {
    var selectedSource by remember { mutableStateOf<AnimeHttpSource?>(null) }

    if (selectedSource != null) {
        SourceCatalogScreen(
            source = selectedSource!!,
            onBack = { selectedSource = null },
            onAnimeClick = onAnimeClick,
        )
        return
    }

    var tabIndex by remember { mutableStateOf(0) }
    var sourcesTabIndex by remember { mutableStateOf(0) }
    var extensionsTabIndex by remember { mutableStateOf(0) }
    var selectedAnimeRepoUrl by remember { mutableStateOf(animeRepos.firstOrNull().orEmpty()) }
    var selectedMangaRepoUrl by remember { mutableStateOf(mangaRepos.firstOrNull().orEmpty()) }

    LaunchedEffect(animeRepos) {
        if (selectedAnimeRepoUrl.isEmpty() && animeRepos.isNotEmpty()) {
            selectedAnimeRepoUrl = animeRepos.first()
        }
    }
    LaunchedEffect(mangaRepos) {
        if (selectedMangaRepoUrl.isEmpty() && mangaRepos.isNotEmpty()) {
            selectedMangaRepoUrl = mangaRepos.first()
        }
    }

    val currentRepos = if (extensionsTabIndex == 0) animeRepos else mangaRepos
    val currentRepoUrl = if (extensionsTabIndex == 0) selectedAnimeRepoUrl else selectedMangaRepoUrl
    val currentRepoLabel = if (extensionsTabIndex == 0) "Anime" else "Manga"

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Fuentes") })
            Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Extensiones") })
        }

        when (tabIndex) {
            0 -> {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        text = "Examinar Fuentes",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    TabRow(
                        selectedTabIndex = sourcesTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Tab(selected = sourcesTabIndex == 0, onClick = { sourcesTabIndex = 0 }, text = { Text("Anime") })
                        Tab(selected = sourcesTabIndex == 1, onClick = { sourcesTabIndex = 1 }, text = { Text("Manga") })
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val sourceItems = if (sourcesTabIndex == 0) {
                        animeSources.map { source ->
                            BrowserSourceItem(
                                name = source.name,
                                lang = source.lang,
                                onClick = { selectedSource = source },
                            )
                        }
                    } else {
                        mangaSources.map { source ->
                            BrowserSourceItem(
                                name = source.name,
                                lang = source.lang,
                                onClick = { onMangaClick(source) },
                            )
                        }
                    }

                    if (sourceItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (sourcesTabIndex == 0) "No hay fuentes de anime cargadas" else "No hay fuentes de manga cargadas",
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    } else {
                        LanguageGroupedSourceList(items = sourceItems)
                    }
                }
            }

            1 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = extensionsTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Tab(selected = extensionsTabIndex == 0, onClick = { extensionsTabIndex = 0 }, text = { Text("Anime") })
                        Tab(selected = extensionsTabIndex == 1, onClick = { extensionsTabIndex = 1 }, text = { Text("Manga") })
                    }

                    if (currentRepos.isNotEmpty()) {
                        var showRepoMenu by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                Button(
                                    onClick = { showRepoMenu = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    val displayUrl = currentRepoUrl.substringBefore("?")
                                    Text("Repo [$currentRepoLabel]: ${displayUrl.take(45)}${if (displayUrl.length > 45) "..." else ""}")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = showRepoMenu,
                                    onDismissRequest = { showRepoMenu = false },
                                ) {
                                    currentRepos.forEach { repo ->
                                        DropdownMenuItem(
                                            text = { Text(repo.substringBefore("?")) },
                                            onClick = {
                                                if (extensionsTabIndex == 0) {
                                                    selectedAnimeRepoUrl = repo
                                                } else {
                                                    selectedMangaRepoUrl = repo
                                                }
                                                showRepoMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (currentRepoUrl.isNotEmpty()) {
                        ExtensionsSection(
                            repoUrl = currentRepoUrl,
                            installedJars = installedJars,
                            onInstallSuccess = onInstallSuccess,
                            onUninstallSuccess = onUninstallSuccess,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Añade algún repositorio en Configuración", color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageGroupedSourceList(
    items: List<BrowserSourceItem>,
) {
    val groupedItems = remember(items) {
        items.sortedWith(
            compareBy<BrowserSourceItem> { it.lang != "all" }
                .thenBy { languageDisplayName(it.lang) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        ).groupBy { it.lang }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groupedItems.forEach { (lang, sourceItems) ->
            item(key = "lang-$lang") {
                Text(
                    text = languageDisplayName(lang),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(sourceItems) { sourceItem ->
                SourceItemCard(
                    name = sourceItem.name,
                    lang = sourceItem.lang,
                    version = "1.0.0",
                    onClick = sourceItem.onClick,
                )
            }
        }
    }
}

@Composable
fun SourceItemCard(name: String, lang: String, version: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(3).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Idioma: ${languageDisplayName(lang)} | Versión $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun MangaSourceCatalogScreen(source: MangaSource, onBack: () -> Unit) {
    val catalogueSource = source as? CatalogueSource

    if (catalogueSource == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onBack) { Text("Volver") }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Esta fuente no expone catálogo navegable en escritorio.",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        return
    }

    var mangaList by remember { mutableStateOf<List<SManga>>(emptyList()) }
    var selectedManga by remember { mutableStateOf<SManga?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    if (selectedManga != null) {
        MangaDetailScreen(
            source = catalogueSource,
            manga = selectedManga!!,
            onBack = { selectedManga = null },
        )
        return
    }

    LaunchedEffect(searchQuery) {
        isLoading = true
        errorText = null
        withContext(Dispatchers.IO) {
            try {
                val page = if (searchQuery.trim().isEmpty()) {
                    catalogueSource.getPopularManga(1)
                } else {
                    catalogueSource.getSearchManga(1, searchQuery.trim(), catalogueSource.getFilterList())
                }
                withContext(Dispatchers.Main) {
                    mangaList = page.mangas
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorText = e.message ?: e.toString()
                    isLoading = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) {
                Text("Volver")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Catálogo ${source.name}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f),
            )

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar manga...") },
                modifier = Modifier.width(250.dp),
                shape = RoundedCornerShape(8.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            errorText != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Error al cargar el catálogo",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorText!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            mangaList.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No se encontraron resultados.",
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(mangaList) { manga ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedManga = manga },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                AsyncImage(
                                    url = manga.thumbnail_url.orEmpty(),
                                    contentDescription = manga.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                )
                                Text(
                                    text = manga.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    modifier = Modifier.padding(8.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MangaDetailScreen(
    source: CatalogueSource,
    manga: SManga,
    onBack: () -> Unit,
) {
    var mangaDetails by remember { mutableStateOf<SManga?>(null) }
    var chapters by remember { mutableStateOf<List<SChapter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selectedChapter by remember { mutableStateOf<SChapter?>(null) }

    LaunchedEffect(manga.url) {
        isLoading = true
        errorText = null
        selectedChapter = null
        withContext(Dispatchers.IO) {
            try {
                val detailedManga = source.getMangaDetails(manga.copy())
                val chapterList = source.getChapterList(detailedManga)
                withContext(Dispatchers.Main) {
                    mangaDetails = detailedManga
                    chapters = chapterList
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorText = e.message ?: e.toString()
                    isLoading = false
                }
            }
        }
    }

    if (selectedChapter != null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { selectedChapter = null }) { Text("Volver") }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = selectedChapter!!.name,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "La lectura del capítulo todavía no está conectada en desktop, pero ya puedes navegar al detalle.",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        return
    }

    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        errorText != null -> {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onBack) { Text("Volver") }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = manga.title, style = MaterialTheme.typography.headlineMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Error al cargar el manga", color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
                Text(errorText!!)
            }
        }

        else -> {
            val shownManga = mangaDetails ?: manga
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onBack) { Text("Volver") }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = shownManga.title,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(
                        url = shownManga.thumbnail_url.orEmpty(),
                        contentDescription = shownManga.title,
                        modifier = Modifier
                            .width(220.dp)
                            .height(320.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = shownManga.author?.takeIf { it.isNotBlank() } ?: shownManga.artist?.takeIf { it.isNotBlank() } ?: "Autor desconocido",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = shownManga.description?.takeIf { it.isNotBlank() } ?: "Sin descripción.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Capítulos",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    chapters.forEach { chapter ->
                        item(key = chapter.url) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedChapter = chapter },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(chapter.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Capítulo ${chapter.chapter_number}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.Default.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                        }
                }
            }
        }
    }
}

@Composable
fun ExtensionsSection(
    repoUrl: String,
    installedJars: List<String>,
    onInstallSuccess: () -> Unit,
    onUninstallSuccess: () -> Unit,
) {
    var extensionsList by remember { mutableStateOf<List<eu.kanade.tachiyomi.extension.ExtensionInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val filteredExtensions = remember(extensionsList, searchQuery) {
        if (searchQuery.isBlank()) extensionsList else {
            extensionsList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.pkg.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val groupedExtensions = remember(filteredExtensions) {
        filteredExtensions.sortedWith(
            compareBy<eu.kanade.tachiyomi.extension.ExtensionInfo> { it.lang != "all" }
                .thenBy { languageDisplayName(it.lang) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        ).groupBy { it.lang }
    }

    LaunchedEffect(repoUrl) {
        isLoading = true
        errorMessage = null
        try {
            val list = eu.kanade.tachiyomi.extension.ExtensionManager.fetchRepository(repoUrl)
            extensionsList = list
        } catch (e: Throwable) {
            println("[main.kt] Error fetching repository: ${e.message}")
            e.printStackTrace()
            errorMessage = e.message ?: e.toString()
        } finally {
            isLoading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Error al cargar repositorio:\n$errorMessage", color = MaterialTheme.colorScheme.error)
            }
        } else {
            val listState = rememberLazyListState()

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar extensión...") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(end = 16.dp)
                ) {
                    groupedExtensions.forEach { (lang, extensions) ->
                        item(key = "ext-lang-$lang") {
                            Text(
                                text = languageDisplayName(lang),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(extensions, key = { it.pkg }) { ext ->
                            val jarName = ext.pkg + ".jar"
                            val jarFile = File(eu.kanade.tachiyomi.extension.ExtensionManager.extensionsDir, jarName)
                            val isInstalled = installedJars.contains(jarName)

                            var isActionLoading by remember { mutableStateOf(false) }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = ext.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "Paquete: ${ext.pkg}\nIdioma: ${ext.lang.uppercase()} | v${ext.version}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (isActionLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isActionLoading = true
                                                    try {
                                                        if (isInstalled) {
                                                            if (jarFile.exists()) jarFile.delete()
                                                            onUninstallSuccess()
                                                        } else {
                                                            eu.kanade.tachiyomi.extension.ExtensionManager.installExtension(repoUrl, ext)
                                                            onInstallSuccess()
                                                        }
                                                    } catch (e: Throwable) {
                                                        println("[main.kt] Error during extension install/uninstall action: ${e.message}")
                                                        e.printStackTrace()
                                                    } finally {
                                                        isActionLoading = false
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isInstalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text(if (isInstalled) "Desinstalar" else "Instalar")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState = listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
fun SourceCatalogScreen(source: AnimeHttpSource, onBack: () -> Unit, onAnimeClick: (RealAnime) -> Unit) {
    var animeList by remember { mutableStateOf<List<RealAnime>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Load anime list
    LaunchedEffect(searchQuery) {
        isLoading = true
        errorText = null
        withContext(Dispatchers.IO) {
            try {
                val page = if (searchQuery.trim().isEmpty()) {
                    source.getPopularAnime(1)
                } else {
                    source.getSearchAnime(1, searchQuery.trim(), source.getFilterList())
                }
                withContext(Dispatchers.Main) {
                    animeList = page.animes.map { anime ->
                        RealAnime(
                            title = safeAnimeTitle(anime, source.name),
                            description = anime.description ?: "Cargando descripción...",
                            thumbnailUrl = anime.thumbnail_url ?: "",
                            url = anime.url,
                            sourceName = source.name
                        )
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    errorText = e.message ?: e.toString()
                    isLoading = false
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("Volver")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Catálogo ${source.name}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar anime...") },
                modifier = Modifier.width(250.dp),
                shape = RoundedCornerShape(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else if (errorText != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Error al cargar la información", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorText!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        } else {
            if (animeList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No se encontraron resultados en la portada.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Usa el buscador arriba a la derecha para buscar contenido.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(animeList) { anime ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnimeClick(anime) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AsyncImage(
                                url = anime.thumbnailUrl,
                                contentDescription = anime.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                            )
                            Text(
                                text = anime.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
}


