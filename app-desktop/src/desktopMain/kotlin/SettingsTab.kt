import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun SettingsTab(
    appSettings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    var newAnimeRepoUrl by remember { mutableStateOf("") }
    var newMangaRepoUrl by remember { mutableStateOf("") }

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
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Repositorios de Anime
                Text(
                    text = "Repositorios de Anime:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                appSettings.animeRepos.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                val newList = appSettings.animeRepos.toMutableList().apply { remove(url) }
                                onSettingsChange(appSettings.copy(animeRepos = newList))
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newAnimeRepoUrl,
                        onValueChange = { newAnimeRepoUrl = it },
                        placeholder = { Text("https://url-del-repo/index.min.json") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newAnimeRepoUrl.isNotBlank() && !appSettings.animeRepos.contains(newAnimeRepoUrl.trim())) {
                                val newList = appSettings.animeRepos.toMutableList().apply { add(newAnimeRepoUrl.trim()) }
                                onSettingsChange(appSettings.copy(animeRepos = newList))
                                newAnimeRepoUrl = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Añadir")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Repositorios de Manga
                Text(
                    text = "Repositorios de Manga:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                appSettings.mangaRepos.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                val newList = appSettings.mangaRepos.toMutableList().apply { remove(url) }
                                onSettingsChange(appSettings.copy(mangaRepos = newList))
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = newMangaRepoUrl,
                        onValueChange = { newMangaRepoUrl = it },
                        placeholder = { Text("https://url-del-repo/index.min.json") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    Button(
                        onClick = {
                            if (newMangaRepoUrl.isNotBlank() && !appSettings.mangaRepos.contains(newMangaRepoUrl.trim())) {
                                val newList = appSettings.mangaRepos.toMutableList().apply { add(newMangaRepoUrl.trim()) }
                                onSettingsChange(appSettings.copy(mangaRepos = newList))
                                newMangaRepoUrl = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Añadir")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "La carga de APKs traducirá los archivos DEX a JAR o los interpretará mediante la JVM de escritorio para ejecutar los scrapers nativos de Android en PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Tema Visual (Color Principal)",
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
                    text = "Selecciona el color de acento de la aplicación:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                val colors = listOf(
                    "Orange" to "Naranja",
                    "Purple" to "Morado",
                    "Blue" to "Azul",
                    "Green" to "Verde",
                    "Red" to "Rojo"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    colors.forEach { (colorName, displayName) ->
                        val isSelected = appSettings.themeColor == colorName
                        val targetColor = when (colorName) {
                            "Purple" -> Color(0xFF9C27B0)
                            "Blue" -> Color(0xFF2196F3)
                            "Green" -> Color(0xFF4CAF50)
                            "Red" -> Color(0xFFE91E63)
                            else -> Color(0xFFFF9800) // Orange
                        }
                        
                        Button(
                            onClick = {
                                onSettingsChange(appSettings.copy(themeColor = colorName))
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) targetColor else Color(0xFF2C2C2C),
                                contentColor = if (isSelected) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(20.dp),
                            border = if (isSelected) null else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(targetColor, shape = androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
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
