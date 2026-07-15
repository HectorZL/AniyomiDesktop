import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

// Mock data structures
data class MockAnime(
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val episodes: List<MockEpisode>
)

data class MockEpisode(
    val name: String,
    val videoUrl: String
)

val mockAnimes = listOf(
    MockAnime(
        title = "Big Buck Bunny (Sintel Edition)",
        description = "Un gran conejo blanco que vive en el bosque y busca vengarse de tres roedores traviesos.",
        thumbnailUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?q=80&w=300",
        episodes = listOf(
            MockEpisode("Episodio 1: La venganza del conejo", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
            MockEpisode("Episodio 2: Escenas adicionales", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4")
        )
    ),
    MockAnime(
        title = "Sintel (Open Movie Project)",
        description = "La historia de una joven solitaria que rescata a un dragón herido y crea un fuerte lazo con él.",
        thumbnailUrl = "https://images.unsplash.com/photo-1478760329108-5c3ed9d495a0?q=80&w=300",
        episodes = listOf(
            MockEpisode("Episodio 1: El encuentro con el dragón", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
        )
    ),
    MockAnime(
        title = "Tears of Steel (Sci-Fi Demo)",
        description = "Un grupo de soldados y científicos en el futuro intenta salvar el mundo de una amenaza robótica.",
        thumbnailUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=300",
        episodes = listOf(
            MockEpisode("Episodio 1: Robocalipsis en Ámsterdam", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4")
        )
    )
)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Aniyomi Desktop (KMP Nativo - PoC)"
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme()
        ) {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    var currentTab by remember { mutableStateOf("catalogo") }

    // Navigation Rail Layout
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail {
            NavigationRailItem(
                icon = { Icon(Icons.Default.Home, contentDescription = "Catálogo") },
                label = { Text("Catálogo") },
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
    var selectedAnime by remember { mutableStateOf<MockAnime?>(null) }
    var activeVideoUrl by remember { mutableStateOf<String?>(null) }

    if (activeVideoUrl != null) {
        // Full screen video player
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

            // Back button overlay
            Button(
                onClick = {
                    playerState.stop()
                    activeVideoUrl = null
                },
                modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text("Volver")
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
                Button(onClick = { selectedAnime = null }) {
                    Text("Volver al Catálogo")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(150.dp, 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                ) {
                    Text(
                        "[Thumbnail]",
                        color = Color.LightGray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column {
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
            Text(
                text = "Episodios disponibles",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(anime.episodes) { episode ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { activeVideoUrl = episode.videoUrl },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = episode.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    } else {
        // Grid/List of Anime
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "Aniyomi Windows - Catálogo de Prueba",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(mockAnimes) { anime ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAnime = anime },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp, 120.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.DarkGray)
                            ) {
                                Text(
                                    "[Poster]",
                                    color = Color.LightGray,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = anime.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = anime.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
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
                                client.newCall(request).execute().use { response ->
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
            text = "Versión: 1.0.0-KMP-PoC",
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
