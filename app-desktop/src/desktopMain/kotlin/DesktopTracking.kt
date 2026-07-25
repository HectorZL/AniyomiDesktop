import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime

enum class DesktopTrackerType {
    TRAKT,
    ANILIST,
}

enum class DesktopContentType {
    AUDIOVISUAL,
    MANGA,
}

@Serializable
data class DesktopTrackerAccount(
    val connected: Boolean = false,
    val username: String = "",
    val token: String = "",
    val connectedAt: String = "",
)

@Serializable
data class DesktopTrackingState(
    val trakt: DesktopTrackerAccount = DesktopTrackerAccount(),
    val anilist: DesktopTrackerAccount = DesktopTrackerAccount(),
)

private val trackingJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

private fun trackingFile(): File = File(
    File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop"),
    "tracking.json",
)

fun loadTrackingState(): DesktopTrackingState {
    return runCatching {
        trackingFile().takeIf { it.exists() }?.let { trackingJson.decodeFromString<DesktopTrackingState>(it.readText()) }
    }.getOrNull() ?: DesktopTrackingState()
}

fun saveTrackingState(state: DesktopTrackingState) {
    runCatching {
        trackingFile().parentFile?.mkdirs()
        trackingFile().writeText(trackingJson.encodeToString(DesktopTrackingState.serializer(), state))
    }.onFailure { it.printStackTrace() }
}

fun DesktopTrackingState.account(type: DesktopTrackerType): DesktopTrackerAccount = when (type) {
    DesktopTrackerType.TRAKT -> trakt
    DesktopTrackerType.ANILIST -> anilist
}

fun DesktopTrackingState.withAccount(
    type: DesktopTrackerType,
    account: DesktopTrackerAccount,
): DesktopTrackingState = when (type) {
    DesktopTrackerType.TRAKT -> copy(trakt = account)
    DesktopTrackerType.ANILIST -> copy(anilist = account)
}

private fun trackerDisplayName(type: DesktopTrackerType): String = when (type) {
    DesktopTrackerType.TRAKT -> "Trakt"
    DesktopTrackerType.ANILIST -> "AniList"
}

private fun trackerDescription(type: DesktopTrackerType): String = when (type) {
    DesktopTrackerType.TRAKT -> "Series y películas · historial, watchlist y progreso"
    DesktopTrackerType.ANILIST -> "Manga · capítulos leídos, lista y puntuación"
}

private fun trackerLoginUrl(type: DesktopTrackerType): String = when (type) {
    DesktopTrackerType.ANILIST -> "https://anilist.co/api/v2/oauth/authorize?client_id=5338&response_type=token"
    DesktopTrackerType.TRAKT -> {
        val clientId = System.getenv("TRAKT_CLIENT_ID")
            ?: readLocalProperty("traktClientId")
            ?: ""
        if (clientId.isBlank()) {
            "https://trakt.tv/oauth/authorize"
        } else {
            "https://trakt.tv/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=aniyomi%3A%2F%2Ftrakt-auth"
        }
    }
}

private fun readLocalProperty(name: String): String? {
    val file = File("local.properties")
    if (!file.exists()) return null
    return file.readLines()
        .firstOrNull { it.trimStart().startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

fun openDesktopUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
    }.onFailure { it.printStackTrace() }
}

private fun trackerSearchUrl(type: DesktopTrackerType, title: String): String {
    val query = URLEncoder.encode(title, StandardCharsets.UTF_8.toString())
    return when (type) {
        DesktopTrackerType.TRAKT -> "https://trakt.tv/search?query=$query"
        DesktopTrackerType.ANILIST -> "https://anilist.co/search/manga?search=$query"
    }
}

private fun trackerAccent(type: DesktopTrackerType): Color = when (type) {
    DesktopTrackerType.TRAKT -> Color(0xFFED1C24)
    DesktopTrackerType.ANILIST -> Color(0xFF2B6CB0)
}

private data class TrackerVerification(
    val username: String,
    val accessToken: String,
)

private suspend fun verifyTrackerAccount(
    type: DesktopTrackerType,
    token: String,
): TrackerVerification {
    val normalizedToken = token.trim().removePrefix("Bearer ").trim()
    if (normalizedToken.isBlank()) error("Introduce un token de acceso.")

    return when (type) {
        DesktopTrackerType.ANILIST -> verifyAniList(normalizedToken)
        DesktopTrackerType.TRAKT -> {
            runCatching { verifyTrakt(normalizedToken) }.getOrElse {
                val exchangedToken = exchangeTraktCode(normalizedToken)
                verifyTrakt(exchangedToken).copy(accessToken = exchangedToken)
            }
        }
    }
}

private suspend fun verifyAniList(token: String): TrackerVerification = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val body = """{\"query\":\"query Viewer { Viewer { name } }\"}"""
        .toRequestBody("application/json".toMediaType())
    val request = okhttp3.Request.Builder()
        .url("https://graphql.anilist.co/")
        .header("Authorization", "Bearer $token")
        .post(body)
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("AniList rechazó el token (${response.code}).")
        val root = trackingJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        val username = root["data"]?.jsonObject?.get("Viewer")?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: error("AniList no devolvió el usuario.")
        TrackerVerification(username = username, accessToken = token)
    }
}

private suspend fun verifyTrakt(token: String): TrackerVerification = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val clientId = System.getenv("TRAKT_CLIENT_ID") ?: readLocalProperty("traktClientId")
        ?: error("Falta traktClientId en local.properties o TRAKT_CLIENT_ID.")
    val request = okhttp3.Request.Builder()
        .url("https://api.trakt.tv/users/settings")
        .header("Authorization", "Bearer $token")
        .header("trakt-api-key", clientId)
        .header("trakt-api-version", "2")
        .get()
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Trakt rechazó el token (${response.code}).")
        val root = trackingJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        val username = root["user"]?.jsonObject?.get("username")?.jsonPrimitive?.content
            ?: error("Trakt no devolvió el usuario.")
        TrackerVerification(username = username, accessToken = token)
    }
}

private suspend fun exchangeTraktCode(code: String): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val clientId = System.getenv("TRAKT_CLIENT_ID") ?: readLocalProperty("traktClientId")
        ?: error("Falta traktClientId en local.properties o TRAKT_CLIENT_ID.")
    val clientSecret = System.getenv("TRAKT_CLIENT_SECRET") ?: readLocalProperty("traktClientSecret")
        ?: error("Falta traktClientSecret en local.properties o TRAKT_CLIENT_SECRET.")
    val payload = """{\"code\":\"${code.replace("\\\"", "\\\\\"")}\",\"client_id\":\"$clientId\",\"client_secret\":\"$clientSecret\",\"redirect_uri\":\"aniyomi://trakt-auth\",\"grant_type\":\"authorization_code\"}"""
        .toRequestBody("application/json".toMediaType())
    val request = okhttp3.Request.Builder()
        .url("https://api.trakt.tv/oauth/token")
        .post(payload)
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("Trakt no pudo intercambiar el código OAuth (${response.code}).")
        trackingJson.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["access_token"]?.jsonPrimitive?.content
            ?: error("Trakt no devolvió un access token.")
    }
}

@Composable
fun TrackingTab(
    trackingState: DesktopTrackingState,
    onTrackingChange: (DesktopTrackingState) -> Unit,
) {
    var selectedTracker by remember { mutableStateOf<DesktopTrackerType?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        Text("Cuentas y tracking", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Conecta tus servicios para llevar el progreso de series, películas y manga desde una sola biblioteca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column {
                    Text("Tus credenciales se guardan solo en este equipo", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("La app abre el sitio oficial y no comparte tus datos con fuentes externas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f))
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        Text("Servicios compatibles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(listOf(DesktopTrackerType.TRAKT, DesktopTrackerType.ANILIST)) { type ->
                TrackerAccountCard(
                    type = type,
                    account = trackingState.account(type),
                    onConnect = { selectedTracker = type },
                    onDisconnect = {
                        onTrackingChange(trackingState.withAccount(type, DesktopTrackerAccount()))
                    },
                )
            }
        }
    }

    selectedTracker?.let { type ->
        TrackerConnectDialog(
            type = type,
            account = trackingState.account(type),
            onDismiss = { selectedTracker = null },
            onSave = { account ->
                onTrackingChange(trackingState.withAccount(type, account))
                selectedTracker = null
            },
        )
    }
}

@Composable
private fun TrackerAccountCard(
    type: DesktopTrackerType,
    account: DesktopTrackerAccount,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val accent = trackerAccent(type)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(type.name.take(2), color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(trackerDisplayName(type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(trackerDescription(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (account.connected) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Conectado${account.username.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            if (account.connected) {
                OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            } else {
                Button(onClick = onConnect) { Text("Conectar") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerConnectDialog(
    type: DesktopTrackerType,
    account: DesktopTrackerAccount,
    onDismiss: () -> Unit,
    onSave: (DesktopTrackerAccount) -> Unit,
) {
    var username by remember(account) { mutableStateOf(account.username) }
    var token by remember(account) { mutableStateOf(account.token) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("Conectar ${trackerDisplayName(type)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(trackerDescription(type), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { openDesktopUrl(trackerLoginUrl(type)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing,
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir inicio de sesión oficial")
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Usuario (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (type == DesktopTrackerType.TRAKT) "Token o código OAuth" else "Token de acceso") },
                    supportingText = {
                        Text(if (type == DesktopTrackerType.TRAKT) "Pega el código OAuth o un token de Trakt; se validará con el servicio." else "Pega el access token que aparece en la URL después del OAuth.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing,
                )
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = token.isNotBlank() && !isProcessing,
                onClick = {
                    scope.launch {
                        isProcessing = true
                        errorMessage = null
                        try {
                            val verification = verifyTrackerAccount(type, token)
                            onSave(
                                DesktopTrackerAccount(
                                    connected = true,
                                    username = verification.username.ifBlank { username.trim() },
                                    token = verification.accessToken,
                                    connectedAt = LocalDateTime.now().toString(),
                                ),
                            )
                        } catch (error: Throwable) {
                            errorMessage = error.message ?: "No se pudo validar la cuenta."
                        } finally {
                            isProcessing = false
                        }
                    }
                },
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Verificando…")
                } else {
                    Text("Validar y guardar")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isProcessing) { Text("Cancelar") } },
    )
}

@Composable
fun DesktopTrackerPanel(
    contentType: DesktopContentType,
    title: String,
    trackingState: DesktopTrackingState,
    onOpenAccounts: () -> Unit,
) {
    val type = if (contentType == DesktopContentType.MANGA) DesktopTrackerType.ANILIST else DesktopTrackerType.TRAKT
    val account = trackingState.account(type)
    val accent = trackerAccent(type)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (contentType == DesktopContentType.MANGA) Icons.Default.MenuBook else Icons.Default.Videocam,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Tracker recomendado · ${trackerDisplayName(type)}", style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
                Text(
                    if (account.connected) "Cuenta conectada${account.username.takeIf { it.isNotBlank() }?.let { " como $it" } ?: ""}. Puedes abrir la ficha y mantener el seguimiento allí."
                    else "Conecta tu cuenta para vincular ${if (contentType == DesktopContentType.MANGA) "este manga" else "esta serie o película"} con ${trackerDisplayName(type)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (account.connected) {
                OutlinedButton(onClick = { openDesktopUrl(trackerSearchUrl(type, title)) }) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Vincular")
                }
            } else {
                Button(onClick = onOpenAccounts) { Text("Conectar") }
            }
        }
    }
}
