import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun SettingsTab(
    appSettings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    var newAnimeRepoUrl by remember { mutableStateOf("") }
    var newMangaRepoUrl by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .padding(end = 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
        Column {
            Text("Ajustes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Personaliza la apariencia y administra las fuentes que usa AniYomi Desktop.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(
            title = "Apariencia",
            description = "Usa una superficie cómoda para sesiones largas de lectura y reproducción.",
        ) {
            Text("Tema", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "Oscuro", "light" to "Claro").forEach { (mode, label) ->
                    FilterChip(
                        selected = appSettings.themeMode == mode,
                        onClick = { onSettingsChange(appSettings.copy(themeMode = mode)) },
                        label = { Text(label) },
                    )
                }
            }
        }

        SettingsSection(
            title = "Extensiones",
            description = "Las extensiones se cargan desde una carpeta local y pueden aportar fuentes de anime o manga.",
        ) {
            Text("Directorio de extensiones", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = appSettings.extensionDirPath,
                    onValueChange = { onSettingsChange(appSettings.copy(extensionDirPath = it)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val chooser = javax.swing.JFileChooser().apply {
                        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Seleccionar directorio de extensiones"
                        currentDirectory = File(appSettings.extensionDirPath).takeIf { it.exists() }
                            ?: File(System.getProperty("user.home"))
                    }
                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        onSettingsChange(appSettings.copy(extensionDirPath = chooser.selectedFile.absolutePath))
                    }
                }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Examinar")
                }
            }
        }

        SettingsSection(
            title = "Repositorios de extensiones",
            description = "Añade fuentes JSON para mantener separados los repositorios de anime y manga.",
        ) {
            RepositoryEditor(
                title = "Anime",
                repositories = appSettings.animeRepos,
                newValue = newAnimeRepoUrl,
                placeholder = "https://.../index.min.json",
                onValueChange = { newAnimeRepoUrl = it },
                onAdd = {
                    val value = newAnimeRepoUrl.trim()
                    if (value.isNotEmpty() && value !in appSettings.animeRepos) {
                        onSettingsChange(appSettings.copy(animeRepos = appSettings.animeRepos + value))
                        newAnimeRepoUrl = ""
                    }
                },
                onRemove = { url -> onSettingsChange(appSettings.copy(animeRepos = appSettings.animeRepos - url)) },
            )
            Spacer(Modifier.height(18.dp))
            RepositoryEditor(
                title = "Manga",
                repositories = appSettings.mangaRepos,
                newValue = newMangaRepoUrl,
                placeholder = "https://.../index.min.json",
                onValueChange = { newMangaRepoUrl = it },
                onAdd = {
                    val value = newMangaRepoUrl.trim()
                    if (value.isNotEmpty() && value !in appSettings.mangaRepos) {
                        onSettingsChange(appSettings.copy(mangaRepos = appSettings.mangaRepos + value))
                        newMangaRepoUrl = ""
                    }
                },
                onRemove = { url -> onSettingsChange(appSettings.copy(mangaRepos = appSettings.mangaRepos - url)) },
            )
        }

        SettingsSection(
            title = "Información",
            description = "Detalles de la ejecución local de AniYomi Desktop.",
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Windows Desktop", fontWeight = FontWeight.Medium)
                    Text("Compose Multiplatform JVM · Skia", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Las credenciales de tracking se administran desde Cuentas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(10.dp)
                .padding(vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scrollState),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
private fun RepositoryEditor(
    title: String,
    repositories: List<String>,
    newValue: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    repositories.forEach { url ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(url, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { onRemove(url) }) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar repositorio", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = newValue,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Añadir")
        }
    }
}
