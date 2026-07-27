import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppSettingsStoreTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `new settings disable automatic checks and trust the official default only`() = withTempDirectory { directory ->
        val store = AppSettingsStore(
            settingsFile = directory.resolve("settings.json"),
            defaultExtensionDirectory = directory.resolve("extensions"),
        )

        val result = store.loadWithStatus()

        assertTrue(result.canAccessRepositoryNetwork)
        assertEquals(CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION, result.settings.extensionUpdates.schemaVersion)
        assertFalse(result.settings.extensionUpdates.automaticCheckEnabled)
        assertEquals(listOf(DEFAULT_EXTENSION_REPOSITORY), result.settings.extensionUpdates.trustedRepositories)
        assertTrue(Files.isRegularFile(directory.resolve("settings.json")))
    }

    @Test
    fun `legacy migration preserves repository and blacklist order and trusts only official URLs`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val officialRepository = "https://example.org/repository/index.min.json"
            val configuredOfficial = " HTTPS://EXAMPLE.ORG:443/repository/./index.min.json#ignored "
            val customRepository = "https://custom.example/index.min.json"
            val key = TrustedPublicKey(
                keyId = "official-2026",
                algorithm = "Ed25519",
                encodedKey = "ZmFrZS1wdWJsaWMta2V5",
            )
            val legacyJson = """
                {
                  "extensionDirPath": "C:/extensions",
                  "extensionRepoUrl": "https://legacy.example/index.min.json",
                  "animeRepos": ["$configuredOfficial", "$customRepository"],
                  "mangaRepos": ["$customRepository", "$configuredOfficial"],
                  "themeColor": "Blue",
                  "themeMode": "light",
                  "blacklistedExtensions": ["pkg.two", "pkg.one"]
                }
            """.trimIndent()
            Files.writeString(settingsFile, legacyJson, StandardCharsets.UTF_8)
            val store = AppSettingsStore(
                settingsFile = settingsFile,
                defaultExtensionDirectory = directory.resolve("extensions"),
                officialRepositories = setOf(officialRepository),
                officialRepositoryKeys = mapOf(officialRepository to listOf(key)),
            )

            val loaded = store.load()

            assertEquals(listOf(configuredOfficial, customRepository), loaded.animeRepos)
            assertEquals(listOf(customRepository, configuredOfficial), loaded.mangaRepos)
            assertEquals(listOf("pkg.two", "pkg.one"), loaded.blacklistedExtensions)
            assertFalse(loaded.extensionUpdates.automaticCheckEnabled)
            assertEquals(listOf(officialRepository), loaded.extensionUpdates.trustedRepositories)
            assertEquals(mapOf(officialRepository to listOf(key)), loaded.extensionUpdates.repositoryKeys)
            assertFalse(customRepository in loaded.extensionUpdates.trustedRepositories)

            val persisted = json.decodeFromString<AppSettings>(Files.readString(settingsFile))
            assertEquals(loaded, persisted)
        }

    @Test
    fun `legacy extension repository fills only missing repository lists`() = withTempDirectory { directory ->
        val settingsFile = directory.resolve("settings.json")
        val legacyRepository = "https://legacy.example/index.min.json"
        Files.writeString(
            settingsFile,
            """{"extensionRepoUrl":"$legacyRepository","animeRepos":[],"mangaRepos":[],"blacklistedExtensions":["pkg"]}""",
        )
        val store = AppSettingsStore(
            settingsFile = settingsFile,
            defaultExtensionDirectory = directory.resolve("extensions"),
            officialRepositories = emptySet(),
        )

        val loaded = store.load()

        assertEquals(listOf(legacyRepository), loaded.animeRepos)
        assertEquals(listOf(legacyRepository), loaded.mangaRepos)
        assertEquals(listOf("pkg"), loaded.blacklistedExtensions)
        assertTrue(loaded.extensionUpdates.trustedRepositories.isEmpty())
    }

    @Test
    fun `migration write failure keeps legacy file and closes repository network access`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val original = """{"animeRepos":["$DEFAULT_EXTENSION_REPOSITORY"],"blacklistedExtensions":["pkg"]}"""
            Files.writeString(settingsFile, original)
            val store = AppSettingsStore(
                settingsFile = settingsFile,
                defaultExtensionDirectory = directory.resolve("extensions"),
                atomicWriter = { _, _ -> throw IOException("disk full") },
            )

            val result = store.loadWithStatus()

            assertFalse(result.canAccessRepositoryNetwork)
            assertFalse(store.canAccessRepositoryNetwork)
            assertNotNull(result.persistenceFailure)
            assertEquals(CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION, result.settings.extensionUpdates.schemaVersion)
            assertEquals(listOf("pkg"), result.settings.blacklistedExtensions)
            assertEquals(original, Files.readString(settingsFile))
        }

    @Test
    fun `current settings round trip through an atomic replacement`() = withTempDirectory { directory ->
        val settingsFile = directory.resolve("settings.json")
        val key = TrustedPublicKey("key-1", "Ed25519", "cHVibGljLWtleQ==")
        val settings = AppSettings(
            extensionDirPath = directory.resolve("extensions").toString(),
            extensionRepoUrl = "legacy-value",
            animeRepos = listOf("https://anime.example/index.min.json"),
            mangaRepos = listOf("https://manga.example/index.min.json"),
            themeColor = "Purple",
            themeMode = "light",
            blacklistedExtensions = listOf("pkg.b", "pkg.a"),
            extensionUpdates = ExtensionUpdateSettings(
                schemaVersion = CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
                automaticCheckEnabled = true,
                trustedRepositories = listOf("https://manga.example/index.min.json"),
                repositoryKeys = mapOf("https://manga.example/index.min.json" to listOf(key)),
            ),
        )
        val store = AppSettingsStore(
            settingsFile = settingsFile,
            defaultExtensionDirectory = directory.resolve("extensions"),
        )

        assertTrue(store.save(settings).isSuccess)
        assertFalse(Files.exists(directory.resolve("settings.json.tmp")))
        assertEquals(settings, AppSettingsStore(settingsFile).load())
    }

    @Test
    fun `failed replacement leaves previous settings intact and closes network access`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val original = AppSettings(
                extensionDirPath = "original",
                extensionUpdates = ExtensionUpdateSettings(
                    schemaVersion = CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
                ),
            )
            Files.writeString(settingsFile, json.encodeToString(original))
            val store = AppSettingsStore(
                settingsFile = settingsFile,
                atomicWriter = { _, _ -> throw IOException("read only") },
            )

            val result = store.save(original.copy(extensionDirPath = "changed"))

            assertTrue(result.isFailure)
            assertFalse(store.canAccessRepositoryNetwork)
            assertEquals(original, json.decodeFromString<AppSettings>(Files.readString(settingsFile)))
        }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("app-settings-store-test")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
