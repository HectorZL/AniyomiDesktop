package eu.kanade.tachiyomi.extension.update.model

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.MangaSource
import java.net.URI
import java.nio.file.Path
import java.time.Instant

sealed interface ExtensionProgressEvent {
    val packageId: PackageId
    val stage: TransactionStage
    val occurredAt: Instant

    data class StageChanged(
        override val packageId: PackageId,
        override val stage: TransactionStage,
        override val occurredAt: Instant = Instant.now(),
    ) : ExtensionProgressEvent

    data class DownloadProgress(
        override val packageId: PackageId,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        override val occurredAt: Instant = Instant.now(),
    ) : ExtensionProgressEvent {
        override val stage: TransactionStage = TransactionStage.DOWNLOADING

        init {
            require(bytesDownloaded >= 0) { "Downloaded byte count cannot be negative" }
            require(totalBytes == null || totalBytes >= bytesDownloaded) {
                "Total byte count cannot be less than downloaded bytes"
            }
        }
    }
}

sealed interface LoadedExtensionSource {
    data class Anime(val source: AnimeSource) : LoadedExtensionSource
    data class Manga(val source: MangaSource) : LoadedExtensionSource
}

data class ExtensionLoadSnapshot(
    val sources: List<LoadedExtensionSource>,
    val installedJars: Set<Path>,
    val errors: Map<String, List<String>>,
)

sealed interface RuntimeDetachResult {
    val packageId: PackageId

    data class Detached(
        override val packageId: PackageId,
        val detachedSources: Int,
    ) : RuntimeDetachResult

    data class Failed(
        override val packageId: PackageId,
        val error: ExtensionUpdateError,
    ) : RuntimeDetachResult
}

sealed interface RuntimeRefreshResult {
    data class Refreshed(
        val excludedPackages: Set<PackageId>,
    ) : RuntimeRefreshResult

    data class Failed(
        val errors: Map<String, List<String>>,
    ) : RuntimeRefreshResult
}

data class ArtifactRequest(
    val packageId: PackageId,
    val artifactUrl: URI,
    val repository: NormalizedRepositoryUrl,
    val fingerprint: CandidateFingerprint,
)

sealed interface CandidateValidationReport {
    data class Valid(
        val packageId: PackageId,
        val loadedSourceCount: Int,
    ) : CandidateValidationReport

    data class Invalid(
        val packageId: PackageId,
        val error: ValidationErrorKind,
    ) : CandidateValidationReport
}

sealed interface ExtensionOperationResult {
    val packageId: PackageId
}

sealed interface TransactionResult : ExtensionOperationResult {
    data class Success(
        override val packageId: PackageId,
        val installedVersion: VersionDescriptor,
        val verification: VerificationStatus,
        val transactionId: String,
    ) : TransactionResult

    data class Failed(
        override val packageId: PackageId,
        val error: ExtensionUpdateError,
        val verification: VerificationStatus? = null,
    ) : TransactionResult

    data class RolledBack(
        override val packageId: PackageId,
        val restoredVersion: VersionDescriptor?,
        val restoredVerification: VerificationStatus?,
        val error: ExtensionUpdateError,
    ) : TransactionResult

    data class RecoveryPending(
        override val packageId: PackageId,
        val diagnosticId: String,
    ) : TransactionResult
}

sealed interface UninstallResult : ExtensionOperationResult {
    data class Removed(override val packageId: PackageId) : UninstallResult

    data class Deferred(
        override val packageId: PackageId,
        val message: String,
    ) : UninstallResult

    data class Failed(
        override val packageId: PackageId,
        val error: ExtensionUpdateError,
        val message: String? = null,
    ) : UninstallResult
}

data class RecoveryReport(
    val restoredPackages: Set<PackageId> = emptySet(),
    val pendingPackages: Map<PackageId, String> = emptyMap(),
    val failures: List<ExtensionUpdateError.Recovery> = emptyList(),
)
