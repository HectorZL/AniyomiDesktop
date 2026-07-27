import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Data structures for Real Anime
@Serializable
data class RealAnime(
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val url: String,
    val sourceName: String = "AnimeFLV"
)

@Serializable
data class RealEpisode(
    val name: String,
    val url: String,
    val episodeNumber: Float
)

@Serializable
data class RealTrack(
    val url: String,
    val lang: String
)

@Serializable
data class RealVideo(
    val name: String,
    val url: String,
    val subtitleTracks: List<RealTrack> = emptyList(),
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class HistoryItem(
    val anime: RealAnime,
    val episode: RealEpisode,
    val videoUrl: String,
    val timestamp: String,
    val progressSeconds: Long = 0L, // Progreso en segundos
    val durationSeconds: Long = 0L, // Duración total en segundos
    val isSeen: Boolean = false // Se marca al terminar el episodio
)

@Serializable
data class TrustedPublicKey(
    val keyId: String,
    val algorithm: String,
    val encodedKey: String,
)

@Serializable
data class ExtensionUpdateSettings(
    val schemaVersion: Int = 0,
    val automaticCheckEnabled: Boolean = false,
    val trustedRepositories: List<String> = emptyList(),
    val repositoryKeys: Map<String, List<TrustedPublicKey>> = emptyMap(),
)

@Serializable
data class AppSettings(
    val extensionDirPath: String = "",
    val extensionRepoUrl: String = "",
    val animeRepos: List<String> = emptyList(),
    val mangaRepos: List<String> = emptyList(),
    val themeColor: String = "Orange",
    val themeMode: String = "dark",
    val blacklistedExtensions: List<String> = emptyList(),
    val extensionUpdates: ExtensionUpdateSettings = ExtensionUpdateSettings(),
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
    // ponytail: logging for verification
    println("[DEBUG] saveHistory called with ${history.size} items")
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

private val legacyAppSettingsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    AppSettingsStore.default()
}

/** Legacy adapter kept while callers migrate to [AppSettingsStore]. */
fun saveSettings(settings: AppSettings) {
    legacyAppSettingsStore.save(settings).exceptionOrNull()?.printStackTrace()
}

/** Legacy adapter kept while callers migrate to [AppSettingsStore]. */
fun loadSettings(): AppSettings = legacyAppSettingsStore.load()

/** Existing repository fetches must fail closed after any settings persistence error. */
fun settingsPersistenceAllowsRepositoryNetwork(): Boolean =
    legacyAppSettingsStore.canAccessRepositoryNetwork
