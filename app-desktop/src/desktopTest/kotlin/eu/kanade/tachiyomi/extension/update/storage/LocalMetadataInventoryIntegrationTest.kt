package eu.kanade.tachiyomi.extension.update.storage

import eu.kanade.tachiyomi.extension.update.model.LocalMetadataKind
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.VerificationStatus
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Integration coverage for requirements 4.14-4.18 and 5.12 using the real filesystem stores. */
class LocalMetadataInventoryIntegrationTest {
    @Test
    fun `mixed disk state produces a conservative local inventory after restart`() =
        withTemporaryDirectory { root ->
            val files = TransactionFileStore(root)
            val metadataStore = LocalMetadataStore(files)
            val unindexed = PackageId("eu.kanade.extension.unindexed")
            val digestMismatch = PackageId("eu.kanade.extension.digest_mismatch")
            val sizeMismatch = PackageId("eu.kanade.extension.size_mismatch")
            val corrupt = PackageId("eu.kanade.extension.corrupt")
            val orphaned = PackageId("eu.kanade.extension.orphaned")

            Files.write(files.activeJar(unindexed), byteArrayOf(1, 2, 3, 4))
            metadataStore.writeForActiveJar(
                packageId = unindexed,
                version = VersionDescriptor("2.1.0", 20100),
                repository = REPOSITORY,
                artifactUrl = "https://repo.example/apk/unindexed.apk",
                candidateFingerprint = "unindexed-fingerprint",
                verification = VerificationStatus.VerifiedBySignature,
                transactionId = "transaction-unindexed",
                installedAtEpochMillis = 100,
            )

            Files.write(files.activeJar(digestMismatch), byteArrayOf(5, 6, 7, 8))
            metadataStore.writeForActiveJar(
                packageId = digestMismatch,
                version = VersionDescriptor("3.0.0", 3),
                repository = REPOSITORY,
                artifactUrl = "https://repo.example/apk/digest.apk",
                candidateFingerprint = "digest-fingerprint",
                verification = VerificationStatus.VerifiedByHash,
                transactionId = "transaction-digest",
                installedAtEpochMillis = 200,
            )
            Files.write(files.activeJar(digestMismatch), byteArrayOf(8, 7, 6, 5))

            Files.write(files.activeJar(sizeMismatch), byteArrayOf(9, 10, 11, 12))
            metadataStore.writeForActiveJar(
                packageId = sizeMismatch,
                version = VersionDescriptor("4.0.0", 4),
                repository = REPOSITORY,
                artifactUrl = "https://repo.example/apk/size.apk",
                candidateFingerprint = "size-fingerprint",
                verification = VerificationStatus.VerifiedByHashAndSignature,
                transactionId = "transaction-size",
                installedAtEpochMillis = 300,
            )
            Files.write(
                files.activeJar(sizeMismatch),
                byteArrayOf(13),
                StandardOpenOption.APPEND,
            )

            Files.write(files.activeJar(corrupt), byteArrayOf(14, 15, 16, 17))
            files.writeAtomically(files.metadataFile(corrupt), "{not-json".encodeToByteArray())

            Files.write(files.activeJar(orphaned), byteArrayOf(18, 19, 20, 21))
            metadataStore.writeForActiveJar(
                packageId = orphaned,
                version = VersionDescriptor("5.0.0", 5),
                repository = REPOSITORY,
                artifactUrl = "https://repo.example/apk/orphaned.apk",
                candidateFingerprint = "orphaned-fingerprint",
                verification = VerificationStatus.UnverifiedByIndex,
                transactionId = "transaction-orphaned",
                installedAtEpochMillis = 400,
            )
            files.deleteActiveJar(orphaned)

            val reopenedFiles = TransactionFileStore(root)
            val reopenedStore = LocalMetadataStore(reopenedFiles)
            val scan = reopenedStore.scan()

            assertEquals(
                listOf(corrupt, digestMismatch, sizeMismatch, unindexed).map(PackageId::value).sorted(),
                scan.installations.map { it.packageId.value },
            )

            val localOnlyInstallation = scan.installations.single { it.packageId == unindexed }
            assertEquals(LocalMetadataKind.CURRENT, localOnlyInstallation.metadataKind)
            assertEquals(VersionDescriptor("2.1.0", 20100), localOnlyInstallation.version)
            assertEquals(REPOSITORY, localOnlyInstallation.origin)
            assertEquals(VerificationStatus.VerifiedBySignature, localOnlyInstallation.verification)

            listOf(digestMismatch, sizeMismatch, corrupt).forEach { packageId ->
                val installation = scan.installations.single { it.packageId == packageId }
                assertEquals(LocalMetadataKind.LEGACY, installation.metadataKind)
                assertNull(installation.version)
                assertNull(installation.origin)
                assertNull(installation.verification)
            }
            assertEquals(LegacyJarReason.INCOHERENT_SIDECAR, scan.legacyReasons[digestMismatch])
            assertEquals(LegacyJarReason.INCOHERENT_SIDECAR, scan.legacyReasons[sizeMismatch])
            assertEquals(LegacyJarReason.CORRUPT_SIDECAR, scan.legacyReasons[corrupt])

            assertFalse(scan.installations.any { it.packageId == orphaned })
            assertEquals(listOf(reopenedFiles.metadataFile(orphaned)), scan.orphanedSidecars)
            assertIs<LocalMetadataReadResult.Valid>(reopenedStore.read(orphaned))
        }

    @Test
    fun `interrupted sidecar replacement reopens as one complete inventory state`() {
        InterruptionPoint.entries.forEach { interruptionPoint ->
            withTemporaryDirectory { root ->
                val files = TransactionFileStore(root)
                val metadataStore = LocalMetadataStore(files)
                val packageId = PackageId("eu.kanade.extension.interrupted_${interruptionPoint.name.lowercase()}")
                Files.write(files.activeJar(packageId), byteArrayOf(22, 23, 24, 25))
                val previous = metadataStore.writeForActiveJar(
                    packageId = packageId,
                    version = VersionDescriptor("1.0.0", 1),
                    repository = REPOSITORY,
                    artifactUrl = "https://repo.example/apk/interrupted.apk",
                    candidateFingerprint = "previous-fingerprint",
                    verification = VerificationStatus.VerifiedByHash,
                    transactionId = "transaction-previous",
                    installedAtEpochMillis = 500,
                )
                val replacement = previous.copy(
                    version = "2.0.0",
                    versionCode = 2,
                    candidateFingerprint = "replacement-fingerprint",
                    verification = PersistedVerificationStatus.VERIFIED_BY_HASH_AND_SIGNATURE,
                    transactionId = "transaction-replacement",
                    installedAtEpochMillis = 600,
                )
                val interruptedFiles = TransactionFileStore(
                    extensionDirectory = root,
                    faultInjector = interruptionPoint.injector(),
                )

                assertFailsWith<IOException> {
                    LocalMetadataStore(interruptedFiles).write(replacement)
                }

                val reopenedFiles = TransactionFileStore(root)
                val reopenedStore = LocalMetadataStore(reopenedFiles)
                val expected = when (interruptionPoint) {
                    InterruptionPoint.BEFORE_PROMOTION -> previous
                    InterruptionPoint.AFTER_PROMOTION -> replacement
                }
                assertEquals(
                    expected,
                    assertIs<LocalMetadataReadResult.Valid>(reopenedStore.read(packageId)).metadata,
                )

                val scan = reopenedStore.scan()
                val installation = scan.installations.single()
                assertEquals(LocalMetadataKind.CURRENT, installation.metadataKind)
                assertEquals(VersionDescriptor(expected.version, expected.versionCode), installation.version)
                assertEquals(
                    when (expected.verification) {
                        PersistedVerificationStatus.VERIFIED_BY_HASH -> VerificationStatus.VerifiedByHash
                        PersistedVerificationStatus.VERIFIED_BY_SIGNATURE -> VerificationStatus.VerifiedBySignature
                        PersistedVerificationStatus.VERIFIED_BY_HASH_AND_SIGNATURE -> {
                            VerificationStatus.VerifiedByHashAndSignature
                        }
                        PersistedVerificationStatus.UNVERIFIED_BY_INDEX -> VerificationStatus.UnverifiedByIndex
                    },
                    installation.verification,
                )
                assertTrue(scan.legacyReasons.isEmpty())
                assertTrue(scan.orphanedSidecars.isEmpty())
                assertNoWriteTemporary(reopenedFiles.metadataFile(packageId))
            }
        }
    }

    private fun InterruptionPoint.injector(): TransactionFileStoreFaultInjector =
        object : TransactionFileStoreFaultInjector {
            override fun before(operation: TransactionFileOperation) {
                if (this@injector == InterruptionPoint.BEFORE_PROMOTION &&
                    operation.type == TransactionFileOperationType.MOVE
                ) {
                    throw IOException("injected interruption before sidecar promotion")
                }
            }

            override fun after(operation: TransactionFileOperation) {
                if (this@injector == InterruptionPoint.AFTER_PROMOTION &&
                    operation.type == TransactionFileOperationType.MOVE
                ) {
                    throw IOException("injected interruption after sidecar promotion")
                }
            }
        }

    private fun assertNoWriteTemporary(target: Path) {
        val prefix = ".${target.fileName}.write-"
        Files.newDirectoryStream(target.parent) { path ->
            path.fileName.toString().startsWith(prefix)
        }.use { leftovers ->
            assertFalse(leftovers.iterator().hasNext(), "Interrupted write left a temporary sidecar")
        }
    }

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("local-metadata-integration-test-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private enum class InterruptionPoint {
        BEFORE_PROMOTION,
        AFTER_PROMOTION,
    }

    private companion object {
        val REPOSITORY = NormalizedRepositoryUrl("https://repo.example/index.min.json")
    }
}
