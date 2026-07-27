package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.model.ArtifactHash
import eu.kanade.tachiyomi.extension.update.model.ArtifactSignature
import eu.kanade.tachiyomi.extension.update.model.HttpCacheValidator
import eu.kanade.tachiyomi.extension.update.model.IntegrityBlockReason
import eu.kanade.tachiyomi.extension.update.model.IntegrityControl
import eu.kanade.tachiyomi.extension.update.model.IntegrityDescriptor
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.RemoteExtensionEntry
import eu.kanade.tachiyomi.extension.update.model.RepositoryEntryIssue
import eu.kanade.tachiyomi.extension.update.model.RepositoryErrorKind
import eu.kanade.tachiyomi.extension.update.model.RepositoryFailure
import eu.kanade.tachiyomi.extension.update.model.RepositoryIndexResult
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import eu.kanade.tachiyomi.extension.update.model.VerificationExpectation
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.util.Base64
import java.util.Locale

/** Nullable wire shape used to stage both legacy and extended repository entries. */
@Serializable
data class RepositoryIndexEntryDto(
    val name: String? = null,
    val pkg: String? = null,
    val apk: String? = null,
    val lang: String? = null,
    val version: String? = null,
    val versionCode: Long? = null,
    val hash: RepositoryHashDto? = null,
    val signature: RepositorySignatureDto? = null,
    val nsfw: Int? = null,
    val sources: List<RepositorySourceDto>? = null,
)

@Serializable
data class RepositoryHashDto(
    val algorithm: String? = null,
    val value: String? = null,
)

@Serializable
data class RepositorySignatureDto(
    val algorithm: String? = null,
    val keyId: String? = null,
    val value: String? = null,
)

@Serializable
data class RepositorySourceDto(
    val name: String? = null,
    val lang: String? = null,
    val id: String? = null,
    val baseUrl: String? = null,
)

/**
 * Decodes one entry at a time so a malformed record cannot invalidate otherwise usable metadata.
 * This component only turns an already-fetched index document into domain metadata; it performs no IO.
 */
class RepositoryIndexParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(
        document: String,
        repository: RepositoryRef,
        indexUrl: URI = URI(repository.normalizedUrl.value),
        cacheValidator: HttpCacheValidator? = null,
    ): RepositoryIndexResult {
        val root = try {
            json.parseToJsonElement(document)
        } catch (_: Exception) {
            return failure(repository, RepositoryErrorKind.InvalidDocument)
        }

        if (root !is JsonArray) {
            return failure(repository, RepositoryErrorKind.RootIsNotAList)
        }

        val entries = mutableListOf<RemoteExtensionEntry>()
        val issues = mutableListOf<RepositoryEntryIssue>()

        root.forEachIndexed { ordinal, element ->
            val objectValue = element as? JsonObject
            if (objectValue == null) {
                issues += issue(repository, ordinal, "entry_not_object")
                return@forEachIndexed
            }

            val dto = objectValue.toNullableDto()
            val missingFields = buildList {
                if (dto.pkg.isNullOrBlank()) add("pkg")
                if (dto.version.isNullOrBlank()) add("version")
                if (dto.apk.isNullOrBlank()) add("apk")
            }
            if (missingFields.isNotEmpty()) {
                issues += issue(
                    repository,
                    ordinal,
                    "missing_or_invalid:${missingFields.joinToString(",")}",
                )
                return@forEachIndexed
            }

            val artifactReference = requireNotNull(dto.apk).trim()
            val artifactResolution = resolveArtifactReference(artifactReference, indexUrl)
            if (artifactResolution is ArtifactReferenceResolution.Invalid) {
                issues += issue(repository, ordinal, "unsafe_apk_reference:${artifactResolution.reason}")
                return@forEachIndexed
            }

            val rawVersionCode = objectValue["versionCode"]
            val validVersionCode = dto.versionCode?.takeIf { it >= 0 }
            if (rawVersionCode != null && rawVersionCode !is JsonNull && validVersionCode == null) {
                issues += issue(repository, ordinal, "invalid_version_code")
            }

            val packageId = PackageId(requireNotNull(dto.pkg).trim())
            val parsedIntegrity = parseIntegrity(dto.hash, dto.signature)
            val artifactUrl = (artifactResolution as ArtifactReferenceResolution.Valid).uri

            entries += RemoteExtensionEntry(
                name = dto.name?.trim()?.takeIf(String::isNotEmpty) ?: packageId.value,
                packageId = packageId,
                artifactReference = artifactReference,
                artifactUrl = artifactUrl,
                language = dto.lang?.trim().orEmpty(),
                version = VersionDescriptor(
                    text = requireNotNull(dto.version).trim(),
                    versionCode = validVersionCode,
                ),
                integrity = parsedIntegrity.descriptor,
                verificationExpectation = parsedIntegrity.expectation,
                repository = repository,
                indexOrdinal = ordinal,
            )
        }

        return RepositoryIndexResult.Success(
            repository = repository,
            entries = entries,
            issues = issues,
            cacheValidator = cacheValidator,
        )
    }

    private fun failure(
        repository: RepositoryRef,
        kind: RepositoryErrorKind,
    ): RepositoryIndexResult.Failure = RepositoryIndexResult.Failure(
        repository = repository,
        failure = RepositoryFailure(
            url = repository.normalizedUrl,
            kind = kind,
        ),
    )

    private fun issue(
        repository: RepositoryRef,
        ordinal: Int,
        reason: String,
    ) = RepositoryEntryIssue(
        repository = repository.normalizedUrl,
        ordinal = ordinal,
        reason = reason,
    )
}

private fun JsonObject.toNullableDto(): RepositoryIndexEntryDto = RepositoryIndexEntryDto(
    name = get("name").stringOrNull(),
    pkg = get("pkg").stringOrNull(),
    apk = get("apk").stringOrNull(),
    lang = get("lang").stringOrNull(),
    version = get("version").stringOrNull(),
    versionCode = get("versionCode").numberLongOrNull(),
    hash = get("hash").hashDtoOrNull(),
    signature = get("signature").signatureDtoOrNull(),
    nsfw = get("nsfw").numberIntOrNull(),
    sources = (get("sources") as? JsonArray)?.mapNotNull { source ->
        (source as? JsonObject)?.let { sourceObject ->
            RepositorySourceDto(
                name = sourceObject["name"].stringOrNull(),
                lang = sourceObject["lang"].stringOrNull(),
                id = sourceObject["id"].stringOrNull(),
                baseUrl = sourceObject["baseUrl"].stringOrNull(),
            )
        }
    },
)

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonElement?.numberLongOrNull(): Long? =
    (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

private fun JsonElement?.numberIntOrNull(): Int? =
    (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun JsonElement?.hashDtoOrNull(): RepositoryHashDto? = when (this) {
    null, JsonNull -> null
    is JsonObject -> RepositoryHashDto(
        algorithm = get("algorithm").stringOrNull(),
        value = get("value").stringOrNull(),
    )
    else -> RepositoryHashDto()
}

private fun JsonElement?.signatureDtoOrNull(): RepositorySignatureDto? = when (this) {
    null, JsonNull -> null
    is JsonObject -> RepositorySignatureDto(
        algorithm = get("algorithm").stringOrNull(),
        keyId = get("keyId").stringOrNull(),
        value = get("value").stringOrNull(),
    )
    else -> RepositorySignatureDto()
}

private data class ParsedIntegrity(
    val descriptor: IntegrityDescriptor?,
    val expectation: VerificationExpectation,
)

private sealed interface ParsedControl<out T> {
    data object Absent : ParsedControl<Nothing>
    data class Valid<T>(val value: T) : ParsedControl<T>
    data class Blocked(val reason: IntegrityBlockReason) : ParsedControl<Nothing>
}

private fun parseIntegrity(
    hashDto: RepositoryHashDto?,
    signatureDto: RepositorySignatureDto?,
): ParsedIntegrity {
    val hash = parseHash(hashDto)
    val signature = parseSignature(signatureDto)
    val descriptor = IntegrityDescriptor(
        hash = (hash as? ParsedControl.Valid)?.value,
        signature = (signature as? ParsedControl.Valid)?.value,
    ).takeIf { it.hash != null || it.signature != null }

    val blocked = (hash as? ParsedControl.Blocked)?.reason
        ?: (signature as? ParsedControl.Blocked)?.reason
    if (blocked != null) {
        return ParsedIntegrity(
            descriptor = descriptor,
            expectation = VerificationExpectation.Blocked(blocked),
        )
    }

    val requiresHash = hash is ParsedControl.Valid
    val requiresSignature = signature is ParsedControl.Valid
    return if (requiresHash || requiresSignature) {
        ParsedIntegrity(
            descriptor = descriptor,
            expectation = VerificationExpectation.Required(
                hash = requiresHash,
                signature = requiresSignature,
            ),
        )
    } else {
        ParsedIntegrity(
            descriptor = null,
            expectation = VerificationExpectation.NotPublished,
        )
    }
}

private fun parseHash(dto: RepositoryHashDto?): ParsedControl<ArtifactHash> {
    if (dto == null) return ParsedControl.Absent

    val algorithm = dto.algorithm?.trim()?.takeIf(String::isNotEmpty)
        ?: return ParsedControl.Blocked(
            IntegrityBlockReason.MalformedDescriptor(IntegrityControl.HASH),
        )
    val canonicalAlgorithm = when (algorithm.uppercase(Locale.ROOT)) {
        "SHA-256", "SHA256" -> "SHA-256"
        else -> return ParsedControl.Blocked(
            IntegrityBlockReason.UnsupportedAlgorithm(IntegrityControl.HASH, algorithm),
        )
    }
    val bytes = dto.value?.trim()?.decodeSha256Hex()
        ?: return ParsedControl.Blocked(
            IntegrityBlockReason.MalformedDescriptor(IntegrityControl.HASH),
        )

    return ParsedControl.Valid(ArtifactHash(canonicalAlgorithm, bytes))
}

private fun parseSignature(dto: RepositorySignatureDto?): ParsedControl<ArtifactSignature> {
    if (dto == null) return ParsedControl.Absent

    val algorithm = dto.algorithm?.trim()?.takeIf(String::isNotEmpty)
        ?: return ParsedControl.Blocked(
            IntegrityBlockReason.MalformedDescriptor(IntegrityControl.SIGNATURE),
        )
    val canonicalAlgorithm = when (algorithm.uppercase(Locale.ROOT)) {
        "ED25519" -> "Ed25519"
        else -> return ParsedControl.Blocked(
            IntegrityBlockReason.UnsupportedAlgorithm(IntegrityControl.SIGNATURE, algorithm),
        )
    }
    val keyId = dto.keyId?.trim()?.takeIf(String::isNotEmpty)
        ?: return ParsedControl.Blocked(
            IntegrityBlockReason.MalformedDescriptor(IntegrityControl.SIGNATURE),
        )
    val bytes = dto.value?.trim()?.let { encoded ->
        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }?.takeIf { it.size == ED25519_SIGNATURE_BYTES }
        ?: return ParsedControl.Blocked(
            IntegrityBlockReason.MalformedDescriptor(IntegrityControl.SIGNATURE),
        )

    return ParsedControl.Valid(
        ArtifactSignature(
            algorithm = canonicalAlgorithm,
            keyId = keyId,
            value = bytes,
        ),
    )
}

private fun String.decodeSha256Hex(): ByteArray? {
    if (length != SHA_256_HEX_LENGTH || !all(Char::isHexDigit)) return null
    return ByteArray(SHA_256_BYTES) { index ->
        val offset = index * 2
        ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal sealed interface ArtifactReferenceResolution {
    data class Valid(val uri: URI) : ArtifactReferenceResolution
    data class Invalid(val reason: String) : ArtifactReferenceResolution
}

/** Resolves an APK reference without allowing scheme changes, foreign origins, or traversal. */
internal fun resolveArtifactReference(
    reference: String,
    indexUrl: URI,
): ArtifactReferenceResolution {
    val trimmed = reference.trim()
    if (trimmed.isEmpty()) return ArtifactReferenceResolution.Invalid("empty")
    if (trimmed.hasControlCharactersOrEscapes()) {
        return ArtifactReferenceResolution.Invalid("control_character")
    }

    val base = indexUrl.takeIf(URI::isSafeHttpIndex)
        ?: return ArtifactReferenceResolution.Invalid("invalid_index_origin")
    val parsed = runCatching { URI(trimmed) }.getOrNull()
        ?: return ArtifactReferenceResolution.Invalid("malformed_uri")

    if (parsed.isOpaque || parsed.rawFragment != null || parsed.rawUserInfo != null) {
        return ArtifactReferenceResolution.Invalid("unsupported_uri_component")
    }
    if (!parsed.isAbsolute && parsed.rawAuthority != null) {
        return ArtifactReferenceResolution.Invalid("network_path_reference")
    }
    if (!parsed.isAbsolute && parsed.rawPath.orEmpty().startsWith('/')) {
        return ArtifactReferenceResolution.Invalid("absolute_path_reference")
    }
    if (!parsed.hasSafeArtifactPath()) {
        return ArtifactReferenceResolution.Invalid("unsafe_path")
    }

    val relativeOrAbsolute = if (
        !parsed.isAbsolute &&
        parsed.rawAuthority == null &&
        !parsed.rawPath.orEmpty().contains('/')
    ) {
        URI("apk/").resolve(parsed)
    } else {
        parsed
    }
    val resolved = if (relativeOrAbsolute.isAbsolute) {
        relativeOrAbsolute
    } else {
        base.resolve(".").resolve(relativeOrAbsolute)
    }.normalize()

    if (!resolved.isSafeHttpIndex() || !resolved.hasSafeArtifactPath()) {
        return ArtifactReferenceResolution.Invalid("unsafe_resolved_uri")
    }
    if (!isSameRepositoryOrigin(base, resolved)) {
        return ArtifactReferenceResolution.Invalid("foreign_origin")
    }

    return ArtifactReferenceResolution.Valid(resolved)
}

internal fun isSameRepositoryOrigin(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second)

private fun URI.isSafeHttpIndex(): Boolean =
    isAbsolute &&
        !isOpaque &&
        userInfo == null &&
        host != null &&
        (scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true))

private fun URI.hasSafeArtifactPath(): Boolean {
    val raw = rawPath.orEmpty()
    val decoded = path.orEmpty()
    if (raw.isBlank() || raw.endsWith('/') || decoded.isBlank() || decoded.endsWith('/')) return false
    if ('\\' in raw || '\\' in decoded) return false
    if (ENCODED_SLASH_OR_BACKSLASH.containsMatchIn(raw)) return false
    if (pathContainsTraversal(raw) || pathContainsTraversal(decoded)) return false
    if (decoded.any { it.code < 0x20 || it.code == 0x7f }) return false
    return true
}

private fun pathContainsTraversal(path: String): Boolean = path.split('/').any { segment ->
    segment.replace(ENCODED_DOT, ".") == ".."
}

private fun String.hasControlCharactersOrEscapes(): Boolean =
    any { it.code < 0x20 || it.code == 0x7f } || ENCODED_CONTROL.containsMatchIn(this)

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private const val SHA_256_BYTES = 32
private const val SHA_256_HEX_LENGTH = SHA_256_BYTES * 2
private const val ED25519_SIGNATURE_BYTES = 64
private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)
private val ENCODED_SLASH_OR_BACKSLASH = Regex("%(2f|5c)", RegexOption.IGNORE_CASE)
private val ENCODED_CONTROL = Regex("%([01][0-9a-f]|7f)", RegexOption.IGNORE_CASE)
