package eu.kanade.tachiyomi.extension.update.model

import java.time.Instant

enum class OperationKind {
    INSTALL,
    UPDATE,
    UPDATE_ALL,
    UNINSTALL,
}

data class ConfirmedItemPreview(
    val packageId: PackageId,
    val displayName: String,
    val localVersion: VersionDescriptor?,
    val remoteVersion: VersionDescriptor?,
    val repository: NormalizedRepositoryUrl?,
    val verificationExpectation: VerificationExpectation?,
    val currentVerification: VerificationStatus?,
    val candidateFingerprint: CandidateFingerprint?,
)

data class ConfirmationRequest(
    val id: String,
    val kind: OperationKind,
    val items: List<ConfirmedItemPreview>,
    val inventoryRevision: Long,
    val settingsRevision: Long,
    val issuedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Confirmation id cannot be blank" }
        require(items.isNotEmpty()) { "Confirmation must contain at least one item" }
        require(inventoryRevision >= 0) { "Inventory revision cannot be negative" }
        require(settingsRevision >= 0) { "Settings revision cannot be negative" }
    }
}

data class ConfirmedExtensionCommand(
    val confirmationId: String,
    val kind: OperationKind,
    val candidate: RemoteCandidate,
    val expectedLocal: LocalInstallation?,
    val inventoryRevision: Long,
    val settingsRevision: Long,
) {
    init {
        require(kind == OperationKind.INSTALL || kind == OperationKind.UPDATE) {
            "Extension command must install or update one package"
        }
    }
}

data class ConfirmedUninstallCommand(
    val confirmationId: String,
    val local: LocalInstallation,
    val inventoryRevision: Long,
    val settingsRevision: Long,
)
