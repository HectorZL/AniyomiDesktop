package eu.kanade.tachiyomi.data.track.trakt.dto

import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("refresh_token")
    val refreshToken: String,
    val scope: String,
    @SerialName("created_at")
    val createdAt: Long,
)

@Serializable
data class TraktUser(
    val user: TraktUserAccount,
)

@Serializable
data class TraktUserAccount(
    val username: String,
)

@Serializable
data class TraktIds(
    val trakt: Long,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
)

@Serializable
data class TraktSearchResultItem(
    val title: String,
    val year: Int? = null,
    val ids: TraktIds,
)

@Serializable
data class TraktSearchResult(
    val type: String,
    val score: Double? = null,
    val movie: TraktSearchResultItem? = null,
    val show: TraktSearchResultItem? = null,
) {
    fun toTrackSearch(coverUrl: String = ""): AnimeTrackSearch {
        val item = if (type == "movie") movie!! else show!!
        return AnimeTrackSearch.create(TrackerManager.TRAKT).apply {
            remote_id = item.ids.trakt
            title = item.title
            total_episodes = if (type == "movie") 1 else 0
            cover_url = coverUrl
            summary = "Year: ${item.year ?: ""}"
            tracking_url = if (type == "movie") "https://trakt.tv/movies/${item.ids.slug ?: item.ids.trakt}" else "https://trakt.tv/shows/${item.ids.slug ?: item.ids.trakt}"
            publishing_status = ""
            publishing_type = type
            start_date = item.year?.toString() ?: ""
        }
    }
}

@Serializable
data class TraktEpisodeIds(
    val trakt: Long,
)

@Serializable
data class TraktEpisode(
    val season: Int,
    val number: Int,
    val title: String? = null,
    val ids: TraktEpisodeIds,
)

@Serializable
data class TraktSeason(
    val number: Int,
    val episodes: List<TraktEpisode>? = null,
)

@Serializable
data class TraktShowProgress(
    val aired: Int,
    val completed: Int,
    @SerialName("last_watched_at")
    val lastWatchedAt: String? = null,
)

@Serializable
data class TraktWatchlistItem(
    val type: String,
    val show: TraktSearchResultItem? = null,
    val movie: TraktSearchResultItem? = null,
)

@Serializable
data class TraktHistoryItem(
    val id: Long,
)

@Serializable
data class TraktUserRating(
    val rating: Int,
    val type: String,
    val show: TraktSearchResultItem? = null,
    val movie: TraktSearchResultItem? = null,
)
