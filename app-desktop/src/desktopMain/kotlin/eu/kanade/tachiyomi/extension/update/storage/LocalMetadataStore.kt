package eu.kanade.tachiyomi.extension.update.storage

import eu.kanade.tachiyomi.extension.update.model.LocalInstallation
import eu.kanade.tachiyomi.extension.update.model.LocalMetadataKind
import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.VerificationStatus
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

const val CURRENT_LOCAL_METADATA_SCHEMA_VERSION = 1

private const val SHA_256_ALGORITHM = "SHA-256"
private const val SHA_256_BYTE_COUNT = 32
private val SHA_256_HEX_PATTERN = Regex("[0-9a-f]{64}")

/** Terminal verification outcomes that may describe an active local JAR. */
@Serializable
enum class PersistedVerificationStatus {
    VERIFIED_BY_HASH,
    VERIFIED_BY_SIGNATURE,
    VERIFIED_BY_HASH_AND_SIGNATURE,
    UNVERIFIED_BY_INDEX,
}

/** Durable sidecar for one active `{Package_ID}.jar`. */
@Serializable
data class LocalExtensionMetadata(
    val schemaVersion: Int = CURRENT_LOCAL_METADATA_SCHEMA_VERSION,
    val packageId: String,
    val version: String,
    val versionCode: Long? = null,
    val repository: String,
    val artifactUrl: String,
    val candidateFingerprint: String,
    val verification: PersistedVerificationStatus,
    val activeJarSha256: String,
    val activeJarSize: Long,
    val installedAtEpochMillis: Long,
    val transactionId: String,
)

enum class LocalMetadataInvalidReason {
    MALFORMED,
    UNSUPPORTED_SCHEMA,
    INCOHERENT,
}

sealed interface LocalMetadataReadResult {
    data object Missing : LocalMetadataReadResult

    data class Valid(val metadata: LocalExtensionMetadata) : LocalMetadataReadResult

    data class Invalid(val reason: LocalMetadataInvalidReason) : LocalMetadataReadResult
}

enum class LegacyJarReason {
    MISSING_SIDECAR,
    CORRUPT_SIDECAR,
    INCOHERENT_SIDECAR,
    UNREADABLE_JAR,
}

/**
 * A non-mutating local scan. Installations are derived only from root JARs; orphaned sidecars are
 * reported separately and can therefore never create a phantom installation.
 */
data class LocalInstallationScan(
    val installations: List<LocalInstallation>,
    val legacyReasons: Map<PackageId, LegacyJarReason>,
    val orphanedSidecars: List<Path>,
)

data class OrphanedMetadataCleanupResult(
    val deleted: List<Path>,
    val retainedForActiveTransactions: List<Path>,
    val retainedUnrecognized: List<Path>,
)

/**
 * Reads, writes, validates, and scans local extension sidecars.
 *
 * Active sidecars are replaced through [TransactionFileStore.writeAtomically]. A sidecar is
 * current only when its package, file name, size, and SHA-256 all describe the installed JAR.
 * Any absent, malformed, unsupported, or incoherent sidecar safely degrades that JAR to legacy
 * metadata; no version is inferred from a file name.
 */
class LocalMetadataStore(
    private val fileStore: TransactionFileStore,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    /** Reads and structurally validates a sidecar without requiring its JAR to exist. */
    fun read(packageId: PackageId): LocalMetadataReadResult {
        val sidecar = fileStore.metadataFile(packageId)
        if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            return LocalMetadataReadResult.Missing
        }
        if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
            return LocalMetadataReadResult.Invalid(LocalMetadataInvalidReason.MALFORMED)
        }

        val metadata = try {
            json.decodeFromString<LocalExtensionMetadata>(
                fileStore.readBytes(sidecar).toString(StandardCharsets.UTF_8),
            )
        } catch (_: Exception) {
            return LocalMetadataReadResult.Invalid(LocalMetadataInvalidReason.MALFORMED)
        }

        val invalidReason = validateDecodedMetadata(metadata, packageId)
        return if (invalidReason == null) {
            LocalMetadataReadResult.Valid(metadata)
        } else {
            LocalMetadataReadResult.Invalid(invalidReason)
        }
    }

    /**
     * Atomically persists a complete sidecar after proving that it describes the current active
     * JAR. Callers coordinating a transaction must hold the package lock while promoting the JAR
     * and invoking this method.
     */
    fun write(metadata: LocalExtensionMetadata) {
        val packageId = packageIdFromMetadata(metadata)
        val jarIdentity = inspectJar(fileStore.activeJar(packageId))
        writeValidated(metadata, packageId, jarIdentity)
    }

    /** Builds and atomically writes a sidecar using digest and size from the active JAR itself. */
    fun writeForActiveJar(
        packageId: PackageId,
        version: VersionDescriptor,
        repository: NormalizedRepositoryUrl,
        artifactUrl: String,
        candidateFingerprint: String,
        verification: VerificationStatus,
        transactionId: String,
        installedAtEpochMillis: Long = System.currentTimeMillis(),
    ): LocalExtensionMetadata {
        val jarIdentity = inspectJar(fileStore.activeJar(packageId))
        val metadata = LocalExtensionMetadata(
            packageId = packageId.value,
            version = version.text,
            versionCode = version.versionCode,
            repository = repository.value,
            artifactUrl = artifactUrl,
            candidateFingerprint = candidateFingerprint,
            verification = verification.toPersisted(),
            activeJarSha256 = jarIdentity.sha256.toLowerHex(),
            activeJarSize = jarIdentity.size,
            installedAtEpochMillis = installedAtEpochMillis,
            transactionId = transactionId,
        )
        writeValidated(metadata, packageId, jarIdentity)
        return metadata
    }

    /** Scans only root `*.jar` files and classifies metadata validity for each installation. */
    fun scan(): LocalInstallationScan {
        val installations = mutableListOf<LocalInstallation>()
        val legacyReasons = linkedMapOf<PackageId, LegacyJarReason>()
        val consumedSidecars = mutableSetOf<Path>()

        listInstalledJars().forEach { jar ->
            val packageId = packageIdFromJar(jar) ?: return@forEach
            consumedSidecars.add(fileStore.metadataFile(packageId))

            val jarIdentity = try {
                inspectJar(jar)
            } catch (_: Exception) {
                legacyReasons[packageId] = LegacyJarReason.UNREADABLE_JAR
                installations += legacyInstallation(packageId, jar, ByteArray(0))
                return@forEach
            }

            when (val metadataResult = read(packageId)) {
                LocalMetadataReadResult.Missing -> {
                    legacyReasons[packageId] = LegacyJarReason.MISSING_SIDECAR
                    installations += legacyInstallation(packageId, jar, jarIdentity.sha256)
                }

                is LocalMetadataReadResult.Invalid -> {
                    legacyReasons[packageId] = when (metadataResult.reason) {
                        LocalMetadataInvalidReason.MALFORMED -> LegacyJarReason.CORRUPT_SIDECAR
                        LocalMetadataInvalidReason.UNSUPPORTED_SCHEMA,
                        LocalMetadataInvalidReason.INCOHERENT,
                        -> LegacyJarReason.INCOHERENT_SIDECAR
                    }
                    installations += legacyInstallation(packageId, jar, jarIdentity.sha256)
                }

                is LocalMetadataReadResult.Valid -> {
                    val metadata = metadataResult.metadata
                    val expectedDigest = metadata.activeJarSha256.decodeSha256OrNull()
                    val coherent = metadata.activeJarSize == jarIdentity.size &&
                        expectedDigest != null &&
                        MessageDigest.isEqual(expectedDigest, jarIdentity.sha256)

                    if (coherent) {
                        installations += LocalInstallation(
                            packageId = packageId,
                            jar = jar,
                            jarSha256 = jarIdentity.sha256,
                            version = VersionDescriptor(metadata.version, metadata.versionCode),
                            origin = NormalizedRepositoryUrl(metadata.repository),
                            verification = metadata.verification.toDomain(),
                            metadataKind = LocalMetadataKind.CURRENT,
                        )
                    } else {
                        legacyReasons[packageId] = LegacyJarReason.INCOHERENT_SIDECAR
                        installations += legacyInstallation(packageId, jar, jarIdentity.sha256)
                    }
                }
            }
        }

        val orphanedSidecars = listMetadataSidecars()
            .filterNot(consumedSidecars::contains)
            .sortedBy { it.fileName.toString() }

        return LocalInstallationScan(
            installations = installations.sortedBy { it.packageId.value },
            legacyReasons = legacyReasons.toSortedMap(compareBy(PackageId::value)),
            orphanedSidecars = orphanedSidecars,
        )
    }

    fun scanInstallations(): List<LocalInstallation> = scan().installations

    /**
     * Removes only valid orphaned sidecars whose transaction directory is absent. Corrupt or
     * unrecognized state is retained because its transaction ownership cannot be proven. The
     * global lock and a final directory check prevent cleanup of a sidecar promoted by an active
     * cooperating transaction.
     */
    fun cleanupOrphanedSidecars(): OrphanedMetadataCleanupResult =
        fileStore.acquireGlobalLock().use {
            val deleted = mutableListOf<Path>()
            val retainedForActiveTransactions = mutableListOf<Path>()
            val retainedUnrecognized = mutableListOf<Path>()

            scan().orphanedSidecars.forEach { sidecar ->
                val packageId = packageIdFromSidecar(sidecar)
                if (packageId == null) {
                    retainedUnrecognized.add(sidecar)
                    return@forEach
                }

                when (val result = read(packageId)) {
                    is LocalMetadataReadResult.Valid -> {
                        val transactionDirectory = fileStore
                            .transactionPaths(result.metadata.transactionId)
                            .directory
                        if (Files.isDirectory(transactionDirectory, LinkOption.NOFOLLOW_LINKS)) {
                            retainedForActiveTransactions.add(sidecar)
                        } else if (fileStore.deleteStateFile(sidecar)) {
                            deleted.add(sidecar)
                        }
                    }

                    LocalMetadataReadResult.Missing -> Unit
                    is LocalMetadataReadResult.Invalid -> retainedUnrecognized.add(sidecar)
                }
            }

            OrphanedMetadataCleanupResult(
                deleted = deleted.sortedBy { it.fileName.toString() },
                retainedForActiveTransactions = retainedForActiveTransactions
                    .sortedBy { it.fileName.toString() },
                retainedUnrecognized = retainedUnrecognized.sortedBy { it.fileName.toString() },
            )
        }

    private fun writeValidated(
        metadata: LocalExtensionMetadata,
        packageId: PackageId,
        jarIdentity: JarIdentity,
    ) {
        val invalidReason = validateDecodedMetadata(metadata, packageId)
        require(invalidReason == null) { "Local extension metadata is invalid: $invalidReason" }
        require(metadata.activeJarSize == jarIdentity.size) {
            "Local extension metadata size does not match the active JAR"
        }
        val expectedDigest = requireNotNull(metadata.activeJarSha256.decodeSha256OrNull()) {
            "Local extension metadata contains an invalid SHA-256 digest"
        }
        require(MessageDigest.isEqual(expectedDigest, jarIdentity.sha256)) {
            "Local extension metadata digest does not match the active JAR"
        }

        val bytes = json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8)
        fileStore.writeAtomically(fileStore.metadataFile(packageId), bytes)
    }

    private fun validateDecodedMetadata(
        metadata: LocalExtensionMetadata,
        expectedPackageId: PackageId,
    ): LocalMetadataInvalidReason? {
        if (metadata.schemaVersion != CURRENT_LOCAL_METADATA_SCHEMA_VERSION) {
            return LocalMetadataInvalidReason.UNSUPPORTED_SCHEMA
        }
        if (metadata.packageId != expectedPackageId.value) {
            return LocalMetadataInvalidReason.INCOHERENT
        }

        return try {
            fileStore.metadataFile(expectedPackageId)
            fileStore.transactionPaths(metadata.transactionId)
            VersionDescriptor(metadata.version, metadata.versionCode)
            NormalizedRepositoryUrl(metadata.repository)
            require(metadata.repository == metadata.repository.trim())
            require(metadata.artifactUrl.isNotBlank())
            require(metadata.candidateFingerprint.isNotBlank())
            require(metadata.activeJarSha256.matches(SHA_256_HEX_PATTERN))
            require(metadata.activeJarSize >= 0)
            require(metadata.installedAtEpochMillis >= 0)
            null
        } catch (_: IllegalArgumentException) {
            LocalMetadataInvalidReason.INCOHERENT
        }
    }

    private fun packageIdFromMetadata(metadata: LocalExtensionMetadata): PackageId {
        val packageId = PackageId(metadata.packageId)
        fileStore.metadataFile(packageId)
        return packageId
    }

    private fun packageIdFromJar(jar: Path): PackageId? {
        val fileName = jar.fileName.toString()
        if (!fileName.endsWith(".jar")) return null
        val value = fileName.removeSuffix(".jar")
        return runCatching {
            val packageId = PackageId(value)
            val expected = fileStore.activeJar(packageId)
            require(expected == jar.toAbsolutePath().normalize())
            packageId
        }.getOrNull()
    }

    private fun packageIdFromSidecar(sidecar: Path): PackageId? {
        val fileName = sidecar.fileName.toString()
        if (!fileName.endsWith(".json")) return null
        val value = fileName.removeSuffix(".json")
        return runCatching {
            val packageId = PackageId(value)
            require(fileStore.metadataFile(packageId) == sidecar.toAbsolutePath().normalize())
            packageId
        }.getOrNull()
    }

    private fun listInstalledJars(): List<Path> =
        Files.newDirectoryStream(fileStore.extensionDirectory) { path ->
            path.fileName.toString().endsWith(".jar") &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        }.use { entries ->
            entries.map { it.toAbsolutePath().normalize() }
                .sortedBy { it.fileName.toString() }
        }

    private fun listMetadataSidecars(): List<Path> =
        Files.newDirectoryStream(fileStore.metadataDirectory) { path ->
            path.fileName.toString().endsWith(".json") &&
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        }.use { entries ->
            entries.map { it.toAbsolutePath().normalize() }
                .sortedBy { it.fileName.toString() }
        }

    private fun inspectJar(jar: Path): JarIdentity {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Active extension JAR is not a regular file")
        }

        val sizeBefore = Files.size(jar)
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        Files.newInputStream(jar, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val sizeAfter = Files.size(jar)
        if (sizeBefore != sizeAfter) {
            throw IOException("Active extension JAR changed while metadata was inspected")
        }
        return JarIdentity(sizeAfter, digest.digest())
    }

    private fun legacyInstallation(
        packageId: PackageId,
        jar: Path,
        sha256: ByteArray,
    ): LocalInstallation = LocalInstallation(
        packageId = packageId,
        jar = jar,
        jarSha256 = sha256,
        version = null,
        origin = null,
        verification = null,
        metadataKind = LocalMetadataKind.LEGACY,
    )

    private data class JarIdentity(
        val size: Long,
        val sha256: ByteArray,
    )
}

private fun PersistedVerificationStatus.toDomain(): VerificationStatus = when (this) {
    PersistedVerificationStatus.VERIFIED_BY_HASH -> VerificationStatus.VerifiedByHash
    PersistedVerificationStatus.VERIFIED_BY_SIGNATURE -> VerificationStatus.VerifiedBySignature
    PersistedVerificationStatus.VERIFIED_BY_HASH_AND_SIGNATURE -> VerificationStatus.VerifiedByHashAndSignature
    PersistedVerificationStatus.UNVERIFIED_BY_INDEX -> VerificationStatus.UnverifiedByIndex
}

private fun VerificationStatus.toPersisted(): PersistedVerificationStatus = when (this) {
    VerificationStatus.VerifiedByHash -> PersistedVerificationStatus.VERIFIED_BY_HASH
    VerificationStatus.VerifiedBySignature -> PersistedVerificationStatus.VERIFIED_BY_SIGNATURE
    VerificationStatus.VerifiedByHashAndSignature -> PersistedVerificationStatus.VERIFIED_BY_HASH_AND_SIGNATURE
    VerificationStatus.UnverifiedByIndex -> PersistedVerificationStatus.UNVERIFIED_BY_INDEX
    is VerificationStatus.BlockedByIntegrity -> {
        throw IllegalArgumentException("Blocked verification cannot describe an active JAR")
    }
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun String.decodeSha256OrNull(): ByteArray? {
    if (!matches(SHA_256_HEX_PATTERN)) return null
    val decoded = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return decoded.takeIf { it.size == SHA_256_BYTE_COUNT }
}
