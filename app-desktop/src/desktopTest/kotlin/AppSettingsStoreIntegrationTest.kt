import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
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

/**
 * Integration coverage for requirements 2.1, 2.4, 2.5, 2.6, 2.7 and 2.8.
 */
class AppSettingsStoreIntegrationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `legacy file migration is persisted and only the normalized official repository is planned`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val officialRepository = "https://official.example/catalog/index.min.json"
            val configuredOfficial = " HTTPS://OFFICIAL.EXAMPLE:443/catalog/./index.min.json#legacy "
            val customRepository = "https://custom.example/catalog/index.min.json"
            val legacyFile = """
                {
                  "extensionDirPath": "C:/legacy/extensions",
                  "extensionRepoUrl": "$customRepository",
                  "animeRepos": ["$configuredOfficial", "$customRepository"],
                  "mangaRepos": ["$customRepository", "$configuredOfficial"],
                  "themeColor": "Blue",
                  "themeMode": "light",
                  "blacklistedExtensions": ["pkg.second", "pkg.first"]
                }
            """.trimIndent()
            Files.writeString(settingsFile, legacyFile, StandardCharsets.UTF_8)

            val result = AppSettingsStore(
                settingsFile = settingsFile,
                defaultExtensionDirectory = directory.resolve("extensions"),
                officialRepositories = setOf(officialRepository),
            ).loadWithStatus()

            assertTrue(result.canAccessRepositoryNetwork)
            assertEquals(CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION, result.settings.extensionUpdates.schemaVersion)
            assertFalse(result.settings.extensionUpdates.automaticCheckEnabled)
            assertEquals(listOf(configuredOfficial, customRepository), result.settings.animeRepos)
            assertEquals(listOf(customRepository, configuredOfficial), result.settings.mangaRepos)
            assertEquals(listOf("pkg.second", "pkg.first"), result.settings.blacklistedExtensions)
            assertEquals(listOf(officialRepository), result.settings.extensionUpdates.trustedRepositories)

            val persisted = json.decodeFromString<AppSettings>(Files.readString(settingsFile))
            assertEquals(result.settings, persisted)
            assertFalse(Files.exists(directory.resolve("settings.json.tmp")))

            val plan = RepositoryPlanner().plan(result.settings, result.canAccessRepositoryNetwork)
            assertEquals(1, plan.size)
            assertEquals(officialRepository, plan.single().normalizedUrl.value)
            assertEquals(configuredOfficial, plan.single().originalUrl)
            assertEquals(0, plan.single().persistedRank)
            assertEquals(setOf(RepositoryCategory.ANIME, RepositoryCategory.MANGA), plan.single().categories)
            assertTrue(plan.single().trusted)
        }

    @Test
    fun `successful trust change atomically replaces the file before planned fetches`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val previousFileLink = directory.resolve("settings-before-replacement.json")
            val officialRepository = "https://official.example/index.min.json"
            val customRepository = "https://custom.example/index.min.json"
            val original = currentSettings(
                directory = directory,
                animeRepositories = listOf(officialRepository, customRepository),
                trustedRepositories = listOf(officialRepository),
                automaticCheckEnabled = false,
            )
            val replacement = original.copy(
                extensionUpdates = original.extensionUpdates.copy(
                    automaticCheckEnabled = true,
                    trustedRepositories = listOf(officialRepository, customRepository),
                ),
            )
            val store = AppSettingsStore(settingsFile)
            store.save(original).getOrThrow()
            Files.createLink(previousFileLink, settingsFile)
            assertTrue(Files.isSameFile(previousFileLink, settingsFile))

            store.save(replacement).getOrThrow()

            assertEquals(original, json.decodeFromString<AppSettings>(Files.readString(previousFileLink)))
            assertEquals(replacement, json.decodeFromString<AppSettings>(Files.readString(settingsFile)))
            assertFalse(Files.isSameFile(previousFileLink, settingsFile))
            assertFalse(Files.exists(directory.resolve("settings.json.tmp")))

            val fetchedRepositories = mutableListOf<String>()
            RepositoryPlanner()
                .plan(AppSettingsStore(settingsFile).load(), store.canAccessRepositoryNetwork)
                .forEach { repository ->
                    assertEquals(
                        replacement,
                        json.decodeFromString<AppSettings>(Files.readString(settingsFile)),
                        "The trust change must already be durable when a fetch starts",
                    )
                    fetchedRepositories += repository.normalizedUrl.value
                }

            assertEquals(listOf(officialRepository, customRepository), fetchedRepositories)
        }

    @Test
    fun `corrupt file recovers safe settings without authorizing a fetch until they are persisted`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val corruptBytes = "{\"extensionUpdates\":".toByteArray(StandardCharsets.UTF_8)
            Files.write(settingsFile, corruptBytes)
            val store = AppSettingsStore(
                settingsFile = settingsFile,
                defaultExtensionDirectory = directory.resolve("extensions"),
            )

            val result = store.loadWithStatus()

            assertFalse(result.canAccessRepositoryNetwork)
            assertNotNull(result.persistenceFailure)
            assertEquals(CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION, result.settings.extensionUpdates.schemaVersion)
            assertFalse(result.settings.extensionUpdates.automaticCheckEnabled)
            assertEquals(listOf(DEFAULT_EXTENSION_REPOSITORY), result.settings.animeRepos)
            assertEquals(listOf(DEFAULT_EXTENSION_REPOSITORY), result.settings.mangaRepos)
            assertEquals(listOf(DEFAULT_EXTENSION_REPOSITORY), result.settings.extensionUpdates.trustedRepositories)
            assertTrue(RepositoryPlanner().plan(result.settings, result.canAccessRepositoryNetwork).isEmpty())
            assertTrue(corruptBytes.contentEquals(Files.readAllBytes(settingsFile)))

            store.save(result.settings).getOrThrow()

            assertTrue(store.canAccessRepositoryNetwork)
            assertEquals(result.settings, AppSettingsStore(settingsFile).load())
            assertFalse(Files.exists(directory.resolve("settings.json.tmp")))
        }

    @Test
    fun `failed trust save keeps prior settings and prevents the next planned fetch`() =
        withTempDirectory { directory ->
            val settingsFile = directory.resolve("settings.json")
            val officialRepository = "https://official.example/index.min.json"
            val customRepository = "https://custom.example/index.min.json"
            val original = currentSettings(
                directory = directory,
                animeRepositories = listOf(officialRepository, customRepository),
                trustedRepositories = listOf(officialRepository),
                automaticCheckEnabled = false,
            )
            AppSettingsStore(settingsFile).save(original).getOrThrow()
            val proposed = original.copy(
                extensionUpdates = original.extensionUpdates.copy(
                    automaticCheckEnabled = true,
                    trustedRepositories = listOf(customRepository),
                ),
            )
            val failingStore = AppSettingsStore(
                settingsFile = settingsFile,
                atomicWriter = { _, _ -> throw IOException("injected settings write failure") },
            )

            val saveResult = failingStore.save(proposed)

            assertTrue(saveResult.isFailure)
            assertFalse(failingStore.canAccessRepositoryNetwork)
            assertNotNull(failingStore.lastPersistenceFailure)
            assertEquals(original, json.decodeFromString<AppSettings>(Files.readString(settingsFile)))
            assertEquals(original, AppSettingsStore(settingsFile).load())

            var fetchCount = 0
            RepositoryPlanner()
                .plan(proposed, failingStore.canAccessRepositoryNetwork)
                .forEach { fetchCount += 1 }

            assertEquals(0, fetchCount)
            assertEquals(
                listOf(officialRepository),
                RepositoryPlanner().plan(original).map { it.normalizedUrl.value },
            )
        }

    private fun currentSettings(
        directory: Path,
        animeRepositories: List<String>,
        trustedRepositories: List<String>,
        automaticCheckEnabled: Boolean,
    ): AppSettings = AppSettings(
        extensionDirPath = directory.resolve("extensions").toString(),
        extensionRepoUrl = animeRepositories.first(),
        animeRepos = animeRepositories,
        mangaRepos = emptyList(),
        blacklistedExtensions = listOf("pkg.blocked"),
        extensionUpdates = ExtensionUpdateSettings(
            schemaVersion = CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
            automaticCheckEnabled = automaticCheckEnabled,
            trustedRepositories = trustedRepositories,
        ),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("app-settings-store-integration-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
