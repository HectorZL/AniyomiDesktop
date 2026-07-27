package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.PropertyTestConvention
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryIndexResult
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PROPERTY_NAME =
    "Feature: actualizacion-segura-extensiones, Property 14: Aislamiento de entradas inválidas"

class RepositoryIndexEntryIsolationPropertyTest {
    private val parser = RepositoryIndexParser()
    private val repository = RepositoryRef(
        originalUrl = "https://repo.example/catalog/index.min.json",
        normalizedUrl = NormalizedRepositoryUrl("https://repo.example/catalog/index.min.json"),
        persistedRank = 0,
        categories = setOf(RepositoryCategory.ANIME),
        trusted = true,
    )

    /**
     * **Validates: Requirements 5.3**
     */
    @Test
    fun `Feature actualizacion-segura-extensiones, Property 14 Aislamiento de entradas inválidas`() = runTest {
        assertTrue(requireNotNull(PropertyTestConvention.config.iterations) >= 100, PROPERTY_NAME)

        checkAll(PropertyTestConvention.config, mixedIndexArb) { scenario ->
            val result = parser.parse(
                document = JsonArray(scenario.entries.map(GeneratedIndexEntry::json)).toString(),
                repository = repository,
            )

            val success = assertIs<RepositoryIndexResult.Success>(result)
            val expectedEntries = scenario.entries.mapIndexedNotNull { ordinal, generated ->
                generated.valid?.copy(ordinal = ordinal)
            }
            val expectedInvalidOrdinals = scenario.entries.mapIndexedNotNull { ordinal, generated ->
                ordinal.takeIf { generated.valid == null }
            }

            assertEquals(
                expectedEntries,
                success.entries.map { entry ->
                    ExpectedValidEntry(
                        packageId = entry.packageId.value,
                        version = entry.version.text,
                        artifactReference = entry.artifactReference,
                        ordinal = entry.indexOrdinal,
                    )
                },
                "The parser must return exactly the valid entries in index order",
            )
            assertEquals(
                expectedInvalidOrdinals,
                success.issues.map { it.ordinal },
                "Every invalid entry must produce exactly one positional issue",
            )
            assertEquals(
                expectedInvalidOrdinals.size,
                success.issues.map { it.ordinal }.toSet().size,
                "An invalid entry must not produce duplicate issues",
            )
        }
    }

    private companion object {
        private val packageInvalidKinds = listOf(
            InvalidKind.MISSING_PACKAGE,
            InvalidKind.BLANK_PACKAGE,
            InvalidKind.NON_STRING_PACKAGE,
        )
        private val versionInvalidKinds = listOf(
            InvalidKind.MISSING_VERSION,
            InvalidKind.BLANK_VERSION,
            InvalidKind.NON_STRING_VERSION,
        )
        private val apkInvalidKinds = listOf(
            InvalidKind.MISSING_APK,
            InvalidKind.BLANK_APK,
            InvalidKind.NON_STRING_APK,
            InvalidKind.TRAVERSAL_APK,
            InvalidKind.FOREIGN_ORIGIN_APK,
        )
        private val allInvalidKinds = packageInvalidKinds + versionInvalidKinds + apkInvalidKinds

        private val mixedIndexArb: Arb<MixedIndexScenario> = arbitrary {
            val seed = Arb.int(0..1_000_000).bind()
            val validCount = Arb.int(1..8).bind()
            val packageKind = packageInvalidKinds[Arb.int(packageInvalidKinds.indices).bind()]
            val versionKind = versionInvalidKinds[Arb.int(versionInvalidKinds.indices).bind()]
            val apkKind = apkInvalidKinds[Arb.int(apkInvalidKinds.indices).bind()]
            val extraKindIndexes = Arb.list(
                Arb.int(allInvalidKinds.indices),
                0..8,
            ).bind()
            val orderSeed = Arb.int().bind()

            val validEntries = List(validCount) { index ->
                validEntry(seed = seed, index = index)
            }
            val invalidEntries = (
                listOf(packageKind, versionKind, apkKind) +
                    extraKindIndexes.map(allInvalidKinds::get)
                ).mapIndexed { index, kind ->
                invalidEntry(seed = seed, index = index, kind = kind)
            }

            MixedIndexScenario(
                entries = (validEntries + invalidEntries).shuffled(Random(orderSeed)),
            )
        }

        private fun validEntry(seed: Int, index: Int): GeneratedIndexEntry {
            val packageId = "extension.valid.$seed.$index"
            val version = "${seed % 100}.${index}.${(seed + index) % 100}"
            val artifactReference = if (index % 2 == 0) {
                "extension-$seed-$index.apk"
            } else {
                "releases/extension-$seed-$index.apk"
            }
            return GeneratedIndexEntry(
                json = baseEntry(
                    packageId = packageId,
                    version = version,
                    artifactReference = artifactReference,
                ),
                valid = ExpectedValidEntry(
                    packageId = packageId,
                    version = version,
                    artifactReference = artifactReference,
                ),
            )
        }

        private fun invalidEntry(seed: Int, index: Int, kind: InvalidKind): GeneratedIndexEntry {
            val fields = baseEntryFields(
                packageId = "extension.invalid.$seed.$index",
                version = "1.$index",
                artifactReference = "invalid-$seed-$index.apk",
            )

            when (kind) {
                InvalidKind.MISSING_PACKAGE -> fields.remove("pkg")
                InvalidKind.BLANK_PACKAGE -> fields["pkg"] = JsonPrimitive("   ")
                InvalidKind.NON_STRING_PACKAGE -> fields["pkg"] = JsonPrimitive(index)
                InvalidKind.MISSING_VERSION -> fields.remove("version")
                InvalidKind.BLANK_VERSION -> fields["version"] = JsonPrimitive("   ")
                InvalidKind.NON_STRING_VERSION -> fields["version"] = JsonPrimitive(index)
                InvalidKind.MISSING_APK -> fields.remove("apk")
                InvalidKind.BLANK_APK -> fields["apk"] = JsonPrimitive("   ")
                InvalidKind.NON_STRING_APK -> fields["apk"] = JsonPrimitive(index)
                InvalidKind.TRAVERSAL_APK -> fields["apk"] = JsonPrimitive("../escape-$seed-$index.apk")
                InvalidKind.FOREIGN_ORIGIN_APK -> {
                    fields["apk"] = JsonPrimitive("https://untrusted.example/extension-$seed-$index.apk")
                }
            }

            return GeneratedIndexEntry(json = JsonObject(fields), valid = null)
        }

        private fun baseEntry(
            packageId: String,
            version: String,
            artifactReference: String,
        ): JsonObject = JsonObject(
            baseEntryFields(
                packageId = packageId,
                version = version,
                artifactReference = artifactReference,
            ),
        )

        private fun baseEntryFields(
            packageId: String,
            version: String,
            artifactReference: String,
        ): MutableMap<String, JsonElement> = linkedMapOf(
            "name" to JsonPrimitive(packageId),
            "pkg" to JsonPrimitive(packageId),
            "version" to JsonPrimitive(version),
            "apk" to JsonPrimitive(artifactReference),
            "lang" to JsonPrimitive("en"),
        )
    }
}

private data class MixedIndexScenario(
    val entries: List<GeneratedIndexEntry>,
)

private data class GeneratedIndexEntry(
    val json: JsonObject,
    val valid: ExpectedValidEntry?,
)

private data class ExpectedValidEntry(
    val packageId: String,
    val version: String,
    val artifactReference: String,
    val ordinal: Int = -1,
)

private enum class InvalidKind {
    MISSING_PACKAGE,
    BLANK_PACKAGE,
    NON_STRING_PACKAGE,
    MISSING_VERSION,
    BLANK_VERSION,
    NON_STRING_VERSION,
    MISSING_APK,
    BLANK_APK,
    NON_STRING_APK,
    TRAVERSAL_APK,
    FOREIGN_ORIGIN_APK,
}
