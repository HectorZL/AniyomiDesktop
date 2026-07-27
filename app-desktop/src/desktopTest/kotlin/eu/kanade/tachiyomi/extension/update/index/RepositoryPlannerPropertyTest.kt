package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 4: Plan confiable y sin duplicados"

class RepositoryPlannerPropertyTest {
    /**
     * **Validates: Requirements 2.8, 3.1**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 4 Plan confiable y sin duplicados`() = runTest {
        assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

        checkAll(PropertyTestConvention.config, scenarioArb) { scenario ->
            val plan = RepositoryPlanner().plan(
                animeRepos = scenario.animeRepos.map(GeneratedRepository::url),
                mangaRepos = scenario.mangaRepos.map(GeneratedRepository::url),
                trustedRepositories = scenario.trustedRepositories.map(GeneratedRepository::url).toSet(),
            )
            val expected = expectedPlan(scenario)
            val plannedUrls = plan.map { it.normalizedUrl.value }

            assertEquals(expected.map(ExpectedRepository::normalizedUrl), plannedUrls)
            assertEquals(plannedUrls.size, plannedUrls.toSet().size, "Normalized URLs must be unique")
            assertTrue(plan.all { it.trusted }, "Every planned repository must be trusted")

            plan.zip(expected).forEach { (actual, expectedRepository) ->
                assertEquals(expectedRepository.persistedRank, actual.persistedRank)
                assertEquals(expectedRepository.categories, actual.categories)
            }

            val untrustedConfiguredUrls = configuredRepositoryIds(scenario)
                .minus(scenario.trustedRepositories.map(GeneratedRepository::id).toSet())
                .map(::canonicalRepositoryUrl)
                .toSet()
            assertTrue(
                plan.none { it.normalizedUrl.value in untrustedConfiguredUrls },
                "Untrusted normalized URLs must not enter the query plan",
            )
        }
    }

    private companion object {
        private val scenarioArb: Arb<PlannerScenario> = arbitrary {
            val animeIds = Arb.list(Arb.int(0..8), 0..14).bind()
            val mangaIds = Arb.list(Arb.int(0..8), 0..14).bind()
            val trustedIds = Arb.list(Arb.int(0..11), 0..12).bind()
            val animeVariantSeed = Arb.int(0..4).bind()
            val mangaVariantSeed = Arb.int(0..4).bind()
            val trustVariantSeed = Arb.int(0..4).bind()

            PlannerScenario(
                animeRepos = animeIds.mapIndexed { index, id ->
                    GeneratedRepository(id, repositoryUrlVariant(id, index + animeVariantSeed))
                },
                mangaRepos = mangaIds.mapIndexed { index, id ->
                    GeneratedRepository(id, repositoryUrlVariant(id, index + mangaVariantSeed))
                },
                trustedRepositories = trustedIds.mapIndexed { index, id ->
                    GeneratedRepository(id, repositoryUrlVariant(id, index + trustVariantSeed))
                },
            )
        }

        private fun expectedPlan(scenario: PlannerScenario): List<ExpectedRepository> {
            val trustedIds = scenario.trustedRepositories.map(GeneratedRepository::id).toSet()
            val accumulated = linkedMapOf<Int, ExpectedRepository>()
            val occurrences = scenario.animeRepos.map { it to RepositoryCategory.ANIME } +
                scenario.mangaRepos.map { it to RepositoryCategory.MANGA }

            occurrences.forEachIndexed { rank, (repository, category) ->
                val expected = accumulated.getOrPut(repository.id) {
                    ExpectedRepository(
                        normalizedUrl = canonicalRepositoryUrl(repository.id),
                        persistedRank = rank,
                    )
                }
                expected.categories += category
            }

            return accumulated
                .filterKeys { it in trustedIds }
                .values
                .toList()
        }

        private fun configuredRepositoryIds(scenario: PlannerScenario): Set<Int> =
            (scenario.animeRepos + scenario.mangaRepos).map(GeneratedRepository::id).toSet()

        private fun canonicalRepositoryUrl(id: Int): String =
            "https://repo-$id.example.org/catalog/index.min.json?channel=$id"

        private fun repositoryUrlVariant(id: Int, variant: Int): String = when (Math.floorMod(variant, 5)) {
            0 -> canonicalRepositoryUrl(id)
            1 -> " HTTPS://REPO-$id.EXAMPLE.ORG:443/catalog/index.min.json?channel=$id#ignored "
            2 -> "https://repo-$id.example.org/catalog/section/../index.min.json?channel=$id"
            3 -> "https://repo-$id.example.org/catalog/./index.min.json?channel=$id#different-fragment"
            else -> "https://REPO-$id.example.org:443/catalog/index.min.json?channel=$id"
        }
    }
}

private data class PlannerScenario(
    val animeRepos: List<GeneratedRepository>,
    val mangaRepos: List<GeneratedRepository>,
    val trustedRepositories: List<GeneratedRepository>,
)

private data class GeneratedRepository(
    val id: Int,
    val url: String,
)

private data class ExpectedRepository(
    val normalizedUrl: String,
    val persistedRank: Int,
    val categories: MutableSet<RepositoryCategory> = linkedSetOf(),
)
