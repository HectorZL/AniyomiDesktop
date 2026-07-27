package eu.kanade.tachiyomi.extension.update.storage

import eu.kanade.tachiyomi.extension.update.model.PackageId
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

const val EXTENSION_STATE_DIRECTORY_NAME = ".aniyomi-extension-state"

private const val METADATA_DIRECTORY_NAME = "metadata"
private const val TRANSACTIONS_DIRECTORY_NAME = "transactions"
private const val LOCKS_DIRECTORY_NAME = "locks"
private const val PENDING_REMOVALS_FILE_NAME = "pending-removals.json"
private const val GLOBAL_LOCK_FILE_NAME = "global.lock"
private const val MAX_PACKAGE_ID_LENGTH = 180
private const val MAX_TRANSACTION_ID_LENGTH = 128
private const val MAX_MANAGED_FILE_NAME_LENGTH = 240
private const val COPY_BUFFER_SIZE = 64 * 1024

private val WINDOWS_INVALID_FILE_NAME_CHARACTERS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
private val WINDOWS_RESERVED_FILE_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { index ->
        add("COM$index")
        add("LPT$index")
    }
}
private val PACKAGE_SEGMENT_PATTERN = Regex("[A-Za-z0-9_]+")
private val TRANSACTION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

/** Paths owned by the extension update subsystem. */
data class ExtensionStateLayout(
    val extensionDirectory: Path,
    val stateDirectory: Path,
    val metadataDirectory: Path,
    val transactionsDirectory: Path,
    val locksDirectory: Path,
    val pendingRemovalsFile: Path,
    val globalLockFile: Path,
)

/**
 * Every mutable transaction artifact lives below [directory], on the extension directory's
 * filesystem. In particular, backup and retired JARs can never be discovered by the root JAR
 * scanner.
 */
data class TransactionPaths(
    val transactionId: String,
    val directory: Path,
    val journal: Path,
    val artifactPart: Path,
    val artifact: Path,
    val candidateJar: Path,
    val candidateMetadata: Path,
    val backupJar: Path,
    val backupMetadata: Path,
    val retiredJar: Path,
)

enum class TransactionFileOperationType {
    CREATE_DIRECTORY,
    WRITE,
    COPY,
    FORCE_FILE,
    FORCE_DIRECTORY,
    MOVE,
    DELETE,
    LOCK,
}

data class TransactionFileOperation(
    val type: TransactionFileOperationType,
    val source: Path? = null,
    val target: Path? = null,
)

/** Test seam used by the transaction fault-injection matrix. */
interface TransactionFileStoreFaultInjector {
    fun before(operation: TransactionFileOperation) = Unit

    fun after(operation: TransactionFileOperation) = Unit

    companion object {
        val NONE: TransactionFileStoreFaultInjector = object : TransactionFileStoreFaultInjector {}
    }
}

enum class MoveKind {
    ATOMIC,
    RECOVERABLE_RENAME,
}

data class DurableMoveResult(
    val source: Path,
    val target: Path,
    val kind: MoveKind,
)

/**
 * An in-process and inter-process lock. Closing is idempotent and always releases the local mutex,
 * even if releasing the operating-system file lock reports an error.
 */
class TransactionFileLock internal constructor(
    val path: Path,
    private val localLock: ReentrantLock,
    private val channel: FileChannel,
    private val fileLock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        try {
            fileLock.release()
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            channel.close()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        } finally {
            localLock.unlock()
        }
        failure?.let { throw it }
    }
}

/**
 * Secure filesystem primitives for extension transactions.
 *
 * All returned paths are generated locally from validated package/transaction identifiers. Public
 * operations reject traversal, paths outside [extensionDirectory], symbolic links, and names that
 * are unsafe on Windows even when running on another platform.
 */
class TransactionFileStore(
    extensionDirectory: Path,
    private val faultInjector: TransactionFileStoreFaultInjector = TransactionFileStoreFaultInjector.NONE,
) {
    val extensionDirectory: Path = prepareExtensionDirectory(extensionDirectory)
    val layout: ExtensionStateLayout = initializeLayout()

    val stateDirectory: Path
        get() = layout.stateDirectory

    val metadataDirectory: Path
        get() = layout.metadataDirectory

    val transactionsDirectory: Path
        get() = layout.transactionsDirectory

    val locksDirectory: Path
        get() = layout.locksDirectory

    fun activeJar(packageId: PackageId): Path {
        val safePackageId = validatePackageId(packageId)
        return validateManagedPath(extensionDirectory.resolve("$safePackageId.jar"))
    }

    fun metadataFile(packageId: PackageId): Path {
        val safePackageId = validatePackageId(packageId)
        return validateManagedPath(layout.metadataDirectory.resolve("$safePackageId.json"))
    }

    fun packageLockFile(packageId: PackageId): Path {
        val safePackageId = validatePackageId(packageId)
        return validateManagedPath(layout.locksDirectory.resolve("$safePackageId.lock"))
    }

    fun transactionPaths(transactionId: String): TransactionPaths {
        val safeTransactionId = validateTransactionId(transactionId)
        val directory = validateManagedPath(layout.transactionsDirectory.resolve(safeTransactionId))
        return TransactionPaths(
            transactionId = safeTransactionId,
            directory = directory,
            journal = validateManagedPath(directory.resolve("journal.json")),
            artifactPart = validateManagedPath(directory.resolve("artifact.apk.part")),
            artifact = validateManagedPath(directory.resolve("artifact.apk")),
            candidateJar = validateManagedPath(directory.resolve("candidate.jar")),
            candidateMetadata = validateManagedPath(directory.resolve("candidate-metadata.json")),
            backupJar = validateManagedPath(directory.resolve("backup.jar")),
            backupMetadata = validateManagedPath(directory.resolve("backup-metadata.json")),
            retiredJar = validateManagedPath(directory.resolve("retired.jar")),
        )
    }

    fun createTransaction(): TransactionPaths = createTransaction(UUID.randomUUID().toString())

    fun createTransaction(transactionId: String): TransactionPaths {
        val paths = transactionPaths(transactionId)
        createManagedDirectory(paths.directory, mustBeNew = true)
        requireSameFileStore(extensionDirectory, paths.directory)
        return paths
    }

    fun listTransactions(): List<TransactionPaths> {
        ensureDirectory(layout.transactionsDirectory)
        return Files.newDirectoryStream(layout.transactionsDirectory).use { entries ->
            entries.map { entry ->
                val secureEntry = validateManagedPath(entry)
                ensureDirectory(secureEntry)
                transactionPaths(secureEntry.fileName.toString())
            }.sortedBy { it.transactionId }
        }
    }

    fun readBytes(path: Path): ByteArray {
        val source = requireRegularFile(path)
        return Files.readAllBytes(source)
    }

    /**
     * Writes a complete sibling temporary, forces it, then atomically replaces [target]. If the
     * filesystem cannot guarantee atomic replacement, the operation fails without touching the
     * existing target.
     */
    fun writeAtomically(target: Path, bytes: ByteArray) {
        val secureTarget = prepareFileTarget(target, mayExist = true)
        val temporary = uniqueSiblingTemporary(secureTarget, "write")
        var promoted = false
        try {
            writeNewFile(temporary, bytes)
            atomicMove(temporary, secureTarget, replaceExisting = true)
            promoted = true
            forceDirectory(secureTarget.parent)
        } finally {
            if (!promoted) {
                deleteInternalTemporary(temporary)
            }
        }
    }

    /**
     * Copies [source] to a complete, forced temporary and only then promotes it to [target]. This
     * is suitable for verified backups: a failed copy never truncates either endpoint.
     */
    fun copyDurably(
        source: Path,
        target: Path,
        replaceExisting: Boolean = false,
    ) {
        val secureSource = requireRegularFile(source)
        val secureTarget = prepareFileTarget(target, mayExist = replaceExisting)
        requireSameFileStore(secureSource, secureTarget.parent)
        val temporary = uniqueSiblingTemporary(secureTarget, "copy")
        var promoted = false
        try {
            val operation = TransactionFileOperation(
                type = TransactionFileOperationType.COPY,
                source = secureSource,
                target = temporary,
            )
            faultInjector.before(operation)
            copyToNewFile(secureSource, temporary)
            faultInjector.after(operation)

            val sourceSize = Files.size(secureSource)
            val copiedSize = Files.size(temporary)
            if (sourceSize != copiedSize) {
                throw IOException("Durable copy size mismatch")
            }

            if (replaceExisting) {
                atomicMove(temporary, secureTarget, replaceExisting = true)
            } else {
                moveRecoverably(temporary, secureTarget)
            }
            promoted = true
            forceDirectory(secureTarget.parent)
        } finally {
            if (!promoted) {
                deleteInternalTemporary(temporary)
            }
        }
    }

    /**
     * Moves a complete file without copying it over the destination. An atomic move is preferred;
     * when unavailable, a same-filesystem rename to an absent destination is recoverable from the
     * transaction journal because either the source or target complete file remains.
     */
    fun moveRecoverably(source: Path, target: Path): DurableMoveResult {
        val secureSource = requireRegularFile(source)
        val secureTarget = prepareFileTarget(target, mayExist = false)
        requireSameFileStore(secureSource, secureTarget.parent)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.MOVE,
            source = secureSource,
            target = secureTarget,
        )

        faultInjector.before(operation)
        val moveKind = try {
            Files.move(secureSource, secureTarget, StandardCopyOption.ATOMIC_MOVE)
            MoveKind.ATOMIC
        } catch (_: AtomicMoveNotSupportedException) {
            if (Files.exists(secureTarget, LinkOption.NOFOLLOW_LINKS)) {
                throw FileAlreadyExistsException(secureTarget.toString())
            }
            Files.move(secureSource, secureTarget)
            MoveKind.RECOVERABLE_RENAME
        }
        faultInjector.after(operation)

        requireRegularFile(secureTarget)
        forceFile(secureTarget)
        forceDirectory(secureTarget.parent)
        if (secureSource.parent != secureTarget.parent) {
            forceDirectory(secureSource.parent)
        }
        return DurableMoveResult(secureSource, secureTarget, moveKind)
    }

    /** Forces a complete file written by an external producer such as the artifact downloader. */
    fun forceFile(path: Path) {
        val securePath = requireRegularFile(path)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.FORCE_FILE,
            target = securePath,
        )
        faultInjector.before(operation)
        FileChannel.open(
            securePath,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.force(true)
        }
        faultInjector.after(operation)
    }

    /**
     * Directory forcing is best effort because Windows and some providers do not allow directory
     * channels. Injected failures still propagate, which keeps fault-injection deterministic.
     */
    fun forceDirectory(path: Path) {
        val secureDirectory = validateManagedPath(path, allowExtensionRoot = true)
        ensureDirectory(secureDirectory)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.FORCE_DIRECTORY,
            target = secureDirectory,
        )
        faultInjector.before(operation)
        val forced = try {
            FileChannel.open(secureDirectory, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (forced) {
            faultInjector.after(operation)
        }
    }

    /** Deletes only a non-directory file below the hidden state directory. */
    fun deleteStateFile(path: Path): Boolean {
        val securePath = validateStatePath(path)
        if (!Files.exists(securePath, LinkOption.NOFOLLOW_LINKS)) return false
        rejectSymbolicLink(securePath)
        if (Files.isDirectory(securePath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("State file path is a directory")
        }
        return deleteFile(securePath)
    }

    /** Deletes exactly the active JAR derived from [packageId], never an arbitrary root file. */
    fun deleteActiveJar(packageId: PackageId): Boolean {
        val jar = activeJar(packageId)
        if (!Files.exists(jar, LinkOption.NOFOLLOW_LINKS)) return false
        requireRegularFile(jar)
        return deleteFile(jar)
    }

    /** Recursively removes one validated transaction directory without following links. */
    fun deleteTransaction(transactionId: String): Boolean {
        val transaction = transactionPaths(transactionId)
        val directory = transaction.directory
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false
        ensureDirectTransactionDirectory(directory)

        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.DELETE,
            target = directory,
        )
        faultInjector.before(operation)
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validateManagedPath(dir)
                    if (attrs.isSymbolicLink) {
                        throw IOException("Symbolic links are not allowed in transaction state")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validateManagedPath(file)
                    if (attrs.isSymbolicLink) {
                        throw IOException("Symbolic links are not allowed in transaction state")
                    }
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    exc?.let { throw it }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        faultInjector.after(operation)
        forceDirectory(layout.transactionsDirectory)
        return true
    }

    fun acquireGlobalLock(): TransactionFileLock =
        acquireLock(layout.globalLockFile, wait = true)
            ?: error("Blocking global lock acquisition unexpectedly failed")

    fun tryAcquireGlobalLock(): TransactionFileLock? = acquireLock(layout.globalLockFile, wait = false)

    fun acquirePackageLock(packageId: PackageId): TransactionFileLock =
        acquireLock(packageLockFile(packageId), wait = true)
            ?: error("Blocking package lock acquisition unexpectedly failed")

    fun tryAcquirePackageLock(packageId: PackageId): TransactionFileLock? =
        acquireLock(packageLockFile(packageId), wait = false)

    private fun initializeLayout(): ExtensionStateLayout {
        val stateDirectory = extensionDirectory.resolve(EXTENSION_STATE_DIRECTORY_NAME)
        val metadataDirectory = stateDirectory.resolve(METADATA_DIRECTORY_NAME)
        val transactionsDirectory = stateDirectory.resolve(TRANSACTIONS_DIRECTORY_NAME)
        val locksDirectory = stateDirectory.resolve(LOCKS_DIRECTORY_NAME)

        createManagedDirectory(stateDirectory)
        createManagedDirectory(metadataDirectory)
        createManagedDirectory(transactionsDirectory)
        createManagedDirectory(locksDirectory)
        requireSameFileStore(extensionDirectory, stateDirectory)

        return ExtensionStateLayout(
            extensionDirectory = extensionDirectory,
            stateDirectory = validateManagedPath(stateDirectory),
            metadataDirectory = validateManagedPath(metadataDirectory),
            transactionsDirectory = validateManagedPath(transactionsDirectory),
            locksDirectory = validateManagedPath(locksDirectory),
            pendingRemovalsFile = validateManagedPath(stateDirectory.resolve(PENDING_REMOVALS_FILE_NAME)),
            globalLockFile = validateManagedPath(locksDirectory.resolve(GLOBAL_LOCK_FILE_NAME)),
        )
    }

    private fun createManagedDirectory(path: Path, mustBeNew: Boolean = false) {
        val securePath = validateManagedPath(path)
        if (Files.exists(securePath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymbolicLink(securePath)
            if (mustBeNew) throw FileAlreadyExistsException(securePath.toString())
            ensureDirectory(securePath)
            return
        }

        val parent = securePath.parent ?: throw IOException("Managed directory must have a parent")
        ensureDirectory(parent)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.CREATE_DIRECTORY,
            target = securePath,
        )
        faultInjector.before(operation)
        Files.createDirectory(securePath)
        restrictToCurrentUser(securePath)
        faultInjector.after(operation)
        forceDirectory(parent)
    }

    private fun writeNewFile(target: Path, bytes: ByteArray) {
        val secureTarget = prepareFileTarget(target, mayExist = false)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.WRITE,
            target = secureTarget,
        )
        faultInjector.before(operation)
        FileChannel.open(
            secureTarget,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            restrictToCurrentUser(secureTarget)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            faultInjector.after(operation)

            val forceOperation = TransactionFileOperation(
                type = TransactionFileOperationType.FORCE_FILE,
                target = secureTarget,
            )
            faultInjector.before(forceOperation)
            channel.force(true)
            faultInjector.after(forceOperation)
        }
    }

    private fun copyToNewFile(source: Path, target: Path) {
        FileChannel.open(
            source,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { input ->
            FileChannel.open(
                target,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                restrictToCurrentUser(target)
                val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        output.write(buffer)
                    }
                    buffer.clear()
                }

                val forceOperation = TransactionFileOperation(
                    type = TransactionFileOperationType.FORCE_FILE,
                    target = target,
                )
                faultInjector.before(forceOperation)
                output.force(true)
                faultInjector.after(forceOperation)
            }
        }
    }

    private fun atomicMove(source: Path, target: Path, replaceExisting: Boolean) {
        val secureSource = requireRegularFile(source)
        val secureTarget = prepareFileTarget(target, mayExist = replaceExisting)
        requireSameFileStore(secureSource, secureTarget.parent)
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.MOVE,
            source = secureSource,
            target = secureTarget,
        )
        faultInjector.before(operation)
        try {
            if (replaceExisting) {
                Files.move(
                    secureSource,
                    secureTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.move(secureSource, secureTarget, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("Atomic replacement is not supported for managed state", failure)
        }
        faultInjector.after(operation)
    }

    private fun acquireLock(path: Path, wait: Boolean): TransactionFileLock? {
        val securePath = prepareFileTarget(path, mayExist = true)
        val localLock = LOCAL_LOCKS.computeIfAbsent(securePath) { ReentrantLock() }
        if (localLock.isHeldByCurrentThread) {
            if (!wait) return null
            throw IllegalStateException("Transaction file locks are not reentrant")
        }

        val localAcquired = if (wait) {
            localLock.lockInterruptibly()
            true
        } else {
            localLock.tryLock()
        }
        if (!localAcquired) return null

        var channel: FileChannel? = null
        try {
            val operation = TransactionFileOperation(
                type = TransactionFileOperationType.LOCK,
                target = securePath,
            )
            faultInjector.before(operation)
            channel = FileChannel.open(
                securePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            )
            rejectSymbolicLink(securePath)
            val fileLock = try {
                if (wait) channel.lock() else channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (fileLock == null) {
                channel.close()
                localLock.unlock()
                return null
            }
            restrictToCurrentUser(securePath)
            faultInjector.after(operation)
            return TransactionFileLock(securePath, localLock, channel, fileLock)
        } catch (failure: Throwable) {
            runCatching { channel?.close() }
            localLock.unlock()
            throw failure
        }
    }

    private fun prepareFileTarget(path: Path, mayExist: Boolean): Path {
        val securePath = validateManagedPath(path)
        val parent = securePath.parent ?: throw IOException("Managed file must have a parent")
        ensureDirectory(parent)
        if (Files.exists(securePath, LinkOption.NOFOLLOW_LINKS)) {
            rejectSymbolicLink(securePath)
            if (!mayExist) throw FileAlreadyExistsException(securePath.toString())
            if (!Files.isRegularFile(securePath, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Managed file target is not a regular file")
            }
        }
        return securePath
    }

    private fun requireRegularFile(path: Path): Path {
        val securePath = validateManagedPath(path)
        rejectSymbolicLink(securePath)
        if (!Files.isRegularFile(securePath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Managed path is not a regular file")
        }
        return securePath
    }

    private fun ensureDirectory(path: Path) {
        val securePath = validateManagedPath(path, allowExtensionRoot = true)
        rejectSymbolicLink(securePath)
        if (!Files.isDirectory(securePath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Managed path is not a directory")
        }
    }

    private fun validateStatePath(path: Path): Path {
        val securePath = validateManagedPath(path)
        if (!securePath.startsWith(layout.stateDirectory) || securePath == layout.stateDirectory) {
            throw IllegalArgumentException("Path is outside the extension state directory")
        }
        return securePath
    }

    private fun ensureDirectTransactionDirectory(path: Path) {
        val securePath = validateManagedPath(path)
        if (securePath.parent != layout.transactionsDirectory) {
            throw IllegalArgumentException("Only a direct transaction directory can be removed")
        }
        ensureDirectory(securePath)
    }

    private fun validateManagedPath(path: Path, allowExtensionRoot: Boolean = false): Path {
        if (path.any { segment -> segment.toString() == "." || segment.toString() == ".." }) {
            throw IllegalArgumentException("Traversal segments are not allowed in managed paths")
        }
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(extensionDirectory)) {
            throw IllegalArgumentException("Path escapes the extension directory")
        }
        if (!allowExtensionRoot && normalized == extensionDirectory) {
            throw IllegalArgumentException("The extension directory itself is not a file target")
        }

        if (normalized != extensionDirectory) {
            val relative = extensionDirectory.relativize(normalized)
            var current = extensionDirectory
            relative.forEach { segment ->
                validateWindowsSafeFileName(segment.toString())
                current = current.resolve(segment)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    rejectSymbolicLink(current)
                }
            }
        }
        return normalized
    }

    private fun uniqueSiblingTemporary(target: Path, purpose: String): Path {
        val random = UUID.randomUUID().toString()
        return validateManagedPath(target.resolveSibling(".${target.fileName}.$purpose-$random.tmp"))
    }

    private fun deleteInternalTemporary(path: Path) {
        val securePath = validateManagedPath(path)
        if (!Files.exists(securePath, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(securePath, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Internal temporary path is a directory")
        }
        Files.deleteIfExists(securePath)
    }

    private fun deleteFile(path: Path): Boolean {
        val operation = TransactionFileOperation(
            type = TransactionFileOperationType.DELETE,
            target = path,
        )
        faultInjector.before(operation)
        val deleted = Files.deleteIfExists(path)
        faultInjector.after(operation)
        if (deleted) forceDirectory(path.parent)
        return deleted
    }

    private fun requireSameFileStore(first: Path, second: Path) {
        val firstStore = existingFileStore(first)
        val secondStore = existingFileStore(second)
        if (!sameFileStore(firstStore, secondStore)) {
            throw IOException("Managed move would cross filesystems")
        }
    }

    private fun existingFileStore(path: Path): FileStore {
        var existing: Path? = path
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent
        }
        return Files.getFileStore(existing ?: throw IOException("Managed path has no existing ancestor"))
    }

    private fun sameFileStore(first: FileStore, second: FileStore): Boolean =
        first == second || (first.name() == second.name() && first.type() == second.type())

    private fun rejectSymbolicLink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw IOException("Symbolic links are not allowed in extension state")
        }
    }

    private fun validatePackageId(packageId: PackageId): String {
        val value = packageId.value
        require(value == value.trim()) { "PackageId cannot contain surrounding whitespace" }
        require(value.length <= MAX_PACKAGE_ID_LENGTH) { "PackageId is too long for a local file name" }
        val segments = value.split('.')
        require(segments.isNotEmpty() && segments.all(PACKAGE_SEGMENT_PATTERN::matches)) {
            "PackageId contains unsafe local path characters"
        }
        segments.forEach(::validateWindowsSafeFileName)
        validateWindowsSafeFileName("$value.jar")
        return value
    }

    private fun validateTransactionId(transactionId: String): String {
        require(transactionId == transactionId.trim()) {
            "Transaction id cannot contain surrounding whitespace"
        }
        require(transactionId.length in 1..MAX_TRANSACTION_ID_LENGTH) {
            "Transaction id has an invalid length"
        }
        require(TRANSACTION_ID_PATTERN.matches(transactionId)) {
            "Transaction id contains unsafe local path characters"
        }
        validateWindowsSafeFileName(transactionId)
        return transactionId
    }

    private fun validateWindowsSafeFileName(name: String) {
        require(name.isNotEmpty() && name != "." && name != "..") {
            "Managed path contains an invalid file name"
        }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANAGED_FILE_NAME_LENGTH) {
            "Managed path contains a file name that is too long"
        }
        require(name.none { character ->
            character.code in 0..31 || character in WINDOWS_INVALID_FILE_NAME_CHARACTERS
        }) {
            "Managed path contains characters invalid on Windows"
        }
        require(!name.endsWith(' ') && !name.endsWith('.')) {
            "Managed path contains a name invalid on Windows"
        }
        val windowsStem = name.substringBefore('.').uppercase(Locale.ROOT)
        require(windowsStem !in WINDOWS_RESERVED_FILE_NAMES) {
            "Managed path contains a reserved Windows file name"
        }
    }

    private fun restrictToCurrentUser(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    companion object {
        private val LOCAL_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()

        private fun prepareExtensionDirectory(configuredDirectory: Path): Path {
            val normalized = configuredDirectory.toAbsolutePath().normalize()
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized)) {
                    throw IOException("Extension directory cannot be a symbolic link")
                }
                if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Extension path is not a directory")
                }
            } else {
                Files.createDirectories(normalized)
            }
            if (Files.isSymbolicLink(normalized)) {
                throw IOException("Extension directory cannot be a symbolic link")
            }
            return normalized.toRealPath()
        }
    }
}
