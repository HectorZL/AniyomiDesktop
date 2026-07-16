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

@Serializable
data class AppSettings(
    val extensionDirPath: String = "",
    val extensionRepoUrl: String = "",
    val animeRepos: List<String> = emptyList(),
    val mangaRepos: List<String> = emptyList(),
    val themeColor: String = "Orange",
    val blacklistedExtensions: List<String> = emptyList()
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
    val defaultRepo = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"
    try {
        val appDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop")
        val file = File(appDir, "settings.json")
        if (file.exists()) {
            val loaded = Json.decodeFromString<AppSettings>(file.readText())
            val aRepos = if (loaded.animeRepos.isNotEmpty()) loaded.animeRepos else {
                if (loaded.extensionRepoUrl.isNotEmpty()) listOf(loaded.extensionRepoUrl) else listOf(defaultRepo)
            }
            return AppSettings(
                extensionDirPath = if (loaded.extensionDirPath.isNotEmpty()) loaded.extensionDirPath else defaultPath,
                extensionRepoUrl = loaded.extensionRepoUrl.ifEmpty { defaultRepo },
                animeRepos = aRepos,
                mangaRepos = loaded.mangaRepos,
                themeColor = loaded.themeColor.ifEmpty { "Orange" },
                blacklistedExtensions = loaded.blacklistedExtensions
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return AppSettings(
        extensionDirPath = defaultPath,
        extensionRepoUrl = defaultRepo,
        animeRepos = listOf(defaultRepo),
        mangaRepos = emptyList(),
        themeColor = "Orange",
        blacklistedExtensions = emptyList()
    )
}
