package eu.kanade.tachiyomi.extension.update.storage

import eu.kanade.tachiyomi.extension.update.model.PackageId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionFileStoreTest {
    @Test
    fun `layout keeps backups and temporaries below hidden state directory`() = withTemporaryDirectory { root ->
        val store = TransactionFileStore(root)
        val transaction = store.createTransaction("transaction-1")
        val packageId = PackageId("eu.kanade.extension.example")

        assertTrue(Files.isDirectory(store.stateDirectory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(store.metadataDirectory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(store.transactionsDirectory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(store.locksDirectory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(transaction.backupJar.startsWith(store.stateDirectory))
        assertTrue(transaction.candidateJar.startsWith(store.stateDirectory))
        assertFalse(transaction.backupJar.parent == store.extensionDirectory)
        assertTrue(store.activeJar(packageId).parent == store.extensionDirectory)
    }

    @Test
    fun `atomic write preserves previous bytes when move fails before promotion`() = withTemporaryDirectory { root ->
        val target = TransactionFileStore(root).layout.pendingRemovalsFile
        TransactionFileStore(root).writeAtomically(target, "previous".encodeToByteArray())
        val failingStore = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.BEFORE),
        )

        assertFailsWith<IOException> {
            failingStore.writeAtomically(target, "replacement".encodeToByteArray())
        }

        assertContentEquals("previous".encodeToByteArray(), Files.readAllBytes(target))
        assertNoSiblingTemporary(target, "write")
    }

    @Test
    fun `atomic write exposes only complete replacement when move fails after promotion`() = withTemporaryDirectory { root ->
        val normalStore = TransactionFileStore(root)
        val target = normalStore.layout.pendingRemovalsFile
        normalStore.writeAtomically(target, "previous".encodeToByteArray())
        val replacement = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        val failingStore = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.AFTER),
        )

        assertFailsWith<IOException> {
            failingStore.writeAtomically(target, replacement)
        }

        assertContentEquals(replacement, Files.readAllBytes(target))
        assertNoSiblingTemporary(target, "write")
    }

    @Test
    fun `managed operations reject traversal outside cleanup and symbolic links`() = withTemporaryDirectory { root ->
        val store = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.example")
        val activeJar = store.activeJar(packageId)
        val activeBytes = byteArrayOf(4, 2, 4, 2)
        Files.write(activeJar, activeBytes)

        assertFailsWith<IllegalArgumentException> {
            store.writeAtomically(root.resolve("..").resolve("outside.json"), byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.writeAtomically(root.resolveSibling("outside.json"), byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            store.deleteStateFile(activeJar)
        }
        assertFailsWith<IllegalArgumentException> {
            store.transactionPaths("../escaped-transaction")
        }

        val transaction = store.createTransaction("symlink-cleanup")
        val externalBytes = byteArrayOf(9, 8, 7)
        val external = Files.createTempFile(root.parent, "transaction-store-external-", ".json")
        Files.write(external, externalBytes)
        val link = transaction.directory.resolve("linked.json")
        try {
            val linkCreated = runCatching { Files.createSymbolicLink(link, external) }.isSuccess
            if (linkCreated) {
                assertFailsWith<IOException> {
                    store.readBytes(link)
                }
                assertFailsWith<IOException> {
                    store.deleteTransaction(transaction.transactionId)
                }
                assertContentEquals(externalBytes, Files.readAllBytes(external))
            }
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(external)
        }

        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
    }

    @Test
    fun `Windows reserved names are rejected for packages and managed files`() {
        if (!isWindows()) return

        withTemporaryDirectory { root ->
            val store = TransactionFileStore(root)
            val reservedStems = listOf("CON", "prn", "Aux", "nul", "COM1", "lPt9")

            reservedStems.forEach { reserved ->
                assertFailsWith<IllegalArgumentException>("Package segment $reserved must be rejected") {
                    store.activeJar(PackageId("example.$reserved"))
                }
                assertFailsWith<IllegalArgumentException>("Managed file $reserved must be rejected") {
                    store.writeAtomically(
                        store.stateDirectory.resolve("$reserved.json"),
                        byteArrayOf(1),
                    )
                }
            }
        }
    }

    @Test
    fun `Windows rooted UNC and backslash traversal paths remain confined`() {
        if (!isWindows()) return

        withTemporaryDirectory { root ->
            val store = TransactionFileStore(root)
            val driveRoot = assertNotNull(root.root)
            val outsidePaths = listOf(
                driveRoot.resolve("aniyomi-transaction-store-outside.json"),
                Path.of("\\\\localhost\\aniyomi-test\\outside.json"),
                root.resolve("..\\outside.json"),
            )

            outsidePaths.forEach { outside ->
                assertFailsWith<IllegalArgumentException>("Windows path must remain outside managed state: $outside") {
                    store.writeAtomically(outside, byteArrayOf(1))
                }
            }
        }
    }

    @Test
    fun `backup failure before move leaves active jar unchanged and no partial backup`() = withTemporaryDirectory { root ->
        val normalStore = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.example")
        val activeJar = normalStore.activeJar(packageId)
        val activeBytes = ByteArray(96 * 1024) { index -> (index % 193).toByte() }
        Files.write(activeJar, activeBytes)
        val transaction = normalStore.createTransaction("backup-before-move")
        val failingStore = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.BEFORE),
        )

        assertFailsWith<IOException> {
            failingStore.copyDurably(activeJar, transaction.backupJar)
        }

        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
        assertFalse(Files.exists(transaction.backupJar, LinkOption.NOFOLLOW_LINKS))
        assertNoSiblingTemporary(transaction.backupJar, "copy")
    }

    @Test
    fun `backup is retained after post move failure while active jar stays unchanged`() = withTemporaryDirectory { root ->
        val normalStore = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.example")
        val activeJar = normalStore.activeJar(packageId)
        val activeBytes = ByteArray(96 * 1024) { index -> (index % 197).toByte() }
        Files.write(activeJar, activeBytes)
        val transaction = normalStore.createTransaction("backup-after-move")
        val failingStore = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.AFTER),
        )

        assertFailsWith<IOException> {
            failingStore.copyDurably(activeJar, transaction.backupJar)
        }

        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
        assertContentEquals(activeBytes, Files.readAllBytes(transaction.backupJar))
        assertNoSiblingTemporary(transaction.backupJar, "copy")
    }

    @Test
    fun `recoverable promotion failure before move keeps complete candidate off final path`() =
        withTemporaryDirectory { root ->
            val normalStore = TransactionFileStore(root)
            val packageId = PackageId("eu.kanade.extension.example")
            val activeJar = normalStore.activeJar(packageId)
            val transaction = normalStore.createTransaction("promotion-before-move")
            val candidateBytes = ByteArray(128 * 1024) { index -> (index % 181).toByte() }
            Files.write(transaction.candidateJar, candidateBytes)
            normalStore.forceFile(transaction.candidateJar)
            val failingStore = TransactionFileStore(
                extensionDirectory = root,
                faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.BEFORE),
            )

            assertFailsWith<IOException> {
                failingStore.moveRecoverably(transaction.candidateJar, activeJar)
            }

            assertContentEquals(candidateBytes, Files.readAllBytes(transaction.candidateJar))
            assertFalse(Files.exists(activeJar, LinkOption.NOFOLLOW_LINKS))
        }

    @Test
    fun `recoverable promotion failure after move leaves complete candidate at final path`() =
        withTemporaryDirectory { root ->
            val normalStore = TransactionFileStore(root)
            val packageId = PackageId("eu.kanade.extension.example")
            val activeJar = normalStore.activeJar(packageId)
            val transaction = normalStore.createTransaction("promotion-after-move")
            val candidateBytes = ByteArray(128 * 1024) { index -> (index % 179).toByte() }
            Files.write(transaction.candidateJar, candidateBytes)
            normalStore.forceFile(transaction.candidateJar)
            val failingStore = TransactionFileStore(
                extensionDirectory = root,
                faultInjector = failOn(TransactionFileOperationType.MOVE, FaultPhase.AFTER),
            )

            assertFailsWith<IOException> {
                failingStore.moveRecoverably(transaction.candidateJar, activeJar)
            }

            assertFalse(Files.exists(transaction.candidateJar, LinkOption.NOFOLLOW_LINKS))
            assertContentEquals(candidateBytes, Files.readAllBytes(activeJar))
        }

    @Test
    fun `global and package locks exclude matching writers and release cleanly`() = withTemporaryDirectory { root ->
        val firstStore = TransactionFileStore(root)
        val secondStore = TransactionFileStore(root)
        val firstPackage = PackageId("eu.kanade.extension.first")
        val secondPackage = PackageId("eu.kanade.extension.second")
        val activeJar = firstStore.activeJar(firstPackage)
        val activeBytes = byteArrayOf(1, 3, 3, 7)
        Files.write(activeJar, activeBytes)

        firstStore.acquireGlobalLock().use {
            assertNull(secondStore.tryAcquireGlobalLock())
        }
        assertNotNull(secondStore.tryAcquireGlobalLock()).close()

        firstStore.acquirePackageLock(firstPackage).use {
            assertNull(secondStore.tryAcquirePackageLock(firstPackage))
            assertNotNull(secondStore.tryAcquirePackageLock(secondPackage)).close()
        }
        assertNotNull(secondStore.tryAcquirePackageLock(firstPackage)).close()

        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
    }

    @Test
    fun `successful transaction cleanup removes only transaction state`() = withTemporaryDirectory { root ->
        val store = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.example")
        val activeJar = store.activeJar(packageId)
        val activeBytes = byteArrayOf(1, 3, 3, 7)
        Files.write(activeJar, activeBytes)
        store.forceFile(activeJar)
        val transaction = store.createTransaction("cleanup-success")

        store.copyDurably(activeJar, transaction.backupJar)
        store.writeAtomically(transaction.journal, "journal".encodeToByteArray())
        Files.write(transaction.artifact, byteArrayOf(5, 6, 7))
        Files.write(transaction.candidateJar, byteArrayOf(8, 9, 10))

        assertTrue(store.deleteTransaction(transaction.transactionId))
        assertFalse(Files.exists(transaction.directory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(activeJar, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
        assertFalse(store.deleteTransaction(transaction.transactionId))
    }

    @Test
    fun `failed transaction cleanup preserves backup and active jar for recovery`() = withTemporaryDirectory { root ->
        val normalStore = TransactionFileStore(root)
        val packageId = PackageId("eu.kanade.extension.example")
        val activeJar = normalStore.activeJar(packageId)
        val activeBytes = byteArrayOf(2, 4, 6, 8)
        Files.write(activeJar, activeBytes)
        val transaction = normalStore.createTransaction("cleanup-failure")
        normalStore.copyDurably(activeJar, transaction.backupJar)
        val failingStore = TransactionFileStore(
            extensionDirectory = root,
            faultInjector = failOn(TransactionFileOperationType.DELETE, FaultPhase.BEFORE),
        )

        assertFailsWith<IOException> {
            failingStore.deleteTransaction(transaction.transactionId)
        }

        assertTrue(Files.isDirectory(transaction.directory, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(activeBytes, Files.readAllBytes(transaction.backupJar))
        assertContentEquals(activeBytes, Files.readAllBytes(activeJar))
    }

    private fun failOn(
        operationType: TransactionFileOperationType,
        phase: FaultPhase,
    ): TransactionFileStoreFaultInjector = object : TransactionFileStoreFaultInjector {
        override fun before(operation: TransactionFileOperation) {
            if (phase == FaultPhase.BEFORE && operation.type == operationType) {
                throw IOException("injected before ${operation.type}")
            }
        }

        override fun after(operation: TransactionFileOperation) {
            if (phase == FaultPhase.AFTER && operation.type == operationType) {
                throw IOException("injected after ${operation.type}")
            }
        }
    }

    private fun assertNoSiblingTemporary(target: Path, purpose: String) {
        val prefix = ".${target.fileName}.$purpose-"
        Files.newDirectoryStream(target.parent) { path ->
            path.fileName.toString().startsWith(prefix)
        }.use { leftovers ->
            assertFalse(leftovers.iterator().hasNext(), "Temporary $purpose file was not cleaned")
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("transaction-file-store-test-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private enum class FaultPhase {
        BEFORE,
        AFTER,
    }
}
