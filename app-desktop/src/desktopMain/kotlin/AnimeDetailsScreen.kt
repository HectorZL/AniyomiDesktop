import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import okhttp3.Headers

@Composable
fun AnimeDetailsScreen(
    anime: RealAnime,
    source: AnimeHttpSource,
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
    var errorText by remember { mutableStateOf<String?>(null) }

    val isInLibrary = libraryList.any { it.url == anime.url }

    // Load details & episodes when anime or source is selected
    LaunchedEffect(anime, source) {
        isLoading = true
        errorText = null
        episodes = emptyList()
        withContext(Dispatchers.IO) {
            try {
                val sAnime = SAnime.create().apply {
                    url = anime.url
                    title = anime.title
                }
                val parsedAnime = source.getAnimeDetails(sAnime)
                val parsedEpisodes = source.getEpisodeList(sAnime)
                
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
            } catch (e: Throwable) {
                e.printStackTrace()
                val friendlyMessage = when (e) {
                    is NoSuchMethodError -> "Error de compatibilidad con la extensión: ${e.message?.substringAfterLast('/')?.substringBefore('(') ?: e.message}"
                    is NoClassDefFoundError -> "Falta una clase requerida por la extensión: ${e.message}"
                    else -> e.message ?: e.toString()
                }
                withContext(Dispatchers.Main) {
                    errorText = friendlyMessage
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
            withContext(Dispatchers.IO) {
                try {
                    val sEpisode = SEpisode.create().apply {
                        url = selectedEpisode!!.url
                        name = selectedEpisode!!.name
                    }
                    val parsedVideos = source.getVideoList(sEpisode)
                    println("[DEPURE] Servidores encontrados en la página: ${parsedVideos.size}")
                    
                    withContext(Dispatchers.Main) {
                        videos = parsedVideos.map {
                            val headersMap = mutableMapOf<String, String>()
                            it.headers?.let { h ->
                                for (i in 0 until h.size) {
                                    headersMap[h.name(i)] = h.value(i)
                                }
                            }
                            RealVideo(
                                name = it.quality,
                                url = it.url,
                                subtitleTracks = it.subtitleTracks.map { track ->
                                    val proxiedSubUrl = eu.kanade.tachiyomi.network.VideoProxyServer.registerVideo(track.url, headersMap)
                                    RealTrack(url = proxiedSubUrl, lang = track.lang)
                                },
                                headers = headersMap
                            )
                        }
                        isLoading = false
                    }
                } catch (e: Throwable) {
                    println("[DEPURE] Error al obtener servidores: ${e.message}")
                    e.printStackTrace()
                    val friendlyMessage = when (e) {
                        is NoSuchMethodError -> "Error de compatibilidad con la extensión: ${e.message?.substringAfterLast('/')?.substringBefore('(') ?: e.message}"
                        is NoClassDefFoundError -> "Falta una clase requerida por la extensión: ${e.message}"
                        else -> e.message ?: e.toString()
                    }
                    withContext(Dispatchers.Main) {
                        errorText = friendlyMessage
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
                    .clip(RoundedCornerShape(8.dp)),
                headers = source.headers
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
        } else if (errorText != null && episodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Error al cargar episodios:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorText!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                }
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
