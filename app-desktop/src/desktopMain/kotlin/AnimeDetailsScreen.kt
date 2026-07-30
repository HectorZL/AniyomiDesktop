import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
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
import kotlinx.coroutines.swing.Swing
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
    trackingState: DesktopTrackingState,
    onOpenAccounts: () -> Unit,
    onBack: () -> Unit,
    onPlayEpisode: (RealEpisode, RealVideo) -> Unit
) {
    var detailAnime by remember(anime) { mutableStateOf(anime) }
    var episodes by remember { mutableStateOf<List<RealEpisode>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<RealEpisode?>(null) }
    var videos by remember { mutableStateOf<List<RealVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var filterUnreadOnly by remember { mutableStateOf(false) }
    var filterDownloadedOnly by remember { mutableStateOf(false) }
    var filterFavoritesOnly by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf("number") }
    var sortAscending by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

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
                
                withContext(Dispatchers.Swing) {
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
                withContext(Dispatchers.Swing) {
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
                    parsedVideos.forEachIndexed { index, video ->
                        println("[DEPURE] Video $index: title=${video.videoTitle}, url=${video.videoUrl}, quality=${video.quality}")
                        video.subtitleTracks.forEachIndexed { subIndex, track ->
                            println("[DEPURE]   Subtitle $subIndex: lang=${track.lang}, url=${track.url}")
                        }
                    }
                    
                    withContext(Dispatchers.Swing) {
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
                                     val proxiedSubUrl = eu.kanade.tachiyomi.network.VideoProxyServer.registerVideo(track.url, it.headers ?: Headers.headersOf())
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
                    withContext(Dispatchers.Swing) {
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

        Spacer(modifier = Modifier.height(18.dp))
        DesktopTrackerPanel(
            contentType = DesktopContentType.AUDIOVISUAL,
            title = detailAnime.title,
            trackingState = trackingState,
            onOpenAccounts = onOpenAccounts,
        )

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
            val filteredEpisodes = remember(episodes, historyList.toList(), filterUnreadOnly, sortOption, sortAscending) {
                var list = episodes
                if (filterUnreadOnly) {
                    list = list.filterNot { episode ->
                        historyList.any { item ->
                            item.anime.url == anime.url &&
                                item.episode.url == episode.url &&
                                item.isSeen
                        }
                    }
                }
                list = when (sortOption) {
                    "number" -> if (sortAscending) list.sortedBy { it.episodeNumber } else list.sortedByDescending { it.episodeNumber }
                    "alpha" -> if (sortAscending) list.sortedBy { it.name } else list.sortedByDescending { it.name }
                    else -> list
                }
                list
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Episodes List
                Column(modifier = Modifier.weight(1.2f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Episodios",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filtros y orden",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filteredEpisodes) { episode ->
                            val historyItem = historyList.find { item ->
                                item.anime.url == anime.url && item.episode.url == episode.url
                            }
                            val isSeen = historyItem?.isSeen == true
                            val isWatching = historyItem != null && !historyItem.isSeen && historyItem.progressSeconds > 5
                            
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
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (isSeen) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                                            contentDescription = if (isSeen) "Visto" else "Reproducir",
                                            tint = if (isSeen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(text = episode.name, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    
                                    // Mostrar "Viendo - HH:MM" si está parcialmente visto
                                    if (isWatching) {
                                        val progress = historyItem.progressSeconds
                                        val timeStr = String.format("%02d:%02d", progress / 60, progress % 60)
                                        Text(
                                            text = "Viendo - $timeStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
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
                                                    // Buscar si existe progreso previo para este episodio ANTES de eliminar
                                                    val existingProgress = historyList.find { it.anime.url == anime.url && it.episode.url == selectedEpisode!!.url }
                                                    val progress = existingProgress?.progressSeconds ?: 0L
                                                    val duration = existingProgress?.durationSeconds ?: 0L
                                                    // Remove old entries for same ep
                                                    historyList.removeAll { it.anime.url == anime.url && it.episode.url == selectedEpisode!!.url }
                                                    println("Creating HistoryItem: anime=$anime, episode=$selectedEpisode, url=${video.url}, time=$nowStr, progress=$progress, duration=$duration")
                                                    historyList.add(0, HistoryItem(anime, selectedEpisode!!, video.url, nowStr, progress, duration))

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

    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Opciones de episodios") },
            text = {
                var activeTab by remember { mutableStateOf("filter") }
                Column(modifier = Modifier.width(320.dp).height(260.dp)) {
                    TabRow(selectedTabIndex = if (activeTab == "filter") 0 else if (activeTab == "sort") 1 else 2) {
                        Tab(
                            selected = activeTab == "filter",
                            onClick = { activeTab = "filter" },
                            text = { Text("Filtrar") }
                        )
                        Tab(
                            selected = activeTab == "sort",
                            onClick = { activeTab = "sort" },
                            text = { Text("Ordenar") }
                        )
                        Tab(
                            selected = activeTab == "appearance",
                            onClick = { activeTab = "appearance" },
                            text = { Text("Apariencia") }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    when (activeTab) {
                        "filter" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterDownloadedOnly = !filterDownloadedOnly }) {
                                    Checkbox(checked = filterDownloadedOnly, onCheckedChange = { filterDownloadedOnly = it })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Descargados")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterUnreadOnly = !filterUnreadOnly }) {
                                    Checkbox(checked = filterUnreadOnly, onCheckedChange = { filterUnreadOnly = it })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sin ver")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filterFavoritesOnly = !filterFavoritesOnly }) {
                                    Checkbox(checked = filterFavoritesOnly, onCheckedChange = { filterFavoritesOnly = it })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Favoritos")
                                }
                            }
                        }
                        "sort" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { sortAscending = !sortAscending },
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Dirección del orden")
                                    Icon(
                                        imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = if (sortAscending) "Ascendente" else "Descendente"
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sortOption = "number" }) {
                                    RadioButton(selected = sortOption == "number", onClick = { sortOption = "number" })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Por número de episodio")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sortOption = "alpha" }) {
                                    RadioButton(selected = sortOption == "alpha", onClick = { sortOption = "alpha" })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Alfabéticamente")
                                }
                            }
                        }
                        "appearance" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Mostrar título del episodio", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = true, onClick = {})
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Nombre completo")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}
