import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 1: Normalización canónica e idempotente"

private data class EquivalentRepositoryUrls(
    val canonicalUrl: String,
    val equivalentUrls: List<String>,
    val queryVariantA: String,
    val expectedQueryIdentityA: String,
    val queryVariantB: String,
    val expectedQueryIdentityB: String,
)

class RepositoryUrlNormalizationPropertyTest {
    /**
     * **Validates: Requirements 2.3**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 1 Normalización canónica e idempotente`() = runTest {
        assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

        checkAll(PropertyTestConvention.config, equivalentRepositoryUrlsArb, invalidRepositoryUrlArb) {
                repositoryUrls,
                invalidUrl,
            ->
            val canonicalIdentity = requireNotNull(
                normalizeRepositoryUrlForSettingsMigration(repositoryUrls.canonicalUrl),
            )
            assertEquals(repositoryUrls.canonicalUrl, canonicalIdentity, PROPERTY_NAME)
            assertEquals(
                canonicalIdentity,
                normalizeRepositoryUrlForSettingsMigration(canonicalIdentity),
                "$PROPERTY_NAME must be idempotent",
            )

            repositoryUrls.equivalentUrls.forEach { equivalentUrl ->
                val equivalentIdentity = normalizeRepositoryUrlForSettingsMigration(equivalentUrl)
                assertEquals(canonicalIdentity, equivalentIdentity, "$PROPERTY_NAME for $equivalentUrl")
                assertEquals(
                    equivalentIdentity,
                    equivalentIdentity?.let(::normalizeRepositoryUrlForSettingsMigration),
                    "$PROPERTY_NAME must remain idempotent for equivalent variants",
                )
            }

            val queryIdentityA = normalizeRepositoryUrlForSettingsMigration(repositoryUrls.queryVariantA)
            val queryIdentityB = normalizeRepositoryUrlForSettingsMigration(repositoryUrls.queryVariantB)
            assertEquals(repositoryUrls.expectedQueryIdentityA, queryIdentityA, PROPERTY_NAME)
            assertEquals(repositoryUrls.expectedQueryIdentityB, queryIdentityB, PROPERTY_NAME)
            assertNotEquals(queryIdentityA, queryIdentityB, "Distinct queries must retain distinct identities")
            assertEquals(queryIdentityA, queryIdentityA?.let(::normalizeRepositoryUrlForSettingsMigration))
            assertEquals(queryIdentityB, queryIdentityB?.let(::normalizeRepositoryUrlForSettingsMigration))

            assertNull(normalizeRepositoryUrlForSettingsMigration(invalidUrl), "Invalid URL: $invalidUrl")
        }
    }

    private companion object {
        private val equivalentRepositoryUrlsArb: Arb<EquivalentRepositoryUrls> = arbitrary {
            val repositoryId = Arb.int(0..100_000).bind()
            val catalogId = Arb.int(0..1_000).bind()
            val fragmentId = Arb.int(0..1_000).bind()
            val secure = Arb.boolean().bind()
            val trailingSlash = Arb.boolean().bind()

            val scheme = if (secure) "https" else "http"
            val defaultPort = if (secure) 443 else 80
            val host = "repo-$repositoryId.example.org"
            val catalogSegment = "catalog-$catalogId"
            val sourceSegment = "source-$repositoryId"
            val path = if (trailingSlash) {
                "/$catalogSegment/$sourceSegment/"
            } else {
                "/$catalogSegment/$sourceSegment/index.min.json"
            }
            val equivalentPath = if (trailingSlash) {
                "/$catalogSegment/./discard-$fragmentId/../$sourceSegment/"
            } else {
                "/$catalogSegment/./discard-$fragmentId/../$sourceSegment/index.min.json"
            }
            val canonicalUrl = "$scheme://$host$path"
            val queryA = "channel=$repositoryId&sort=ascending"
            val queryB = "sort=ascending&channel=$repositoryId"

            EquivalentRepositoryUrls(
                canonicalUrl = canonicalUrl,
                equivalentUrls = listOf(
                    "  $canonicalUrl\t",
                    "${scheme.uppercase()}://${host.uppercase()}$path",
                    "$scheme://$host:$defaultPort$path",
                    "$canonicalUrl#fragment-$fragmentId",
                    "$scheme://$host$equivalentPath",
                    " \t${scheme.uppercase()}://${host.uppercase()}:$defaultPort" +
                        "$equivalentPath#fragment-$fragmentId\r\n",
                ),
                queryVariantA =
                    "${scheme.uppercase()}://${host.uppercase()}:$defaultPort" +
                        "$equivalentPath?$queryA#ignored",
                expectedQueryIdentityA = "$canonicalUrl?$queryA",
                queryVariantB = "$canonicalUrl?$queryB",
                expectedQueryIdentityB = "$canonicalUrl?$queryB",
            )
        }

        private val invalidRepositoryUrlArb: Arb<String> = arbitrary {
            val id = Arb.int(0..100_000).bind()
            when (Arb.int(0..6).bind()) {
                0 -> "repositories-$id/index.min.json"
                1 -> "ftp://repo-$id.example.org/index.min.json"
                2 -> "https://user-$id:secret@repo-$id.example.org/index.min.json"
                3 -> "https:///catalog-$id/index.min.json"
                4 -> "https:catalog-$id/index.min.json"
                5 -> "https://[not-an-ipv6-$id]/index.min.json"
                else -> " \t\r\n "
            }
        }
    }
}
