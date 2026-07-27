import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef

/**
 * Builds the ordered repository query plan from persisted desktop settings.
 *
 * Repository identity and trust are both based on normalized URLs. The first configured
 * occurrence supplies the display URL and rank, while later occurrences only add categories.
 */
class RepositoryPlanner(
    private val repositoryUrlNormalizer: (String) -> String? = ::normalizeRepositoryUrlForSettingsMigration,
) {
    fun plan(settings: AppSettings): List<RepositoryRef> = plan(
        animeRepositories = settings.animeRepos,
        mangaRepositories = settings.mangaRepos,
        trustedRepositories = settings.extensionUpdates.trustedRepositories,
    )

    /** A failed settings write closes the plan so callers cannot start the next fetch. */
    fun plan(
        settings: AppSettings,
        canAccessRepositoryNetwork: Boolean,
    ): List<RepositoryRef> = if (canAccessRepositoryNetwork) plan(settings) else emptyList()

    fun plan(
        animeRepositories: List<String>,
        mangaRepositories: List<String>,
        trustedRepositories: Collection<String>,
    ): List<RepositoryRef> {
        val normalizedTrust = trustedRepositories
            .mapNotNull(repositoryUrlNormalizer)
            .toSet()
        val repositoriesByIdentity = linkedMapOf<String, PlannedRepository>()
        val configuredRepositories = animeRepositories.map { it to RepositoryCategory.ANIME } +
            mangaRepositories.map { it to RepositoryCategory.MANGA }

        configuredRepositories.forEachIndexed { persistedRank, (originalUrl, category) ->
            val normalizedUrl = repositoryUrlNormalizer(originalUrl) ?: return@forEachIndexed
            val existing = repositoriesByIdentity[normalizedUrl]
            if (existing == null) {
                repositoriesByIdentity[normalizedUrl] = PlannedRepository(
                    originalUrl = originalUrl,
                    normalizedUrl = normalizedUrl,
                    persistedRank = persistedRank,
                    categories = linkedSetOf(category),
                )
            } else {
                existing.categories += category
            }
        }

        return repositoriesByIdentity.values
            .filter { it.normalizedUrl in normalizedTrust }
            .map { repository ->
                RepositoryRef(
                    originalUrl = repository.originalUrl,
                    normalizedUrl = NormalizedRepositoryUrl(repository.normalizedUrl),
                    persistedRank = repository.persistedRank,
                    categories = repository.categories.toSet(),
                    trusted = true,
                )
            }
    }

    private data class PlannedRepository(
        val originalUrl: String,
        val normalizedUrl: String,
        val persistedRank: Int,
        val categories: LinkedHashSet<RepositoryCategory>,
    )
}
