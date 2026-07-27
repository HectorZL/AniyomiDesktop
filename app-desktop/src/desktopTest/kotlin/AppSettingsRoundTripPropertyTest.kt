import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 2: Round-trip de ajustes de actualización"

class AppSettingsRoundTripPropertyTest {
    /**
     * **Validates: Requirements 1.5, 2.1, 2.7**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 2 Round-trip de ajustes de actualización`() = runTest {
        assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

        checkAll(PropertyTestConvention.config, appSettingsArb) { original ->
            withTempDirectory { directory ->
                val settingsFile = directory.resolve("settings.json")
                val store = AppSettingsStore(
                    settingsFile = settingsFile,
                    defaultExtensionDirectory = directory.resolve("extensions"),
                )

                store.save(original).getOrThrow()
                val loaded = AppSettingsStore(settingsFile).load()

                assertEquals(original.extensionDirPath, loaded.extensionDirPath)
                assertEquals(original.extensionRepoUrl, loaded.extensionRepoUrl)
                assertEquals(original.themeColor, loaded.themeColor)
                assertEquals(original.themeMode, loaded.themeMode)
                assertEquals(original.animeRepos, loaded.animeRepos)
                assertEquals(original.mangaRepos, loaded.mangaRepos)
                assertEquals(original.blacklistedExtensions, loaded.blacklistedExtensions)
                assertEquals(
                    original.extensionUpdates.automaticCheckEnabled,
                    loaded.extensionUpdates.automaticCheckEnabled,
                )
                assertEquals(
                    original.extensionUpdates.trustedRepositories,
                    loaded.extensionUpdates.trustedRepositories,
                )
                assertEquals(original.extensionUpdates.repositoryKeys, loaded.extensionUpdates.repositoryKeys)
                assertEquals(original, loaded)
            }
        }
    }

    private companion object {
        private val repositoryArb: Arb<String> = arbitrary {
            val repositoryId = Arb.int(0..24).bind()
            val pathId = Arb.int(0..4).bind()
            "https://repo-$repositoryId.example.org/catalog-$pathId/index.min.json"
        }

        private val packageIdArb: Arb<String> = arbitrary {
            val namespaceId = Arb.int(0..12).bind()
            val extensionId = Arb.int(0..40).bind()
            "eu.kanade.tachiyomi.extension.generated$namespaceId.extension$extensionId"
        }

        private val appSettingsArb: Arb<AppSettings> = arbitrary {
            val animeRepos = Arb.list(repositoryArb, 0..8).bind()
            val mangaRepos = Arb.list(repositoryArb, 0..8).bind()
            val blacklistedExtensions = Arb.list(packageIdArb, 0..8).bind().distinct()
            val automaticCheckEnabled = Arb.boolean().bind()
            val selectionBits = Arb.long().bind()
            val directoryId = Arb.int(0..10_000).bind()
            val legacyRepositoryId = Arb.int(0..24).bind()
            val themeColorIndex = Arb.int(0..3).bind()
            val themeModeIndex = Arb.int(0..2).bind()

            val configuredRepositories = (animeRepos + mangaRepos).distinct()
            val trustedRepositories = configuredRepositories.filterIndexed { index, _ ->
                selectionBits.hasBit(index)
            }

            AppSettings(
                extensionDirPath = "C:/generated/extensions/$directoryId",
                extensionRepoUrl = "https://legacy-$legacyRepositoryId.example.org/index.min.json",
                animeRepos = animeRepos,
                mangaRepos = mangaRepos,
                themeColor = listOf("Orange", "Blue", "Purple", "Green")[themeColorIndex],
                themeMode = listOf("dark", "light", "system")[themeModeIndex],
                blacklistedExtensions = blacklistedExtensions,
                extensionUpdates = ExtensionUpdateSettings(
                    schemaVersion = CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
                    automaticCheckEnabled = automaticCheckEnabled,
                    trustedRepositories = trustedRepositories,
                    repositoryKeys = trustedRepositories.mapIndexedNotNull { index, repository ->
                        if (selectionBits.hasBit(index + 16)) {
                            repository to trustedKeys(repository, index, selectionBits.hasBit(index + 32))
                        } else {
                            null
                        }
                    }.toMap(),
                ),
            )
        }

        private fun trustedKeys(repository: String, repositoryIndex: Int, includeSecondKey: Boolean) =
            List(if (includeSecondKey) 2 else 1) { keyIndex ->
                val keyBytes = MessageDigest.getInstance("SHA-256").digest(
                    "$repository#$keyIndex".toByteArray(StandardCharsets.UTF_8),
                )
                TrustedPublicKey(
                    keyId = "repository-$repositoryIndex-key-$keyIndex",
                    algorithm = "Ed25519",
                    encodedKey = Base64.getEncoder().encodeToString(keyBytes),
                )
            }

        private fun Long.hasBit(index: Int): Boolean = ((this ushr index) and 1L) == 1L
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("app-settings-round-trip-property")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
