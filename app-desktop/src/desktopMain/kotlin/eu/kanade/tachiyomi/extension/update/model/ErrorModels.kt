package eu.kanade.tachiyomi.extension.update.model

enum class TransactionStage {
    PREPARING,
    DOWNLOADING,
    DOWNLOADED,
    VERIFYING,
    VERIFIED,
    BLOCKED,
    CONVERTING,
    CONVERTED,
    VALIDATING,
    VALIDATED,
    BACKING_UP,
    BACKED_UP,
    DETACHING_RUNTIME,
    RETIRING_OLD,
    PROMOTING_JAR,
    PROMOTING_METADATA,
    RELOADING,
    COMMITTED,
    UNINSTALLING,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERING,
    RECOVERY_PENDING,
}

sealed interface ConfirmationErrorKind {
    data object NotFound : ConfirmationErrorKind
    data object Cancelled : ConfirmationErrorKind
    data object AlreadyConsumed : ConfirmationErrorKind
    data object CandidateChanged : ConfirmationErrorKind
    data object InventoryChanged : ConfirmationErrorKind
    data object SettingsChanged : ConfirmationErrorKind
    data object TrustChanged : ConfirmationErrorKind
    data object LocalStateChanged : ConfirmationErrorKind
}

sealed interface ValidationErrorKind {
    data object UnreadableJar : ValidationErrorKind
    data class PackageMismatch(
        val expected: PackageId,
        val actual: PackageId?,
    ) : ValidationErrorKind

    data object NoSources : ValidationErrorKind
    data object Timeout : ValidationErrorKind
    data class ProcessCrashed(val diagnosticId: String) : ValidationErrorKind
    data class LoadFailed(val diagnosticId: String) : ValidationErrorKind
}

sealed interface ExtensionUpdateError {
    data class Repository(
        val url: NormalizedRepositoryUrl,
        val kind: RepositoryErrorKind,
    ) : ExtensionUpdateError

    data class Entry(
        val repository: NormalizedRepositoryUrl,
        val ordinal: Int,
        val reason: String,
    ) : ExtensionUpdateError

    data class Confirmation(val reason: ConfirmationErrorKind) : ExtensionUpdateError
    data class Integrity(val reason: IntegrityBlockReason) : ExtensionUpdateError
    data class Conversion(val diagnosticId: String) : ExtensionUpdateError
    data class Validation(val reason: ValidationErrorKind) : ExtensionUpdateError

    data class FileSystem(
        val stage: TransactionStage,
        val diagnosticId: String,
    ) : ExtensionUpdateError

    data class Reload(val errors: Map<String, List<String>>) : ExtensionUpdateError

    data class Recovery(
        val packageId: PackageId,
        val diagnosticId: String,
    ) : ExtensionUpdateError
}
