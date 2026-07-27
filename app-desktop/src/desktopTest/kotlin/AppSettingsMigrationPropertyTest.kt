import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 3: Migración conservadora de confianza"

class AppSettingsMigrationPropertyTest {
    /**
     * **Validates: Requirements 2.2, 2.5, 2.6, 2.7**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 3 Migración conservadora de confianza`() = runTest {
        assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

        checkAll(PropertyTestConvention.config, legacyMigrationCaseArb) { case ->
            withTempDirectory { directory ->
                val settingsFile = directory.resolve("settings.json")
                Files.writeString(
                    settingsFile,
                    Json.encodeToString(case.legacySettings),
                    StandardCharsets.UTF_8,
                )

                val migrated = AppSettingsStore(
                    settingsFile = settingsFile,
                    defaultExtensionDirectory = directory.resolve("extensions"),
                    officialRepositories = case.officialRepositories,
                ).load()

                assertEquals(
                    CURRENT_EXTENSION_UPDATE_SETTINGS_SCHEMA_VERSION,
                    migrated.extensionUpdates.schemaVersion,
                    PROPERTY_NAME,
                )
                assertFalse(migrated.extensionUpdates.automaticCheckEnabled, PROPERTY_NAME)
                assertEquals(case.legacySettings.animeRepos, migrated.animeRepos, PROPERTY_NAME)
                assertEquals(case.legacySettings.mangaRepos, migrated.mangaRepos, PROPERTY_NAME)
                assertEquals(
                    case.legacySettings.blacklistedExtensions,
                    migrated.blacklistedExtensions,
                    PROPERTY_NAME,
                )
                assertEquals(
                    case.expectedTrustedRepositories,
                    migrated.extensionUpdates.trustedRepositories,
                    PROPERTY_NAME,
                )
                assertTrue(
                    migrated.extensionUpdates.trustedRepositories.none(case.nonOfficialNormalizedRepositories::contains),
                    PROPERTY_NAME,
                )
            }
        }
    }

    private companion object {
        private val packageIdArb: Arb<String> = arbitrary {
            val namespace = Arb.int(0..12).bind()
            val extension = Arb.int(0..30).bind()
            "eu.kanade.tachiyomi.extension.generated$namespace.extension$extension"
        }

        private val legacyMigrationCaseArb: Arb<LegacyMigrationCase> = arbitrary {
            val namespace = Arb.int(0..10_000).bind()
            val officialCount = Arb.int(1..5).bind()
            val nonOfficialCount = Arb.int(1..5).bind()
            val officialRepositories = List(officialCount) { index ->
                RepositoryFixture.create(namespace, index, official = true)
            }
            val nonOfficialRepositories = List(nonOfficialCount) { index ->
                RepositoryFixture.create(namespace, index, official = false)
            }
            val repositoryPool = officialRepositories + nonOfficialRepositories

            val randomAnimeIndexes = Arb.list(Arb.int(repositoryPool.indices), 0..8).bind()
            val randomMangaIndexes = Arb.list(Arb.int(repositoryPool.indices), 0..8).bind()
            val animeIndexes = (randomAnimeIndexes + 0)
                .rotateLeft(Arb.int(0..randomAnimeIndexes.size).bind())
            val mangaIndexes = (randomMangaIndexes + officialCount)
                .rotateLeft(Arb.int(0..randomMangaIndexes.size).bind())
            val animeVariants = Arb.list(Arb.int(0..4), animeIndexes.size..animeIndexes.size).bind()
            val mangaVariants = Arb.list(Arb.int(0..4), mangaIndexes.size..mangaIndexes.size).bind()

            val animeOccurrences = animeIndexes.mapIndexed { position, repositoryIndex ->
                repositoryPool[repositoryIndex].occurrence(animeVariants[position])
            }
            val mangaOccurrences = mangaIndexes.mapIndexed { position, repositoryIndex ->
                repositoryPool[repositoryIndex].occurrence(mangaVariants[position])
            }
            val officialVariants = Arb.list(Arb.int(0..4), officialCount..officialCount).bind()
            val configuredOccurrences = animeOccurrences + mangaOccurrences
            val blacklistedExtensions = Arb.list(packageIdArb, 0..10).bind()

            LegacyMigrationCase(
                legacySettings = LegacyAppSettings(
                    extensionDirPath = "C:/generated/extensions/$namespace",
                    extensionRepoUrl = nonOfficialRepositories.first().normalized,
                    animeRepos = animeOccurrences.map(RepositoryOccurrence::original),
                    mangaRepos = mangaOccurrences.map(RepositoryOccurrence::original),
                    themeColor = "Orange",
                    themeMode = "dark",
                    blacklistedExtensions = blacklistedExtensions,
                ),
                officialRepositories = officialRepositories
                    .mapIndexed { index, repository -> repository.variant(officialVariants[index]) }
                    .toSet(),
                expectedTrustedRepositories = configuredOccurrences
                    .filter(RepositoryOccurrence::official)
                    .map(RepositoryOccurrence::normalized)
                    .distinct(),
                nonOfficialNormalizedRepositories = configuredOccurrences
                    .filterNot(RepositoryOccurrence::official)
                    .map(RepositoryOccurrence::normalized)
                    .toSet(),
            )
        }
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("app-settings-migration-property")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

@Serializable
private data class LegacyAppSettings(
    val extensionDirPath: String,
    val extensionRepoUrl: String,
    val animeRepos: List<String>,
    val mangaRepos: List<String>,
    val themeColor: String,
    val themeMode: String,
    val blacklistedExtensions: List<String>,
)

private data class LegacyMigrationCase(
    val legacySettings: LegacyAppSettings,
    val officialRepositories: Set<String>,
    val expectedTrustedRepositories: List<String>,
    val nonOfficialNormalizedRepositories: Set<String>,
)

private data class RepositoryOccurrence(
    val original: String,
    val normalized: String,
    val official: Boolean,
)

private data class RepositoryFixture(
    val scheme: String,
    val host: String,
    val directory: String,
    val query: String,
    val official: Boolean,
) {
    val normalized: String = "$scheme://$host/$directory/index.min.json?$query"

    fun occurrence(variant: Int): RepositoryOccurrence = RepositoryOccurrence(
        original = variant(variant),
        normalized = normalized,
        official = official,
    )

    fun variant(variant: Int): String = when (variant % 5) {
        0 -> normalized
        1 -> " ${scheme.uppercase()}://${host.uppercase()}:443/$directory/./index.min.json?$query#ignored "
        2 -> "$scheme://$host:443/$directory/intermediate/../index.min.json?$query"
        3 -> "$normalized#fragment"
        else -> "$scheme://${host.uppercase()}/./$directory/index.min.json?$query"
    }

    companion object {
        fun create(namespace: Int, index: Int, official: Boolean): RepositoryFixture {
            val kind = if (official) "official" else "custom"
            val domain = if (official) "example.org" else "example.net"
            return RepositoryFixture(
                scheme = "https",
                host = "$kind-$namespace-$index.$domain",
                directory = "catalog-$index",
                query = "channel=${namespace % 7}&format=min",
                official = official,
            )
        }
    }
}

private fun <T> List<T>.rotateLeft(distance: Int): List<T> {
    if (isEmpty()) return this
    val offset = distance % size
    return drop(offset) + take(offset)
}
