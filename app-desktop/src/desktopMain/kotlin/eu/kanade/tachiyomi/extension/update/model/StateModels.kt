package eu.kanade.tachiyomi.extension.update.model

import java.time.Instant

sealed interface CheckState {
    data object Idle : CheckState
    data class Checking(val startedAt: Instant) : CheckState

    data class Complete(
        val outdatedCount: Int,
        val incomplete: Boolean,
        val repositoryFailures: List<RepositoryFailure>,
        val checkedAt: Instant,
    ) : CheckState {
        init {
            require(outdatedCount >= 0) { "Outdated count cannot be negative" }
        }
    }

    data class Failed(val failures: List<RepositoryFailure>) : CheckState
    data object NoTrustedRepositories : CheckState
}

sealed interface PackageOperationState {
    val packageId: PackageId

    data class InProgress(
        override val packageId: PackageId,
        val latestEvent: ExtensionProgressEvent,
    ) : PackageOperationState {
        init {
            require(packageId == latestEvent.packageId) { "Progress event belongs to another package" }
        }
    }

    data class Finished(
        override val packageId: PackageId,
        val result: ExtensionOperationResult,
    ) : PackageOperationState {
        init {
            require(packageId == result.packageId) { "Operation result belongs to another package" }
        }
    }
}

sealed interface BatchExclusionReason {
    data object CandidateChanged : BatchExclusionReason
    data object TrustChanged : BatchExclusionReason
    data object NoLongerOutdated : BatchExclusionReason
    data object LocalStateChanged : BatchExclusionReason
    data object RepositoryUnavailable : BatchExclusionReason
}

sealed interface BatchItemResult {
    val packageId: PackageId

    data class Completed(
        override val packageId: PackageId,
        val result: TransactionResult,
    ) : BatchItemResult {
        init {
            require(packageId == result.packageId) { "Batch result belongs to another package" }
        }
    }

    data class Excluded(
        override val packageId: PackageId,
        val reason: BatchExclusionReason,
    ) : BatchItemResult
}

sealed interface BatchState {
    data class Running(
        val packageIds: List<PackageId>,
        val currentPackage: PackageId?,
        val results: Map<PackageId, BatchItemResult>,
    ) : BatchState

    data class Complete(
        val results: Map<PackageId, BatchItemResult>,
    ) : BatchState
}

sealed interface RecoveryState {
    data object Recovering : RecoveryState
    data object Restored : RecoveryState
    data class Pending(val diagnosticId: String) : RecoveryState
}

data class ExtensionUpdateState(
    val check: CheckState = CheckState.Idle,
    val inventory: List<ExtensionInventoryItem> = emptyList(),
    val operations: Map<PackageId, PackageOperationState> = emptyMap(),
    val confirmation: ConfirmationRequest? = null,
    val batch: BatchState? = null,
    val recovery: Map<PackageId, RecoveryState> = emptyMap(),
    val revision: Long = 0,
) {
    init {
        require(revision >= 0) { "State revision cannot be negative" }
    }
}
