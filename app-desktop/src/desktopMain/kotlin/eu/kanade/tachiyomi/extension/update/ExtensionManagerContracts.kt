package eu.kanade.tachiyomi.extension.update

import eu.kanade.tachiyomi.extension.update.model.ArtifactRequest
import eu.kanade.tachiyomi.extension.update.model.CandidateValidationReport
import eu.kanade.tachiyomi.extension.update.model.ConfirmedExtensionCommand
import eu.kanade.tachiyomi.extension.update.model.ConfirmedUninstallCommand
import eu.kanade.tachiyomi.extension.update.model.ExtensionLoadSnapshot
import eu.kanade.tachiyomi.extension.update.model.ExtensionProgressEvent
import eu.kanade.tachiyomi.extension.update.model.HttpCacheValidator
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.RecoveryReport
import eu.kanade.tachiyomi.extension.update.model.RepositoryIndexResult
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import eu.kanade.tachiyomi.extension.update.model.RuntimeDetachResult
import eu.kanade.tachiyomi.extension.update.model.RuntimeRefreshResult
import eu.kanade.tachiyomi.extension.update.model.TransactionResult
import eu.kanade.tachiyomi.extension.update.model.UninstallResult
import java.nio.file.Path

fun interface ExtensionProgressListener {
    fun onProgress(event: ExtensionProgressEvent)
}

/**
 * Bridge to the existing desktop runtime. Its implementation belongs to the application root,
 * where the current source collections and refresh operation already live.
 */
interface ExtensionRuntimePort {
    suspend fun detach(packageId: PackageId): RuntimeDetachResult

    suspend fun refreshExtensions(
        excludedPackages: Set<PackageId>,
    ): RuntimeRefreshResult
}

/**
 * Contract implemented by the existing [eu.kanade.tachiyomi.extension.ExtensionManager].
 * It is deliberately only an interface: no second manager instance or management facade is
 * introduced. The manager's legacy methods remain available as migration adapters until all
 * current callers move to these confirmed, typed operations.
 */
interface ExtensionManagerApi {
    suspend fun fetchRepositoryIndex(
        repository: RepositoryRef,
        cacheValidator: HttpCacheValidator? = null,
    ): RepositoryIndexResult

    suspend fun installOrUpdate(
        command: ConfirmedExtensionCommand,
        runtime: ExtensionRuntimePort,
        progress: ExtensionProgressListener,
    ): TransactionResult

    suspend fun uninstall(
        command: ConfirmedUninstallCommand,
        runtime: ExtensionRuntimePort,
        progress: ExtensionProgressListener,
    ): UninstallResult

    suspend fun recoverInterruptedTransactions(
        runtime: ExtensionRuntimePort,
        excludedPackages: Set<PackageId>,
        progress: ExtensionProgressListener,
    ): RecoveryReport

    suspend fun loadLocalSnapshot(
        excludedPackages: Set<PackageId>,
    ): ExtensionLoadSnapshot
}

interface ArtifactDownloader {
    suspend fun download(
        request: ArtifactRequest,
        targetPart: Path,
        progress: ExtensionProgressListener,
    )
}

interface ApkToJarConverter {
    suspend fun convert(apk: Path, candidateJar: Path)
}

interface CandidateValidator {
    suspend fun validate(
        candidateJar: Path,
        expectedPackageId: PackageId,
    ): CandidateValidationReport
}
