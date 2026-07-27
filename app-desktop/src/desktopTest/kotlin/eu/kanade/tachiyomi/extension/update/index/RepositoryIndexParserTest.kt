package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.model.IntegrityBlockReason
import eu.kanade.tachiyomi.extension.update.model.IntegrityControl
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryErrorKind
import eu.kanade.tachiyomi.extension.update.model.RepositoryIndexResult
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import eu.kanade.tachiyomi.extension.update.model.VerificationExpectation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryIndexParserTest {
    private val parser = RepositoryIndexParser()
    private val repository = repository("https://repo.example/catalog/index.min.json")

    /** Validates legacy compatibility and unknown-field omission (Requirements 5.1, 5.2). */
    @Test
    fun `legacy entry keeps known metadata and ignores unknown fields`() {
        val result = parser.parse(
            document = """
                [
                  {
                    "name": "Legacy Example",
                    "pkg": "eu.kanade.tachiyomi.animeextension.es.legacy",
                    "apk": "legacy-v1.2.3.apk",
                    "lang": "es",
                    "version": "1.2.3",
                    "nsfw": 0,
                    "sources": [],
                    "futureTopLevelField": {"nested": true}
                  }
                ]
            """.trimIndent(),
            repository = repository,
        )

        val success = assertIs<RepositoryIndexResult.Success>(result)
        assertTrue(success.issues.isEmpty())
        val entry = success.entries.single()
        assertEquals("Legacy Example", entry.name)
        assertEquals("eu.kanade.tachiyomi.animeextension.es.legacy", entry.packageId.value)
        assertEquals("legacy-v1.2.3.apk", entry.artifactReference)
        assertEquals("https://repo.example/catalog/apk/legacy-v1.2.3.apk", entry.artifactUrl.toString())
        assertEquals("es", entry.language)
        assertEquals("1.2.3", entry.version.text)
        assertNull(entry.version.versionCode)
        assertNull(entry.integrity)
        assertEquals(VerificationExpectation.NotPublished, entry.verificationExpectation)
    }

    @Test
    fun `nullable DTO decodes absent values while ignoring future fields`() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<RepositoryIndexEntryDto>(
            """{"future":"value"}""",
        )

        assertNull(dto.name)
        assertNull(dto.pkg)
        assertNull(dto.apk)
        assertNull(dto.version)
        assertNull(dto.versionCode)
        assertNull(dto.hash)
        assertNull(dto.signature)
    }

    @Test
    fun `extended entry maps version and integrity metadata`() {
        val hashBytes = ByteArray(32) { it.toByte() }
        val signatureBytes = ByteArray(64) { (it + 1).toByte() }
        val hashHex = hashBytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        val result = parser.parse(
            document = """
                [
                  {
                    "name": "Extended Example",
                    "pkg": "eu.kanade.tachiyomi.extension.extended",
                    "apk": "downloads/extended.apk",
                    "lang": "all",
                    "version": "2.0.0",
                    "versionCode": 20000,
                    "hash": {
                      "algorithm": "sha-256",
                      "value": "$hashHex",
                      "future": "ignored"
                    },
                    "signature": {
                      "algorithm": "ed25519",
                      "keyId": "repo-key",
                      "value": "$signatureBase64",
                      "future": "ignored"
                    }
                  }
                ]
            """.trimIndent(),
            repository = repository,
        )

        val entry = assertIs<RepositoryIndexResult.Success>(result).entries.single()
        assertEquals(20000, entry.version.versionCode)
        assertEquals("https://repo.example/catalog/downloads/extended.apk", entry.artifactUrl.toString())
        assertEquals("SHA-256", entry.integrity?.hash?.algorithm)
        assertContentEquals(hashBytes, entry.integrity?.hash?.value)
        assertEquals("Ed25519", entry.integrity?.signature?.algorithm)
        assertEquals("repo-key", entry.integrity?.signature?.keyId)
        assertContentEquals(signatureBytes, entry.integrity?.signature?.value)
        assertEquals(
            VerificationExpectation.Required(hash = true, signature = true),
            entry.verificationExpectation,
        )
    }

    /** Validates per-entry isolation (Requirement 5.3). */
    @Test
    fun `invalid records are isolated and malformed optional version code does not discard entry`() {
        val result = parser.parse(
            document = """
                [
                  {
                    "name": "Good",
                    "pkg": "extension.good",
                    "apk": "good.apk",
                    "version": "1.0"
                  },
                  {
                    "name": "Missing package",
                    "apk": "missing.apk",
                    "version": "1.0"
                  },
                  7,
                  {
                    "name": "Traversal",
                    "pkg": "extension.traversal",
                    "apk": "../escape.apk",
                    "version": "1.0"
                  },
                  {
                    "name": "Bad optional code",
                    "pkg": "extension.optional",
                    "apk": "optional.apk",
                    "version": "1.1",
                    "versionCode": "not-a-number"
                  }
                ]
            """.trimIndent(),
            repository = repository,
        )

        val success = assertIs<RepositoryIndexResult.Success>(result)
        assertEquals(listOf("extension.good", "extension.optional"), success.entries.map { it.packageId.value })
        assertNull(success.entries.last().version.versionCode)
        assertEquals(listOf(1, 2, 3, 4), success.issues.map { it.ordinal })
        assertEquals(1, success.issues.count { it.ordinal == 1 })
        assertEquals(1, success.issues.count { it.ordinal == 2 })
        assertEquals(1, success.issues.count { it.ordinal == 3 })
    }

    @Test
    fun `unsafe APK references are rejected without affecting safe same-origin references`() {
        val result = parser.parse(
            document = """
                [
                  {"pkg":"safe.simple","version":"1","apk":"simple.apk"},
                  {"pkg":"safe.relative","version":"1","apk":"releases/relative.apk"},
                  {"pkg":"safe.absolute","version":"1","apk":"https://repo.example/files/absolute.apk"},
                  {"pkg":"unsafe.parent","version":"1","apk":"../escape.apk"},
                  {"pkg":"unsafe.encoded","version":"1","apk":"%2e%2e/escape.apk"},
                  {"pkg":"unsafe.origin","version":"1","apk":"https://evil.example/foreign.apk"},
                  {"pkg":"unsafe.file","version":"1","apk":"file:///tmp/local.apk"},
                  {"pkg":"unsafe.authority","version":"1","apk":"//evil.example/foreign.apk"}
                ]
            """.trimIndent(),
            repository = repository,
        )

        val success = assertIs<RepositoryIndexResult.Success>(result)
        assertEquals(
            listOf("safe.simple", "safe.relative", "safe.absolute"),
            success.entries.map { it.packageId.value },
        )
        assertEquals(
            listOf(
                "https://repo.example/catalog/apk/simple.apk",
                "https://repo.example/catalog/releases/relative.apk",
                "https://repo.example/files/absolute.apk",
            ),
            success.entries.map { it.artifactUrl.toString() },
        )
        assertEquals(listOf(3, 4, 5, 6, 7), success.issues.map { it.ordinal })
    }

    /** Validates whole-document failure classification (Requirement 5.4). */
    @Test
    fun `malformed JSON and non-list roots are repository failures`() {
        val malformed = assertIs<RepositoryIndexResult.Failure>(
            parser.parse("[{", repository),
        )
        val wrongRoot = assertIs<RepositoryIndexResult.Failure>(
            parser.parse("""{"pkg":"extension"}""", repository),
        )

        assertEquals(RepositoryErrorKind.InvalidDocument, malformed.failure.kind)
        assertEquals(RepositoryErrorKind.RootIsNotAList, wrongRoot.failure.kind)
    }

    @Test
    fun `published malformed integrity is blocked rather than treated as absent`() {
        val result = parser.parse(
            document = """
                [
                  {
                    "pkg": "extension.blocked",
                    "apk": "blocked.apk",
                    "version": "1.0",
                    "hash": {"algorithm": "MD5", "value": "00"}
                  }
                ]
            """.trimIndent(),
            repository = repository,
        )

        val entry = assertIs<RepositoryIndexResult.Success>(result).entries.single()
        val expectation = assertIs<VerificationExpectation.Blocked>(entry.verificationExpectation)
        val reason = assertIs<IntegrityBlockReason.UnsupportedAlgorithm>(expectation.reason)
        assertEquals(IntegrityControl.HASH, reason.control)
        assertEquals("MD5", reason.algorithm)
    }

    private fun repository(url: String): RepositoryRef = RepositoryRef(
        originalUrl = url,
        normalizedUrl = NormalizedRepositoryUrl(url),
        persistedRank = 0,
        categories = setOf(RepositoryCategory.ANIME),
        trusted = true,
    )
}
