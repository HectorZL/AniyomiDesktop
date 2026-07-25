package eu.kanade.tachiyomi.extension.update.model

import java.net.URI

data class RemoteExtensionEntry(
    val name: String,
    val packageId: PackageId,
    val artifactReference: String,
    val artifactUrl: URI,
    val language: String,
    val version: VersionDescriptor,
    val integrity: IntegrityDescriptor?,
    val verificationExpectation: VerificationExpectation,
    val repository: RepositoryRef,
    val indexOrdinal: Int,
)

data class CandidateFingerprint(
    val packageId: PackageId,
    val versionText: String,
    val versionCode: Long?,
    val repository: NormalizedRepositoryUrl,
    val artifactUrl: String,
    val integrityCanonical: String,
)

data class RemoteCandidate(
    val entry: RemoteExtensionEntry,
    val fingerprint: CandidateFingerprint,
)

data class HttpCacheValidator(
    val etag: String? = null,
    val lastModified: String? = null,
)

data class RepositoryEntryIssue(
    val repository: NormalizedRepositoryUrl,
    val ordinal: Int,
    val reason: String,
)

sealed interface RepositoryErrorKind {
    data object Network : RepositoryErrorKind
    data object Timeout : RepositoryErrorKind
    data class Http(val statusCode: Int) : RepositoryErrorKind
    data object EmptyBody : RepositoryErrorKind
    data object InvalidDocument : RepositoryErrorKind
    data object RootIsNotAList : RepositoryErrorKind
    data object UnsafeRedirect : RepositoryErrorKind
    data class Unexpected(val diagnosticId: String) : RepositoryErrorKind
}

data class RepositoryFailure(
    val url: NormalizedRepositoryUrl,
    val kind: RepositoryErrorKind,
    val diagnosticId: String? = null,
)

sealed interface RepositoryIndexResult {
    val repository: RepositoryRef

    data class Success(
        override val repository: RepositoryRef,
        val entries: List<RemoteExtensionEntry>,
        val issues: List<RepositoryEntryIssue> = emptyList(),
        val cacheValidator: HttpCacheValidator? = null,
    ) : RepositoryIndexResult

    data class NotModified(
        override val repository: RepositoryRef,
        val cacheValidator: HttpCacheValidator,
    ) : RepositoryIndexResult

    data class Failure(
        override val repository: RepositoryRef,
        val failure: RepositoryFailure,
    ) : RepositoryIndexResult
}
