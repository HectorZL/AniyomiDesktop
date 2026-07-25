package eu.kanade.tachiyomi.extension.update.model

enum class IntegrityControl {
    HASH,
    SIGNATURE,
}

data class ArtifactHash(
    val algorithm: String,
    val value: ByteArray,
) {
    init {
        require(algorithm.isNotBlank()) { "Hash algorithm cannot be blank" }
        require(value.isNotEmpty()) { "Hash value cannot be empty" }
    }
}

data class ArtifactSignature(
    val algorithm: String,
    val keyId: String,
    val value: ByteArray,
) {
    init {
        require(algorithm.isNotBlank()) { "Signature algorithm cannot be blank" }
        require(keyId.isNotBlank()) { "Signature key id cannot be blank" }
        require(value.isNotEmpty()) { "Signature value cannot be empty" }
    }
}

data class IntegrityDescriptor(
    val hash: ArtifactHash?,
    val signature: ArtifactSignature?,
)

sealed interface IntegrityBlockReason {
    data class MalformedDescriptor(val control: IntegrityControl? = null) : IntegrityBlockReason
    data class UnsupportedAlgorithm(
        val control: IntegrityControl,
        val algorithm: String,
    ) : IntegrityBlockReason

    data class MissingTrustedKey(val keyId: String) : IntegrityBlockReason
    data class UntrustedKey(val keyId: String) : IntegrityBlockReason
    data object HashMismatch : IntegrityBlockReason
    data object SignatureMismatch : IntegrityBlockReason
}

sealed interface VerificationExpectation {
    data object NotPublished : VerificationExpectation

    data class Required(
        val hash: Boolean,
        val signature: Boolean,
    ) : VerificationExpectation {
        init {
            require(hash || signature) { "At least one integrity control must be required" }
        }
    }

    data class Blocked(val reason: IntegrityBlockReason) : VerificationExpectation
}

sealed interface VerificationStatus {
    data object VerifiedByHash : VerificationStatus
    data object VerifiedBySignature : VerificationStatus
    data object VerifiedByHashAndSignature : VerificationStatus
    data object UnverifiedByIndex : VerificationStatus
    data class BlockedByIntegrity(val reason: IntegrityBlockReason) : VerificationStatus
}
