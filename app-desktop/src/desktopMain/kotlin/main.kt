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
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.animesource.AnimeFlv
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Data structures for Real Anime
@Serializable
data class RealAnime(
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val url: String
)

@Serializable
data class RealEpisode(
    val name: String,
    val url: String,
    val episodeNumber: Float
)

@Serializable
data class RealVideo(
    val name: String,
    val url: String
)

@Serializable
data class HistoryItem(
    val anime: RealAnime,
    val episode: RealEpisode,
    val videoUrl: String,
    val timestamp: String
)

fun saveLibrary(library: List<RealAnime>) {
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, "library.json")
        file.writeText(Json.encodeToString(library))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun loadLibrary(): List<RealAnime> {
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        val file = File(appDir, "library.json")
        if (file.exists()) {
            return Json.decodeFromString<List<RealAnime>>(file.readText())
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return emptyList()
}

fun saveHistory(history: List<HistoryItem>) {
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, "history.json")
        file.writeText(Json.encodeToString(history))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun loadHistory(): List<HistoryItem> {
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        val file = File(appDir, "history.json")
        if (file.exists()) {
            return Json.decodeFromString<List<HistoryItem>>(file.readText())
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return emptyList()
}

@Serializable
data class AppSettings(
    val extensionDirPath: String = ""
)

fun saveSettings(settings: AppSettings) {
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, "settings.json")
        file.writeText(Json.encodeToString(settings))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun loadSettings(): AppSettings {
    val defaultPath = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/extensions").absolutePath
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        val file = File(appDir, "settings.json")
        if (file.exists()) {
            val loaded = Json.decodeFromString<AppSettings>(file.readText())
            if (loaded.extensionDirPath.isNotEmpty()) {
                return loaded
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return AppSettings(extensionDirPath = defaultPath)
}


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

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Aniyomi Desktop (KMP Nativo - Multi-pestaña)"
        ) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF9800), // Orange theme
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    var currentTab by remember { mutableStateOf("biblioteca") }
    var selectedAnime by remember { mutableStateOf<RealAnime?>(null) }
    var selectedEpisode by remember { mutableStateOf<RealEpisode?>(null) }
    var activeVideoUrl by remember { mutableStateOf<String?>(null) }
    
    // Shared state managers loaded from disk
    val libraryList = remember { mutableStateListOf<RealAnime>().apply { addAll(loadLibrary()) } }
    val historyList = remember { mutableStateListOf<HistoryItem>().apply { addAll(loadHistory()) } }
    var appSettings by remember { mutableStateOf(loadSettings()) }

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

    if (activeVideoUrl != null && selectedEpisode != null && selectedAnime != null) {
        // Player View
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val playerState = rememberVideoPlayerState()

            LaunchedEffect(activeVideoUrl) {
                activeVideoUrl?.let { url ->
                    playerState.openUri(url)
                }
            }

            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize()
            )

            Row(
                modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        playerState.stop()
                        activeVideoUrl = null
                    }
                ) {
                    Text("Volver")
                }
                
                Button(
                    onClick = {
                        try {
                            Desktop.getDesktop().browse(URI(activeVideoUrl!!))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Abrir en Navegador")
                }
            }
        }
    } else if (selectedAnime != null) {
        // Shared Anime Details Screen
        AnimeDetailsScreen(
            anime = selectedAnime!!,
            libraryList = libraryList,
            historyList = historyList,
            onBack = { 
                selectedAnime = null 
                selectedEpisode = null
            },
            onPlayEpisode = { episode, video ->
                selectedEpisode = episode
                activeVideoUrl = video.url
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
                            activeVideoUrl = item.videoUrl
                        }
                    )
                    "examinar" -> BrowseTab(onAnimeClick = { selectedAnime = it })
                    "configuracion" -> SettingsTab(
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

// --- TABS ---

@Composable
fun LibraryTab(libraryList: List<RealAnime>, onAnimeClick: (RealAnime) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Biblioteca",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (libraryList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Tu biblioteca está vacía.\nExplora animes en la pestaña 'Examinar' para añadirlos.",
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
                items(libraryList) { anime ->
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
    val scope = rememberCoroutineScope()
    val source = remember { AnimeFlv() }

    LaunchedEffect(Unit) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val response = source.client.newCall(source.latestUpdatesRequest(1)).execute()
                val page = source.latestUpdatesParse(response)
                withContext(Dispatchers.Main) {
                    updatesList = page.animes.map {
                        RealAnime(
                            title = it.title,
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

@Composable
fun BrowseTab(onAnimeClick: (RealAnime) -> Unit) {
    var selectedSource by remember { mutableStateOf<String?>(null) }

    if (selectedSource == "animeflv") {
        SourceCatalogScreen(
            onBack = { selectedSource = null },
            onAnimeClick = onAnimeClick
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Examinar Fuentes",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Fuentes de Anime",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedSource = "animeflv" },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "FLV",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "AnimeFLV (Nativa)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Idioma: Español | Versión 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SourceCatalogScreen(onBack: () -> Unit, onAnimeClick: (RealAnime) -> Unit) {
    var animeList by remember { mutableStateOf<List<RealAnime>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val source = remember { AnimeFlv() }

    // Load anime list
    LaunchedEffect(searchQuery) {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val response = if (searchQuery.trim().isEmpty()) {
                    source.client.newCall(source.popularAnimeRequest(1)).execute()
                } else {
                    source.client.newCall(source.searchAnimeRequest(1, searchQuery.trim(), AnimeFilterList())).execute()
                }
                val page = if (searchQuery.trim().isEmpty()) {
                    source.popularAnimeParse(response)
                } else {
                    source.searchAnimeParse(response)
                }
                withContext(Dispatchers.Main) {
                    animeList = page.animes.map {
                        RealAnime(
                            title = it.title,
                            description = it.description ?: "Cargando descripción...",
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("Volver")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Catálogo AnimeFLV",
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

@Composable
fun SettingsTab(
    appSettings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Configuración",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Inyección de Extensiones (APK)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Directorio de extensiones APK:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = appSettings.extensionDirPath,
                        onValueChange = { onSettingsChange(appSettings.copy(extensionDirPath = it)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            val chooser = javax.swing.JFileChooser().apply {
                                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                                dialogTitle = "Seleccionar Directorio de Extensiones"
                                val currentDir = File(appSettings.extensionDirPath)
                                if (currentDir.exists()) {
                                    currentDirectory = currentDir
                                } else {
                                    currentDirectory = File(System.getProperty("user.home"))
                                }
                            }
                            val result = chooser.showOpenDialog(null)
                            if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                                val selectedDir = chooser.selectedFile.absolutePath
                                onSettingsChange(appSettings.copy(extensionDirPath = selectedDir))
                            }
                        }
                    ) {
                        Text("Examinar...")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "La carga de APKs traducirá los archivos DEX a JAR o los interpretará mediante la JVM de escritorio para ejecutar los scrapers nativos de Android en PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Información del Sistema",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Plataforma: Windows Desktop (Nativo)", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Motor Gráfico: Compose Multiplatform JVM (Skia)", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Reproductor: Compose Media Player JNI (Windows Media Foundation)", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// --- SHARED ANIME DETAILS SCREEN ---

@Composable
fun AnimeDetailsScreen(
    anime: RealAnime,
    libraryList: MutableList<RealAnime>,
    historyList: MutableList<HistoryItem>,
    onBack: () -> Unit,
    onPlayEpisode: (RealEpisode, RealVideo) -> Unit
) {
    var detailAnime by remember(anime) { mutableStateOf(anime) }
    var episodes by remember { mutableStateOf<List<RealEpisode>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<RealEpisode?>(null) }
    var videos by remember { mutableStateOf<List<RealVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val source = remember { AnimeFlv() }

    val isInLibrary = libraryList.any { it.url == anime.url }

    // Load details & episodes when anime is selected
    LaunchedEffect(anime) {
        isLoading = true
        episodes = emptyList()
        scope.launch(Dispatchers.IO) {
            try {
                val animeUrl = anime.url
                val detailResponse = source.client.newCall(GET(source.baseUrl + animeUrl, source.headers)).execute()
                val parsedAnime = source.animeDetailsParse(detailResponse.asJsoup())
                
                val epResponse = source.client.newCall(GET(source.baseUrl + animeUrl, source.headers)).execute()
                val parsedEpisodes = source.episodeListParse(epResponse)
                
                withContext(Dispatchers.Main) {
                    detailAnime = detailAnime.copy(description = parsedAnime.description ?: "")
                    episodes = parsedEpisodes.map {
                        RealEpisode(
                            name = it.name,
                            url = it.url,
                            episodeNumber = it.episode_number
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

    // Load video servers when episode is selected
    LaunchedEffect(selectedEpisode) {
        if (selectedEpisode != null) {
            isLoading = true
            videos = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val epUrl = selectedEpisode!!.url
                    val fullUrl = source.baseUrl + epUrl
                    println("[DEPURE] Solicitando episodio a: $fullUrl")
                    val response = source.client.newCall(GET(fullUrl, source.headers)).execute()
                    val parsedVideos = source.videoListParse(response)
                    println("[DEPURE] Servidores encontrados en la página: ${parsedVideos.size}")
                    
                    withContext(Dispatchers.Main) {
                        videos = parsedVideos.map {
                            RealVideo(
                                name = it.quality,
                                url = it.url
                            )
                        }
                        isLoading = false
                    }
                } catch (e: Exception) {
                    println("[DEPURE] Error al obtener servidores: ${e.message}")
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("Volver")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = {
                    if (isInLibrary) {
                        libraryList.removeAll { it.url == anime.url }
                    } else {
                        libraryList.add(anime)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isInLibrary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isInLibrary) "Quitar de Biblioteca" else "Añadir a Biblioteca")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                url = detailAnime.thumbnailUrl,
                contentDescription = detailAnime.title,
                modifier = Modifier
                    .size(150.dp, 220.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detailAnime.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = detailAnime.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        if (isLoading && episodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Episodes List
                Column(modifier = Modifier.weight(1.2f)) {
                    Text(
                        text = "Episodios",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(episodes) { episode ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedEpisode = episode },
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedEpisode == episode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = episode.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(24.dp))

                // Video Servers List
                Column(modifier = Modifier.weight(0.8f)) {
                    Text(
                        text = "Servidores",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (selectedEpisode == null) {
                        Text(
                            "Selecciona un episodio para cargar servidores",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    } else if (isLoading && videos.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    } else if (videos.isEmpty()) {
                        Text(
                            "No se encontraron servidores para este episodio.\n(El episodio puede no tener videos cargados o está caído)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(videos) { video ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(
                                            text = "Servidor: ${video.name}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    // Register to History
                                                    val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM"))
                                                    // Remove old entries for same ep
                                                    historyList.removeAll { it.anime.url == anime.url && it.episode.url == selectedEpisode!!.url }
                                                    historyList.add(0, HistoryItem(anime, selectedEpisode!!, video.url, nowStr))
                                                    
                                                    onPlayEpisode(selectedEpisode!!, video)
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Reproducir")
                                            }
                                            Button(
                                                onClick = {
                                                    try {
                                                        Desktop.getDesktop().browse(URI(video.url))
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Navegador")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
