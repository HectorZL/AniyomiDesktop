import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.IDN
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

const val CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION = 1
const val DEFAULT_EXTENSION_REPOSITORY =
    "https://raw.githubusercontent.com/yuzono/anime-repo/repo/index.min.json"

val OFFICIAL_EXTENSION_REPOSITORIES: Set<String> = setOf(DEFAULT_EXTENSION_REPOSITORY)

data class AppSettingsLoadResult(
    val settings: AppSettings,
    val canAccessRepositoryNetwork: Boolean,
    val persistenceFailure: Throwable? = null,
)

/**
 * Durable storage boundary for desktop settings.
 *
 * Repository network access is fail-closed: callers must check
 * [canAccessRepositoryNetwork] before using settings that depend on a write.
 */
class AppSettingsStore(
    private val settingsFile: Path,
    private val defaultExtensionDirectory: Path =
        settingsFile.parent?.resolve("extensions") ?: Path.of("extensions"),
    private val defaultRepository: String = DEFAULT_EXTENSION_REPOSITORY,
    private val officialRepositories: Set<String> = OFFICIAL_EXTENSION_REPOSITORIES,
    private val officialRepositoryKeys: Map<String, List<TrustedPublicKey>> = emptyMap(),
    private val repositoryUrlNormalizer: (String) -> String? = ::normalizeRepositoryUrlForSettingsMigration,
    private val atomicWriter: (Path, ByteArray) -> Unit = ::writeSettingsAtomically,
) {
    companion object {
        fun default(userHome: Path = Path.of(System.getProperty("user.home"))): AppSettingsStore {
            val appDirectory = userHome.resolve("AppData").resolve("Local").resolve("AniyomiDesktop")
            return AppSettingsStore(
                settingsFile = appDirectory.resolve("settings.json"),
                defaultExtensionDirectory = appDirectory.resolve("extensions"),
            )
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    var canAccessRepositoryNetwork: Boolean = true
        private set

    @Volatile
    var lastPersistenceFailure: Throwable? = null
        private set

    @Synchronized
    fun load(): AppSettings = loadWithStatus().settings

    @Synchronized
    fun loadWithStatus(): AppSettingsLoadResult {
        val decoded = try {
            if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                return persistMigration(migrateLegacySettings(defaultLegacySettings()))
            }
            require(Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                "Settings path is not a regular file"
            }
            json.decodeFromString<AppSettings>(Files.readString(settingsFile, StandardCharsets.UTF_8))
        } catch (failure: Exception) {
            return failedLoad(migrateLegacySettings(defaultLegacySettings()), failure)
        }

        if (decoded.extensionUpdates.schemaVersion >= CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION) {
            return successfulLoad(decoded)
        }

        return persistMigration(migrateLegacySettings(applyLegacyDefaults(decoded)))
    }

    @Synchronized
    fun save(settings: AppSettings): Result<Unit> {
        return try {
            val bytes = json.encodeToString(settings).toByteArray(StandardCharsets.UTF_8)
            atomicWriter(settingsFile, bytes)
            canAccessRepositoryNetwork = true
            lastPersistenceFailure = null
            Result.success(Unit)
        } catch (failure: Exception) {
            canAccessRepositoryNetwork = false
            lastPersistenceFailure = failure
            Result.failure(failure)
        }
    }

    private fun persistMigration(settings: AppSettings): AppSettingsLoadResult {
        val result = save(settings)
        return if (result.isSuccess) {
            successfulLoad(settings)
        } else {
            failedLoad(settings, result.exceptionOrNull() ?: IOException("Settings migration failed"))
        }
    }

    private fun successfulLoad(settings: AppSettings): AppSettingsLoadResult {
        canAccessRepositoryNetwork = true
        lastPersistenceFailure = null
        return AppSettingsLoadResult(
            settings = settings,
            canAccessRepositoryNetwork = true,
        )
    }

    private fun failedLoad(settings: AppSettings, failure: Throwable): AppSettingsLoadResult {
        canAccessRepositoryNetwork = false
        lastPersistenceFailure = failure
        return AppSettingsLoadResult(
            settings = settings,
            canAccessRepositoryNetwork = false,
            persistenceFailure = failure,
        )
    }

    private fun defaultLegacySettings(): AppSettings = AppSettings(
        extensionDirPath = defaultExtensionDirectory.toAbsolutePath().toString(),
        extensionRepoUrl = defaultRepository,
        animeRepos = listOf(defaultRepository),
        mangaRepos = listOf(defaultRepository),
        themeColor = "Orange",
        themeMode = "dark",
        blacklistedExtensions = emptyList(),
    )

    private fun applyLegacyDefaults(settings: AppSettings): AppSettings {
        val legacyRepository = settings.extensionRepoUrl.ifBlank { defaultRepository }
        return settings.copy(
            extensionDirPath = settings.extensionDirPath.ifBlank {
                defaultExtensionDirectory.toAbsolutePath().toString()
            },
            extensionRepoUrl = legacyRepository,
            animeRepos = settings.animeRepos.ifEmpty { listOf(legacyRepository) },
            mangaRepos = settings.mangaRepos.ifEmpty { listOf(legacyRepository) },
            themeColor = settings.themeColor.ifBlank { "Orange" },
            themeMode = settings.themeMode.ifBlank { "dark" },
        )
    }

    private fun migrateLegacySettings(settings: AppSettings): AppSettings {
        val normalizedOfficialRepositories = officialRepositories
            .mapNotNull(repositoryUrlNormalizer)
            .toSet()
        val trustedRepositories = (settings.animeRepos + settings.mangaRepos)
            .mapNotNull(repositoryUrlNormalizer)
            .filter { it in normalizedOfficialRepositories }
            .distinct()
        val normalizedOfficialKeys = officialRepositoryKeys.entries
            .mapNotNull { (repository, keys) ->
                repositoryUrlNormalizer(repository)?.let { it to keys }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keyLists) -> keyLists.flatten().distinct() }
        val migratedKeys = trustedRepositories
            .mapNotNull { repository ->
                normalizedOfficialKeys[repository]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { repository to it }
            }
            .toMap()

        return settings.copy(
            extensionUpdates = ExtensionUpdateSettings(
                schemaVersion = CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
                automaticCheckEnabled = false,
                trustedRepositories = trustedRepositories,
                repositoryKeys = migratedKeys,
            ),
        )
    }
}

internal fun normalizeRepositoryUrlForSettingsMigration(value: String): String? = runCatching {
    val uri = URI(value.trim())
    require(uri.isAbsolute && !uri.isOpaque)
    require(uri.userInfo == null)

    val scheme = uri.scheme.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https")
    val host = IDN.toASCII(requireNotNull(uri.host).lowercase(Locale.ROOT))
    val port = when {
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }

    URI(
        scheme,
        null,
        host,
        port,
        uri.path,
        uri.query,
        null,
    ).normalize().toASCIIString()
}.getOrNull()

private fun writeSettingsAtomically(target: Path, bytes: ByteArray) {
    val parent = target.parent ?: throw IOException("Settings file must have a parent directory")
    Files.createDirectories(parent)
    require(!Files.isSymbolicLink(parent)) { "Settings directory cannot be a symbolic link" }

    val temporary = target.resolveSibling("${target.fileName}.tmp")
    Files.deleteIfExists(temporary)
    try {
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }

        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("Atomic settings replacement is not supported", failure)
        }

        // Directory fsync is supported on some file systems and not on Windows. The file itself
        // is always forced before the atomic move; this best-effort call additionally durabilizes
        // the directory entry where the platform permits it.
        runCatching {
            FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
