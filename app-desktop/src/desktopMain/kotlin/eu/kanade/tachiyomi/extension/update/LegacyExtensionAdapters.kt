package eu.kanade.tachiyomi.extension.update

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.update.model.ExtensionLoadSnapshot
import eu.kanade.tachiyomi.extension.update.model.ExtensionUpdateError
import eu.kanade.tachiyomi.extension.update.model.LoadedExtensionSource
import eu.kanade.tachiyomi.extension.update.model.PackageId
import eu.kanade.tachiyomi.extension.update.model.TransactionStage
import eu.kanade.tachiyomi.extension.update.model.UninstallResult
import java.nio.file.Path

/** Converts the current manager source wrapper without changing either source instance. */
fun ExtensionManager.LoadedSource.toUpdateSource(): LoadedExtensionSource = when (this) {
    is ExtensionManager.LoadedSource.Anime -> LoadedExtensionSource.Anime(source)
    is ExtensionManager.LoadedSource.Manga -> LoadedExtensionSource.Manga(source)
}

/** Keeps callers of the current manager API usable while snapshot loading is migrated. */
fun legacyLoadSnapshot(
    loaded: Pair<List<ExtensionManager.LoadedSource>, Map<String, List<String>>>,
    installedJars: Set<Path>,
): ExtensionLoadSnapshot = ExtensionLoadSnapshot(
    sources = loaded.first.map(ExtensionManager.LoadedSource::toUpdateSource),
    installedJars = installedJars,
    errors = loaded.second,
)

/** Maps the existing uninstall triple to the typed result used by the new contract. */
fun legacyUninstallResult(
    packageId: PackageId,
    result: Triple<Boolean, Boolean, String>,
): UninstallResult = when {
    result.first -> UninstallResult.Removed(packageId)
    result.second -> UninstallResult.Deferred(packageId, result.third)
    else -> UninstallResult.Failed(
        packageId = packageId,
        error = ExtensionUpdateError.FileSystem(
            stage = TransactionStage.UNINSTALLING,
            diagnosticId = "legacy-uninstall-failed",
        ),
        message = result.third.ifBlank { null },
    )
}
