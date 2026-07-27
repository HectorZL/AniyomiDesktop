package eu.kanade.tachiyomi.extension.update.storage

import eu.kanade.tachiyomi.extension.update.model.LocalMetadataKind
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.VerificationStatus
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Focused coverage for requirements 4.14, 4.16, 5.12 and 7.10. */
class LocalMetadataStoreTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `active sidecar round trip persists all fields and scans as current`() =
        withTemporaryDirectory { root ->
            val files = TransactionFileStore(root)
            val store = LocalMetadataStore(files)
            val packageId = PackageId("eu.kanade.extension.example")
            val jarBytes = ByteArray(16 * 1024) { index -> (index % 251).toByte() }
            Files.write(files.activeJar(packageId), jarBytes)

            val metadata = store.writeForActiveJar(
                packageId = packageId,
                version = VersionDescriptor("2.4.1", 20401),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/example.apk",
                candidateFingerprint = "candidate-fingerprint",
                verification = VerificationStatus.VerifiedByHashAndSignature,
                transactionId = "transaction-current",
                installedAtEpochMillis = 1_700_000_000_000,
            )

            assertEquals(CURRENT_LOCAL_METADATA_SCHEMA_VERSION, metadata.schemaVersion)
            assertEquals(packageId.value, metadata.packageId)
            assertEquals("2.4.1", metadata.version)
            assertEquals(20401, metadata.versionCode)
            assertEquals("https://repo.example/index.min.json", metadata.repository)
            assertEquals("https://repo.example/apk/example.apk", metadata.artifactUrl)
            assertEquals("candidate-fingerprint", metadata.candidateFingerprint)
            assertEquals(PersistedVerificationStatus.VERIFIED_BY_HASH_AND_SIGNATURE, metadata.verification)
            assertEquals(jarBytes.size.toLong(), metadata.activeJarSize)
            assertEquals(sha256Hex(jarBytes), metadata.activeJarSha256)
            assertEquals(1_700_000_000_000, metadata.installedAtEpochMillis)
            assertEquals("transaction-current", metadata.transactionId)

            val read = assertIs<LocalMetadataReadResult.Valid>(store.read(packageId))
            assertEquals(metadata, read.metadata)

            val scan = store.scan()
            val installation = scan.installations.single()
            assertEquals(packageId, installation.packageId)
            assertEquals(LocalMetadataKind.CURRENT, installation.metadataKind)
            assertEquals(VersionDescriptor("2.4.1", 20401), installation.version)
            assertEquals(NormalizedRepositoryUrl("https://repo.example/index.min.json"), installation.origin)
            assertEquals(VerificationStatus.VerifiedByHashAndSignature, installation.verification)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(jarBytes), installation.jarSha256)
            assertTrue(scan.legacyReasons.isEmpty())
            assertTrue(scan.orphanedSidecars.isEmpty())
        }

    @Test
    fun `sidecar replacement is atomic when promotion fails`() = withTemporaryDirectory { root ->
        val normalFiles = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.atomic")
        Files.write(normalFiles.activeJar(packageId), byteArrayOf(1, 2, 3, 4))
        val normalStore = LocalMetadataStore(normalFiles)
        val previous = normalStore.writeForActiveJar(
            packageId = packageId,
            version = VersionDescriptor("1.0.0", 1),
            repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
            artifactUrl = "https://repo.example/apk/atomic.apk",
            candidateFingerprint = "old-fingerprint",
            verification = VerificationStatus.VerifiedByHash,
            transactionId = "transaction-old",
            installedAtEpochMillis = 100,
        )
        val failingFiles = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = object : TransactionFileStoreFaultInjector {
                override fun before(operation: TransactionFileOperation) {
                    if (operation.type == TransactionFileOperationType.MOVE) {
                        throw IOException("injected metadata promotion failure")
                    }
                }
            },
        )

        assertFailsWith<IOException> {
            LocalMetadataStore(failingFiles).write(
                previous.copy(
                    version = "2.0.0",
                    versionCode = 2,
                    candidateFingerprint = "new-fingerprint",
                    transactionId = "transaction-new",
                ),
            )
        }

        val afterFailure = assertIs<LocalMetadataReadResult.Valid>(normalStore.read(packageId))
        assertEquals(previous, afterFailure.metadata)
        assertFalse(
            Files.newDirectoryStream(normalFiles.metadataDirectory) { path ->
                path.fileName.toString().contains(".write-")
            }.use { it.iterator().hasNext() },
        )
    }

    @Test
    fun `missing corrupt and incoherent sidecars produce legacy installations without inferred versions`() =
        withTemporaryDirectory { root ->
            val files = TransactionFileStore(root)
            val store = LocalMetadataStore(files)
            val missing = PackageId("eu.kanade.extension.release_v999")
            val corrupt = PackageId("eu.kanade.extension.corrupt")
            val packageMismatch = PackageId("eu.kanade.extension.package_mismatch")
            val digestMismatch = PackageId("eu.kanade.extension.digest_mismatch")
            val sizeMismatch = PackageId("eu.kanade.extension.size_mismatch")
            val packages = listOf(missing, corrupt, packageMismatch, digestMismatch, sizeMismatch)

            packages.forEachIndexed { index, packageId ->
                Files.write(files.activeJar(packageId), byteArrayOf(index.toByte(), 4, 2, 1))
            }
            files.writeAtomically(files.metadataFile(corrupt), "{not-json".encodeToByteArray())

            val mismatchedMetadata = store.writeForActiveJar(
                packageId = packageMismatch,
                version = VersionDescriptor("3.0.0", 3),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/mismatch.apk",
                candidateFingerprint = "mismatch-fingerprint",
                verification = VerificationStatus.UnverifiedByIndex,
                transactionId = "transaction-package-mismatch",
                installedAtEpochMillis = 300,
            ).copy(packageId = "eu.kanade.extension.someone_else")
            files.writeAtomically(
                files.metadataFile(packageMismatch),
                json.encodeToString(mismatchedMetadata).encodeToByteArray(),
            )

            store.writeForActiveJar(
                packageId = digestMismatch,
                version = VersionDescriptor("4.0.0", 4),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/digest.apk",
                candidateFingerprint = "digest-fingerprint",
                verification = VerificationStatus.VerifiedByHash,
                transactionId = "transaction-digest-mismatch",
                installedAtEpochMillis = 400,
            )
            Files.write(files.activeJar(digestMismatch), byteArrayOf(99, 4, 2, 1))

            store.writeForActiveJar(
                packageId = sizeMismatch,
                version = VersionDescriptor("5.0.0", 5),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/size.apk",
                candidateFingerprint = "size-fingerprint",
                verification = VerificationStatus.VerifiedBySignature,
                transactionId = "transaction-size-mismatch",
                installedAtEpochMillis = 500,
            )
            Files.write(
                files.activeJar(sizeMismatch),
                byteArrayOf(9),
                StandardOpenOption.APPEND,
            )

            val scan = store.scan()
            assertEquals(packages.map { it.value }.sorted(), scan.installations.map { it.packageId.value })
            scan.installations.forEach { installation ->
                assertEquals(LocalMetadataKind.LEGACY, installation.metadataKind)
                assertNull(installation.version)
                assertNull(installation.origin)
                assertNull(installation.verification)
            }
            assertEquals(LegacyJarReason.MISSING_SIDECAR, scan.legacyReasons[missing])
            assertEquals(LegacyJarReason.CORRUPT_SIDECAR, scan.legacyReasons[corrupt])
            assertEquals(LegacyJarReason.INCOHERENT_SIDECAR, scan.legacyReasons[packageMismatch])
            assertEquals(LegacyJarReason.INCOHERENT_SIDECAR, scan.legacyReasons[digestMismatch])
            assertEquals(LegacyJarReason.INCOHERENT_SIDECAR, scan.legacyReasons[sizeMismatch])
            assertNull(scan.installations.single { it.packageId == missing }.version)
        }

    @Test
    fun `orphan cleanup ignores classification and preserves active transaction state`() =
        withTemporaryDirectory { root ->
            val files = TransactionFileStore(root)
            val store = LocalMetadataStore(files)
            val activePackage = PackageId("eu.kanade.extension.active_orphan")
            val inactivePackage = PackageId("eu.kanade.extension.inactive_orphan")
            val corruptPackage = PackageId("eu.kanade.extension.corrupt_orphan")
            val activeTransaction = "transaction-still-active"
            files.createTransaction(activeTransaction)

            Files.write(files.activeJar(activePackage), byteArrayOf(1, 3, 3, 7))
            store.writeForActiveJar(
                packageId = activePackage,
                version = VersionDescriptor("1.0", 1),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/active.apk",
                candidateFingerprint = "active-fingerprint",
                verification = VerificationStatus.UnverifiedByIndex,
                transactionId = activeTransaction,
                installedAtEpochMillis = 10,
            )
            files.deleteActiveJar(activePackage)

            Files.write(files.activeJar(inactivePackage), byteArrayOf(2, 4, 6, 8))
            store.writeForActiveJar(
                packageId = inactivePackage,
                version = VersionDescriptor("2.0", 2),
                repository = NormalizedRepositoryUrl("https://repo.example/index.min.json"),
                artifactUrl = "https://repo.example/apk/inactive.apk",
                candidateFingerprint = "inactive-fingerprint",
                verification = VerificationStatus.VerifiedByHash,
                transactionId = "transaction-finished",
                installedAtEpochMillis = 20,
            )
            files.deleteActiveJar(inactivePackage)
            files.writeAtomically(files.metadataFile(corruptPackage), "broken".encodeToByteArray())

            val beforeCleanup = store.scan()
            assertTrue(beforeCleanup.installations.isEmpty())
            assertEquals(
                setOf(
                    files.metadataFile(activePackage),
                    files.metadataFile(inactivePackage),
                    files.metadataFile(corruptPackage),
                ),
                beforeCleanup.orphanedSidecars.toSet(),
            )

            val cleanup = store.cleanupOrphanedSidecars()

            assertEquals(listOf(files.metadataFile(inactivePackage)), cleanup.deleted)
            assertEquals(
                listOf(files.metadataFile(activePackage)),
                cleanup.retainedForActiveTransactions,
            )
            assertEquals(listOf(files.metadataFile(corruptPackage)), cleanup.retainedUnrecognized)
            assertTrue(Files.exists(files.metadataFile(activePackage), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(files.metadataFile(inactivePackage), LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.exists(files.metadataFile(corruptPackage), LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isDirectory(files.transactionPaths(activeTransaction).directory))
            assertTrue(store.scanInstallations().isEmpty())
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("local-metadata-store-test-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
