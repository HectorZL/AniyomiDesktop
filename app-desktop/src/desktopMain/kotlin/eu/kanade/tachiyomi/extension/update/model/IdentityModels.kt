package eu.kanade.tachiyomi.extension.update.model

@JvmInline
value class PackageId(val value: String) {
    init {
        require(value.isNotBlank()) { "PackageId cannot be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class NormalizedRepositoryUrl(val value: String) {
    init {
        require(value.isNotBlank()) { "Normalized repository URL cannot be blank" }
    }

    override fun toString(): String = value
}

enum class RepositoryCategory {
    ANIME,
    MANGA,
}

data class RepositoryRef(
    val originalUrl: String,
    val normalizedUrl: NormalizedRepositoryUrl,
    val persistedRank: Int,
    val categories: Set<RepositoryCategory>,
    val trusted: Boolean,
) {
    init {
        require(originalUrl.isNotBlank()) { "Repository URL cannot be blank" }
        require(persistedRank >= 0) { "Repository rank cannot be negative" }
        require(categories.isNotEmpty()) { "Repository must belong to at least one category" }
    }
}

data class VersionDescriptor(
    val text: String,
    val versionCode: Long?,
) {
    init {
        require(text.isNotBlank()) { "Version text cannot be blank" }
        require(versionCode == null || versionCode >= 0) { "Version code cannot be negative" }
    }
}

sealed interface VersionComparison {
    data object Lower : VersionComparison
    data object Equal : VersionComparison
    data object Greater : VersionComparison
    data object Unknown : VersionComparison
}
