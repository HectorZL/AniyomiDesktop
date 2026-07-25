package eu.kanade.tachiyomi.extension.update

import eu.kanade.tachiyomi.extension.update.model.ArtifactHash
import eu.kanade.tachiyomi.extension.update.model.CandidateFingerprint
import eu.kanade.tachiyomi.extension.update.model.CheckState
import eu.kanade.tachiyomi.extension.update.model.ExtensionAction
import eu.kanade.tachiyomi.extension.update.model.ExtensionInventoryItem
import eu.kanade.tachiyomi.extension.update.model.ExtensionProgressEvent
import eu.kanade.tachiyomi.extension.update.model.ExtensionUpdateState
import eu.kanade.tachiyomi.extension.update.model.IntegrityDescriptor
import eu.kanade.tachiyomi.extension.update.model.InventoryStatus
import eu.kanade.tachiyomi.extension.update.model.LocalInstallation
import eu.kanade.tachiyomi.extension.update.model.LocalMetadataKind
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.PackageOperationState
import eu.kanade.tachiyomi.extension.update.model.RemoteCandidate
import eu.kanade.tachiyomi.extension.update.model.RemoteExtensionEntry
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import eu.kanade.tachiyomi.extension.update.model.TransactionStage
import eu.kanade.tachiyomi.extension.update.model.UninstallResult
import eu.kanade.tachiyomi.extension.update.model.VerificationExpectation
import eu.kanade.tachiyomi.extension.update.model.VerificationStatus
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionUpdateContractsTest {

    @Test
    fun `inventory item exposes package versions origin and verification`() {
        val packageId = PackageId("eu.kanade.tachiyomi.animeextension.es.example")
        val repositoryUrl = NormalizedRepositoryUrl("https://example.invalid/index.min.json")
        val repository = RepositoryRef(
            originalUrl = repositoryUrl.value,
            normalizedUrl = repositoryUrl,
            persistedRank = 0,
            categories = setOf(RepositoryCategory.ANIME),
            trusted = true,
        )
        val remoteVersion = VersionDescriptor("2.0.0", 2)
        val entry = RemoteExtensionEntry(
            name = "Example",
            packageId = packageId,
            artifactReference = "example.apk",
            artifactUrl = URI("https://example.invalid/apk/example.apk"),
            language = "es",
            version = remoteVersion,
            integrity = IntegrityDescriptor(
                hash = ArtifactHash("SHA-256", byteArrayOf(1, 2, 3)),
                signature = null,
            ),
            verificationExpectation = VerificationExpectation.Required(hash = true, signature = false),
            repository = repository,
            indexOrdinal = 0,
        )
        val fingerprint = CandidateFingerprint(
            packageId = packageId,
            versionText = remoteVersion.text,
            versionCode = remoteVersion.versionCode,
            repository = repositoryUrl,
            artifactUrl = entry.artifactUrl.toString(),
            integrityCanonical = "sha-256:010203",
        )
        val localVersion = VersionDescriptor("1.0.0", 1)
        val local = LocalInstallation(
            packageId = packageId,
            jar = Path.of("extensions", "${packageId.value}.jar"),
            jarSha256 = byteArrayOf(4, 5, 6),
            version = localVersion,
            origin = repositoryUrl,
            verification = VerificationStatus.VerifiedByHash,
            metadataKind = LocalMetadataKind.CURRENT,
        )

        val item = ExtensionInventoryItem(
            packageId = packageId,
            displayName = entry.name,
            local = local,
            remote = RemoteCandidate(entry, fingerprint),
            status = InventoryStatus.Outdated,
            actions = setOf(ExtensionAction.UPDATE, ExtensionAction.UNINSTALL),
            categories = setOf(RepositoryCategory.ANIME),
        )

        assertEquals(packageId, item.packageId)
        assertEquals(localVersion, item.local?.version)
        assertEquals(remoteVersion, item.remote?.entry?.version)
        assertEquals(repositoryUrl, item.remote?.entry?.repository?.normalizedUrl)
        assertEquals(VerificationStatus.VerifiedByHash, item.local?.verification)
        assertTrue(ExtensionAction.UPDATE in item.actions)
    }

    @Test
    fun `default update state is idle and has no parallel state`() {
        val state = ExtensionUpdateState()

        assertEquals(CheckState.Idle, state.check)
        assertTrue(state.inventory.isEmpty())
        assertTrue(state.operations.isEmpty())
        assertNull(state.confirmation)
        assertNull(state.batch)
        assertEquals(0, state.revision)
    }

    @Test
    fun `operation state rejects progress for another package`() {
        val packageId = PackageId("extension.one")
        val otherPackageId = PackageId("extension.two")
        val event = ExtensionProgressEvent.StageChanged(
            packageId = otherPackageId,
            stage = TransactionStage.VERIFYING,
        )

        assertFailsWith<IllegalArgumentException> {
            PackageOperationState.InProgress(packageId, event)
        }
    }

    @Test
    fun `verification expectation requires at least one control`() {
        assertFailsWith<IllegalArgumentException> {
            VerificationExpectation.Required(hash = false, signature = false)
        }
    }

    @Test
    fun `legacy deferred uninstall maps to typed restart result`() {
        val packageId = PackageId("extension.locked")

        val result = legacyUninstallResult(
            packageId = packageId,
            result = Triple(false, true, "restart required"),
        )

        val deferred = assertIs<UninstallResult.Deferred>(result)
        assertEquals(packageId, deferred.packageId)
        assertEquals("restart required", deferred.message)
    }

    @Test
    fun `legacy empty load result keeps jars and errors in snapshot`() {
        val jar = Path.of("extensions", "extension.jar")
        val errors = mapOf("extension.jar" to listOf("load failed"))

        val snapshot = legacyLoadSnapshot(
            loaded = emptyList<eu.kanade.tachiyomi.extension.ExtensionManager.LoadedSource>() to errors,
            installedJars = setOf(jar),
        )

        assertTrue(snapshot.sources.isEmpty())
        assertEquals(setOf(jar), snapshot.installedJars)
        assertEquals(errors, snapshot.errors)
    }
}
