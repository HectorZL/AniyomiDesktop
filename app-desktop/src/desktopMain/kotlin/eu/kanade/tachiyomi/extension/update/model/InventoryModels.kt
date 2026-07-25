package eu.kanade.tachiyomi.extension.update.model

import java.nio.file.Path

enum class LocalMetadataKind {
    CURRENT,
    LEGACY,
}

data class LocalInstallation(
    val packageId: PackageId,
    val jar: Path,
    val jarSha256: ByteArray,
    val version: VersionDescriptor?,
    val origin: NormalizedRepositoryUrl?,
    val verification: VerificationStatus?,
    val metadataKind: LocalMetadataKind,
)

enum class ExtensionAction {
    INSTALL,
    UPDATE,
    UPDATE_FROM_UNKNOWN,
    UNINSTALL,
}

sealed interface ConflictReason {
    data class AmbiguousVersion(
        val versions: Set<String> = emptySet(),
        val repositories: Set<NormalizedRepositoryUrl> = emptySet(),
    ) : ConflictReason

    data class ContradictoryIntegrity(
        val control: IntegrityControl,
        val repositories: Set<NormalizedRepositoryUrl> = emptySet(),
    ) : ConflictReason
}

sealed interface InventoryStatus {
    data object Available : InventoryStatus
    data object Installed : InventoryStatus
    data object Outdated : InventoryStatus
    data class RepositoryConflict(val reasons: List<ConflictReason>) : InventoryStatus
}

data class ExtensionInventoryItem(
    val packageId: PackageId,
    val displayName: String,
    val local: LocalInstallation?,
    val remote: RemoteCandidate?,
    val status: InventoryStatus,
    val actions: Set<ExtensionAction>,
    val categories: Set<RepositoryCategory>,
)
