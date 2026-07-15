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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import java.net.URI
import javax.imageio.ImageIO

// Data structures for Real Anime
data class RealAnime(
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val url: String
)

data class RealEpisode(
    val name: String,
    val url: String,
    val episodeNumber: Float
)

data class RealVideo(
    val name: String,
    val url: String
)

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
            title = "Aniyomi Desktop (KMP Nativo - AnimeFLV Scraper)"
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
    var currentTab by remember { mutableStateOf("catalogo") }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            NavigationRailItem(
                icon = { Icon(Icons.Default.Home, contentDescription = "AnimeFLV") },
                label = { Text("AnimeFLV") },
                selected = currentTab == "catalogo",
                onClick = { currentTab = "catalogo" }
            )
            NavigationRailItem(
                icon = { Icon(Icons.Default.Search, contentDescription = "Prueba Red") },
                label = { Text("Prueba Red") },
                selected = currentTab == "network",
                onClick = { currentTab = "network" }
            )
            NavigationRailItem(
                icon = { Icon(Icons.Default.Info, contentDescription = "Acerca de") },
                label = { Text("Acerca de") },
                selected = currentTab == "info",
                onClick = { currentTab = "info" }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentTab) {
                "catalogo" -> CatalogTab()
                "network" -> NetworkTestTab()
                "info" -> InfoTab()
            }
        }
    }
}

@Composable
fun CatalogTab() {
    var animeList by remember { mutableStateOf<List<RealAnime>>(emptyList()) }
    var selectedAnime by remember { mutableStateOf<RealAnime?>(null) }
    var episodes by remember { mutableStateOf<List<RealEpisode>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<RealEpisode?>(null) }
    var videos by remember { mutableStateOf<List<RealVideo>>(emptyList()) }
    var activeVideoUrl by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val source = remember { AnimeFlv() }

    // Load initial anime list
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

    // Load details & episodes when anime is selected
    LaunchedEffect(selectedAnime) {
        if (selectedAnime != null) {
            isLoading = true
            episodes = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val animeUrl = selectedAnime!!.url
                    val detailResponse = source.client.newCall(GET(source.baseUrl + animeUrl, source.headers)).execute()
                    val parsedAnime = source.animeDetailsParse(detailResponse.asJsoup())
                    
                    val epResponse = source.client.newCall(GET(source.baseUrl + animeUrl, source.headers)).execute()
                    val parsedEpisodes = source.episodeListParse(epResponse)
                    
                    withContext(Dispatchers.Main) {
                        selectedAnime = selectedAnime!!.copy(description = parsedAnime.description ?: "")
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
    }

    // Load video servers when episode is selected
    LaunchedEffect(selectedEpisode) {
        if (selectedEpisode != null) {
            isLoading = true
            videos = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val epUrl = selectedEpisode!!.url
                    val response = source.client.newCall(GET(source.baseUrl + epUrl, source.headers)).execute()
                    val parsedVideos = source.videoListParse(response)
                    
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
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                }
            }
        }
    }

    if (activeVideoUrl != null) {
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
        // Anime details screen
        val anime = selectedAnime!!
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { 
                    selectedAnime = null
                    selectedEpisode = null
                    videos = emptyList()
                }) {
                    Text("Volver al Catálogo")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    url = anime.thumbnailUrl,
                    contentDescription = anime.title,
                    modifier = Modifier
                        .size(150.dp, 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = anime.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = anime.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            if (isLoading) {
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
                                                    onClick = { activeVideoUrl = video.url },
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
    } else {
        // Catalog Grid of Anime
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Catálogo AnimeFLV",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar anime...") },
                    modifier = Modifier.width(300.dp),
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
                                .clickable { selectedAnime = anime },
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

@Composable
fun NetworkTestTab() {
    var urlInput by remember { mutableStateOf("https://httpbin.org/get") }
    var responseOutput by remember { mutableStateOf("Haz clic en 'Enviar Request' para probar el stack HTTP nativo.") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Prueba de Stack de Red (OkHttp)",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Esta pestaña realiza una petición HTTP real utilizando la librería OkHttp empaquetada en el módulo :source-api compilado para JVM.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                label = { Text("URL de la petición") }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = {
                    isLoading = true
                    responseOutput = "Enviando petición a $urlInput..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val client = OkHttpClient()
                                val request = Request.Builder().url(urlInput).build()
                                client.newCall(request).execute().use { response: Response ->
                                    val code = response.code
                                    val body = response.body?.string() ?: "[Sin Cuerpo]"
                                    "HTTP $code\n\n$body"
                                }
                            } catch (e: Exception) {
                                "Error: ${e.message}\n${e.stackTraceToString()}"
                            }
                        }
                        responseOutput = result
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(size = 20.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Enviar Request")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Resultado de la petición:",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = responseOutput,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CircularProgressIndicator(size: androidx.compose.ui.unit.Dp, color: Color) {
    Box(modifier = Modifier.size(size)) {
        androidx.compose.material3.CircularProgressIndicator(
            color = color,
            strokeWidth = 2.dp,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun InfoTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Aniyomi Windows Nativo",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Versión: 1.0.0-KMP-AnimeFLV-Real",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Esta aplicación es una demostración técnica (Proof of Concept) del port nativo de Aniyomi a Windows utilizando Kotlin Multiplatform (KMP) y Compose Desktop.\n\n" +
                    "Los módulos básicos (:i18n, :i18n-aniyomi, :source-api) compilan nativamente para el target JVM de escritorio, permitiendo cargar fuentes de contenido y decodificar flujos de video con el reproductor nativo integrado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.width(500.dp)
        )
    }
}
