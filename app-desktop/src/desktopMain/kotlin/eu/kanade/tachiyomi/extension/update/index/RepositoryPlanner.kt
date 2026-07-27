package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.model.NormalizedRepositoryUrl
import eu.kanade.tachiyomi.extension.update.model.RepositoryCategory
import eu.kanade.tachiyomi.extension.update.model.RepositoryRef
import java.net.IDN
import java.net.URI
import java.util.Locale

/** Builds the stable, trusted repository list used by update checks. */
class RepositoryPlanner(
    private val normalizer: (String) -> NormalizedRepositoryUrl? = ::normalizeRepositoryUrl,
) {
    fun plan(
        animeRepos: List<String>,
        mangaRepos: List<String>,
        trustedRepositories: Collection<String>,
    ): List<RepositoryRef> {
        val trustedUrls = trustedRepositories.mapNotNull(normalizer).toSet()
        val accumulated = linkedMapOf<NormalizedRepositoryUrl, AccumulatedRepository>()

        fun accumulate(originalUrl: String, category: RepositoryCategory, persistedRank: Int) {
            val normalizedUrl = normalizer(originalUrl) ?: return
            val repository = accumulated.getOrPut(normalizedUrl) {
                AccumulatedRepository(
                    originalUrl = originalUrl,
                    normalizedUrl = normalizedUrl,
                    persistedRank = persistedRank,
                )
            }
            repository.categories += category
        }

        animeRepos.forEachIndexed { index, url ->
            accumulate(url, RepositoryCategory.ANIME, index)
        }
        mangaRepos.forEachIndexed { index, url ->
            accumulate(url, RepositoryCategory.MANGA, animeRepos.size + index)
        }

        return accumulated.values
            .asSequence()
            .filter { it.normalizedUrl in trustedUrls }
            .map { repository ->
                RepositoryRef(
                    originalUrl = repository.originalUrl,
                    normalizedUrl = repository.normalizedUrl,
                    persistedRank = repository.persistedRank,
                    categories = repository.categories.toSet(),
                    trusted = true,
                )
            }
            .toList()
    }

    private data class AccumulatedRepository(
        val originalUrl: String,
        val normalizedUrl: NormalizedRepositoryUrl,
        val persistedRank: Int,
        val categories: MutableSet<RepositoryCategory> = linkedSetOf(),
    )
}

/**
 * Canonical identity used both for configured repositories and trust entries.
 * Invalid or unsupported repository URLs are omitted from a query plan.
 */
fun normalizeRepositoryUrl(value: String): NormalizedRepositoryUrl? = runCatching {
    val uri = URI(value.trim())
    require(uri.isAbsolute && !uri.isOpaque)
    require(uri.userInfo == null)

    val scheme = uri.scheme.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https")
    val host = IDN.toASCII(
        requireNotNull(uri.host).lowercase(Locale.ROOT),
        IDN.USE_STD3_ASCII_RULES,
    ).lowercase(Locale.ROOT)
    require(host.isNotBlank())

    val path = uri.path.orEmpty()
    require(!pathEscapesRoot(path))
    val port = when {
        scheme == "http" && uri.port == 80 -> -1
        scheme == "https" && uri.port == 443 -> -1
        else -> uri.port
    }
    val normalized = URI(
        scheme,
        null,
        host,
        port,
        path,
        uri.query,
        null,
    ).normalize().toASCIIString()

    NormalizedRepositoryUrl(normalized)
}.getOrNull()

private fun pathEscapesRoot(path: String): Boolean {
    var depth = 0
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (depth == 0) return true
                depth--
            }
            else -> depth++
        }
    }
    return false
}
