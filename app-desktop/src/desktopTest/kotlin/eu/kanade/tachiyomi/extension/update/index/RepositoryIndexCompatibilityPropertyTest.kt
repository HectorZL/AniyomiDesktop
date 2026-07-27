package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryIndexResult
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import eu.kanade.tachiyomi.extension.update.model.VerificationExpectation
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 13: Compatibilidad de índices hacia atrás y delante"

class RepositoryIndexCompatibilityPropertyTest {
    private val parser = RepositoryIndexParser()
    private val repository = RepositoryRef(
        originalUrl = REPOSITORY_URL,
        normalizedUrl = NormalizedRepositoryUrl(REPOSITORY_URL),
        persistedRank = 0,
        categories = setOf(RepositoryCategory.ANIME),
        trusted = true,
    )

    /**
     * **Validates: Requirements 5.1, 5.2**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 13 Compatibilidad de índices hacia atrás y delante`() =
        runTest {
            assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

            checkAll(PropertyTestConvention.config, legacyEntryArb) { generated ->
                val legacy = assertIs<RepositoryIndexResult.Success>(
                    parser.parse(generated.toJson(includeUnknownFields = false), repository),
                )
                val forwardCompatible = assertIs<RepositoryIndexResult.Success>(
                    parser.parse(generated.toJson(includeUnknownFields = true), repository),
                )

                assertTrue(legacy.issues.isEmpty(), "$PROPERTY_NAME must accept valid legacy entries")
                assertTrue(
                    forwardCompatible.issues.isEmpty(),
                    "$PROPERTY_NAME must ignore unknown fields",
                )
                assertEquals(
                    legacy.entries,
                    forwardCompatible.entries,
                    "$PROPERTY_NAME must preserve the resulting domain",
                )

                val entry = legacy.entries.single()
                assertEquals(generated.name, entry.name)
                assertEquals(generated.packageId, entry.packageId.value)
                assertEquals(generated.apk, entry.artifactReference)
                assertEquals(generated.artifactUrl, entry.artifactUrl.toString())
                assertEquals(generated.language, entry.language)
                assertEquals(generated.version, entry.version.text)
                assertNull(entry.version.versionCode)
                assertNull(entry.integrity)
                assertEquals(VerificationExpectation.NotPublished, entry.verificationExpectation)
            }
        }

    private companion object {
        private val legacyEntryArb: Arb<GeneratedLegacyEntry> = arbitrary {
            val id = Arb.int(0..1_000_000).bind()
            val major = Arb.int(0..10_000).bind()
            val minor = Arb.int(0..10_000).bind()
            val patch = Arb.int(0..10_000).bind()
            val nestedPath = Arb.boolean().bind()
            val sourceIds = Arb.list(Arb.int(0..1_000_000), 0..4).bind()
            val fileName = "generated-$id-v$major.$minor.$patch.apk"
            val apk = if (nestedPath) "releases-$minor/$fileName" else fileName
            val artifactUrl = if (nestedPath) {
                "https://repo.example/catalog/releases-$minor/$fileName"
            } else {
                "https://repo.example/catalog/apk/$fileName"
            }

            GeneratedLegacyEntry(
                name = "Generated extension $id",
                packageId = "eu.kanade.tachiyomi.animeextension.generated.extension$id",
                apk = apk,
                artifactUrl = artifactUrl,
                language = LANGUAGES[id % LANGUAGES.size],
                version = "$major.$minor.$patch",
                nsfw = id % 2,
                sources = sourceIds.mapIndexed { index, sourceId ->
                    GeneratedLegacySource(
                        name = "Generated source $sourceId",
                        language = LANGUAGES[sourceId % LANGUAGES.size],
                        id = sourceId.toString(),
                        baseUrl = "https://source-$sourceId.example.org/path-$index",
                    )
                },
                unknownSeed = id + major + minor + patch,
            )
        }
    }
}

private data class GeneratedLegacyEntry(
    val name: String,
    val packageId: String,
    val apk: String,
    val artifactUrl: String,
    val language: String,
    val version: String,
    val nsfw: Int,
    val sources: List<GeneratedLegacySource>,
    val unknownSeed: Int,
) {
    fun toJson(includeUnknownFields: Boolean): String = buildJsonArray {
        add(
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("pkg", JsonPrimitive(packageId))
                put("apk", JsonPrimitive(apk))
                put("lang", JsonPrimitive(language))
                put("version", JsonPrimitive(version))
                put("nsfw", JsonPrimitive(nsfw))
                putJsonArray("sources") {
                    sources.forEachIndexed { index, source ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(source.name))
                                put("lang", JsonPrimitive(source.language))
                                put("id", JsonPrimitive(source.id))
                                put("baseUrl", JsonPrimitive(source.baseUrl))
                                if (includeUnknownFields) {
                                    putJsonObject("futureSourceField$index") {
                                        put("enabled", JsonPrimitive(index % 2 == 0))
                                        put("seed", JsonPrimitive(unknownSeed + index))
                                    }
                                }
                            },
                        )
                    }
                }
                if (includeUnknownFields) {
                    put("futureScalar", JsonPrimitive(unknownSeed))
                    putJsonObject("futureObject") {
                        put("enabled", JsonPrimitive(unknownSeed % 2 == 0))
                        putJsonArray("nestedValues") {
                            add(JsonPrimitive("future-$unknownSeed"))
                            add(JsonPrimitive(unknownSeed.toLong()))
                        }
                    }
                    putJsonArray("futureArray") {
                        add(JsonPrimitive(true))
                        add(
                            buildJsonObject {
                                put("deeplyNested", JsonPrimitive("ignored-$unknownSeed"))
                            },
                        )
                    }
                }
            },
        )
    }.toString()
}

private data class GeneratedLegacySource(
    val name: String,
    val language: String,
    val id: String,
    val baseUrl: String,
)

private const val REPOSITORY_URL = "https://repo.example/catalog/index.min.json"
private val LANGUAGES = listOf("all", "en", "es", "ja", "pt-BR")
