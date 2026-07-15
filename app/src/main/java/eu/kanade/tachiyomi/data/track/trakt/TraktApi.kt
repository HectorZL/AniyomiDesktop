package eu.kanade.tachiyomi.data.track.trakt

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktOAuth
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSearchResult
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSeason
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktShowProgress
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktUser
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktUserRating
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktWatchlistItem
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

class TraktApi(private val client: OkHttpClient, interceptor: TraktInterceptor) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    // ─── Search ────────────────────────────────────────────────────────────

    suspend fun searchAnime(query: String): List<AnimeTrackSearch> {
        return withIOContext {
            val searchUrl = "$API_URL/search/movie,show".toUri().buildUpon()
                .appendQueryParameter("query", query)
                .appendQueryParameter("extended", "full")
                .appendQueryParameter("limit", "20")
                .build()

            val results = with(json) {
                client.newCall(
                    GET(
                        searchUrl.toString(),
                        headers = okhttp3.Headers.Builder()
                            .add("trakt-api-key", CLIENT_ID)
                            .add("trakt-api-version", "2")
                            .add("Content-Type", "application/json")
                            .build(),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<List<TraktSearchResult>>()
            }

            kotlinx.coroutines.coroutineScope {
                results.mapNotNull { result ->
                    if (result.type == "movie" || result.type == "show") {
                        val item = if (result.type == "movie") result.movie!! else result.show!!
                        val tmdbId = item.ids.tmdb
                        if (tmdbId != null && TMDB_API_KEY.isNotBlank()) {
                            async {
                                val coverUrl = getTmdbPoster(tmdbId, result.type)
                                result.toTrackSearch(coverUrl)
                            }
                        } else {
                            async {
                                result.toTrackSearch("")
                            }
                        }
                    } else {
                        null
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun getTmdbPoster(tmdbId: Long, type: String): String {
        return runCatching {
            val pathType = if (type == "movie") "movie" else "tv"
            val url = "https://api.themoviedb.org/3/$pathType/$tmdbId?api_key=$TMDB_API_KEY"
            val response = client.newCall(GET(url)).awaitSuccess()
            val posterResult = with(json) { response.parseAs<TmdbPosterResult>() }
            posterResult.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
        }.getOrDefault("")
    }

    @Serializable
    private data class TmdbPosterResult(
        @SerialName("poster_path") val posterPath: String? = null,
    )

    // ─── Library queries ───────────────────────────────────────────────────

    /**
     * Fetches the item from the user's watchlist (Plan to Watch status).
     * Returns true if found.
     */
    suspend fun isInWatchlist(track: AnimeTrack): Boolean {
        return withIOContext {
            val type = getMediaType(track)
            val url = "$API_URL/sync/watchlist/$type".toUri().buildUpon()
                .appendQueryParameter("extended", "min")
                .build()

            val items = with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<List<TraktWatchlistItem>>()
            }
            items.any { item ->
                when (type) {
                    "movies" -> item.movie?.ids?.trakt == track.remote_id
                    else -> item.show?.ids?.trakt == track.remote_id
                }
            }
        }
    }

    /**
     * For shows: fetches the watched progress (completed episode count, total aired).
     * For movies: checks if the movie appears in history.
     */
    suspend fun findLibAnime(track: AnimeTrack): AnimeTrack? {
        return withIOContext {
            val type = getMediaType(track)
            if (type == "movies") {
                fetchMovieStatus(track)
            } else {
                fetchShowStatus(track)
            }
        }
    }

    private suspend fun fetchMovieStatus(track: AnimeTrack): AnimeTrack? {
        // Check if movie is in history (completed)
        val historyUrl = "$API_URL/sync/history/movies/${track.remote_id}"
        val historyItems = with(json) {
            authClient.newCall(GET(historyUrl))
                .awaitSuccess()
                .parseAs<List<eu.kanade.tachiyomi.data.track.trakt.dto.TraktHistoryItem>>()
        }
        if (historyItems.isNotEmpty()) {
            track.status = Trakt.COMPLETED
            track.last_episode_seen = 1.0
            track.total_episodes = 1
        } else if (isInWatchlist(track)) {
            track.status = Trakt.PLAN_TO_WATCH
            track.last_episode_seen = 0.0
            track.total_episodes = 1
        } else {
            return null
        }
        // Fetch rating
        track.score = getRating(track)
        return track
    }

    private suspend fun fetchShowStatus(track: AnimeTrack): AnimeTrack? {
        // Get watch progress
        val progressUrl = "$API_URL/shows/${track.remote_id}/progress/watched"
        val progress = runCatching {
            with(json) {
                authClient.newCall(GET(progressUrl))
                    .awaitSuccess()
                    .parseAs<TraktShowProgress>()
            }
        }.getOrNull() ?: return null

        val completed = progress.completed
        val aired = progress.aired

        track.total_episodes = aired.toLong()
        track.last_episode_seen = completed.toDouble()

        track.status = when {
            completed == 0 && isInWatchlist(track) -> Trakt.PLAN_TO_WATCH
            completed > 0 && completed >= aired && aired > 0 -> Trakt.COMPLETED
            completed > 0 -> Trakt.WATCHING
            else -> return null
        }
        track.score = getRating(track)
        return track
    }

    private suspend fun getRating(track: AnimeTrack): Double {
        val type = getMediaType(track)
        val url = "$API_URL/sync/ratings/$type"
        return runCatching {
            val ratings = with(json) {
                authClient.newCall(GET(url))
                    .awaitSuccess()
                    .parseAs<List<TraktUserRating>>()
            }
            val match = ratings.firstOrNull { r ->
                when (type) {
                    "movies" -> r.movie?.ids?.trakt == track.remote_id
                    else -> r.show?.ids?.trakt == track.remote_id
                }
            }
            match?.rating?.toDouble() ?: 0.0
        }.getOrDefault(0.0)
    }

    // ─── Sync / Update ─────────────────────────────────────────────────────

    suspend fun addLibAnime(track: AnimeTrack): AnimeTrack {
        return withIOContext {
            syncTrackStatus(track)
            track
        }
    }

    suspend fun updateLibAnime(track: AnimeTrack): AnimeTrack {
        return withIOContext {
            syncTrackStatus(track)
            updateRating(track)
            track
        }
    }

    private suspend fun syncTrackStatus(track: AnimeTrack) {
        val type = getMediaType(track)
        when (track.status) {
            Trakt.PLAN_TO_WATCH -> {
                // Remove from history, add to watchlist
                removeFromHistory(track)
                addToWatchlist(track)
            }
            Trakt.WATCHING -> {
                // Remove from watchlist if present, update history
                removeFromWatchlist(track)
                if (type == "shows") {
                    syncShowHistory(track)
                }
            }
            Trakt.COMPLETED -> {
                // Remove from watchlist, add to history as completed
                removeFromWatchlist(track)
                if (type == "movies") {
                    addMovieToHistory(track)
                } else {
                    syncShowHistory(track)
                }
            }
        }
    }

    private suspend fun addToWatchlist(track: AnimeTrack) {
        val type = getMediaType(track)
        val mediaKey = if (type == "movies") "movies" else "shows"
        val payload = buildJsonObject {
            putJsonArray(mediaKey) {
                addJsonObject {
                    putJsonObject("ids") {
                        put("trakt", track.remote_id)
                    }
                }
            }
        }.toString().toRequestBody(jsonMime)
        authClient.newCall(POST("$API_URL/sync/watchlist", body = payload)).awaitSuccess()
    }

    private suspend fun removeFromWatchlist(track: AnimeTrack) {
        val type = getMediaType(track)
        val mediaKey = if (type == "movies") "movies" else "shows"
        val payload = buildJsonObject {
            putJsonArray(mediaKey) {
                addJsonObject {
                    putJsonObject("ids") {
                        put("trakt", track.remote_id)
                    }
                }
            }
        }.toString().toRequestBody(jsonMime)
        runCatching {
            authClient.newCall(POST("$API_URL/sync/watchlist/remove", body = payload)).awaitSuccess()
        }
    }

    private suspend fun addMovieToHistory(track: AnimeTrack) {
        val payload = buildJsonObject {
            putJsonArray("movies") {
                addJsonObject {
                    putJsonObject("ids") {
                        put("trakt", track.remote_id)
                    }
                }
            }
        }.toString().toRequestBody(jsonMime)
        authClient.newCall(POST("$API_URL/sync/history", body = payload)).awaitSuccess()
    }

    private suspend fun removeFromHistory(track: AnimeTrack) {
        val type = getMediaType(track)
        val mediaKey = if (type == "movies") "movies" else "shows"
        val payload = buildJsonObject {
            putJsonArray(mediaKey) {
                addJsonObject {
                    putJsonObject("ids") {
                        put("trakt", track.remote_id)
                    }
                }
            }
        }.toString().toRequestBody(jsonMime)
        runCatching {
            authClient.newCall(POST("$API_URL/sync/history/remove", body = payload)).awaitSuccess()
        }
    }

    /**
     * For shows: fetch the episode list from Trakt (all seasons flattened in order),
     * then mark episodes 1..lastEpisodeSeen as watched.
     * This maps Mihon's linear chapter number → Trakt season/episode.
     */
    private suspend fun syncShowHistory(track: AnimeTrack) {
        val lastSeen = track.last_episode_seen.toInt()
        if (lastSeen <= 0) return

        // Fetch all seasons with episodes
        val seasonsUrl = "$API_URL/shows/${track.remote_id}/seasons?extended=episodes"
        val seasons = with(json) {
            authClient.newCall(GET(seasonsUrl))
                .awaitSuccess()
                .parseAs<List<TraktSeason>>()
        }

        // Build a flat ordered list of (seasonNum, episodeNum) pairs (skip season 0 / specials)
        val flatEpisodes = seasons
            .filter { it.number > 0 }
            .sortedBy { it.number }
            .flatMap { season ->
                season.episodes
                    ?.sortedBy { it.number }
                    ?.map { ep -> Pair(season.number, ep.number) }
                    ?: emptyList()
            }

        // Take the first `lastSeen` episodes
        val episodesToMark = flatEpisodes.take(lastSeen)

        if (episodesToMark.isEmpty()) return

        // Build episodes array for history
        val episodesArray = buildJsonArray {
            for ((season, episode) in episodesToMark) {
                addJsonObject {
                    put("season", season)
                    put("number", episode)
                }
            }
        }

        val payload = buildJsonObject {
            putJsonArray("shows") {
                addJsonObject {
                    putJsonObject("ids") {
                        put("trakt", track.remote_id)
                    }
                    putJsonArray("seasons") {
                        // Group episodes by season
                        val bySeason = episodesToMark.groupBy({ it.first }, { it.second })
                        for ((seasonNum, episodeNums) in bySeason) {
                            addJsonObject {
                                put("number", seasonNum)
                                putJsonArray("episodes") {
                                    for (epNum in episodeNums) {
                                        addJsonObject {
                                            put("number", epNum)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString().toRequestBody(jsonMime)

        // First clear previous history for this show, then add the correct episodes
        removeFromHistory(track)
        authClient.newCall(POST("$API_URL/sync/history", body = payload)).awaitSuccess()
    }

    private suspend fun updateRating(track: AnimeTrack) {
        val type = getMediaType(track)
        val mediaKey = if (type == "movies") "movies" else "shows"
        val score = track.score.toInt()

        if (score == 0) {
            val payload = buildJsonObject {
                putJsonArray(mediaKey) {
                    addJsonObject {
                        putJsonObject("ids") {
                            put("trakt", track.remote_id)
                        }
                    }
                }
            }.toString().toRequestBody(jsonMime)
            runCatching {
                authClient.newCall(POST("$API_URL/sync/ratings/remove", body = payload)).awaitSuccess()
            }
        } else {
            val payload = buildJsonObject {
                putJsonArray(mediaKey) {
                    addJsonObject {
                        putJsonObject("ids") {
                            put("trakt", track.remote_id)
                        }
                        put("rating", score)
                    }
                }
            }.toString().toRequestBody(jsonMime)
            runCatching {
                authClient.newCall(POST("$API_URL/sync/ratings", body = payload)).awaitSuccess()
            }
        }
    }

    // ─── Authentication ────────────────────────────────────────────────────

    suspend fun accessToken(code: String): TraktOAuth {
        return withIOContext {
            with(json) {
                client.newCall(accessTokenRequest(code))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    private fun accessTokenRequest(code: String) = POST(
        "$API_URL/oauth/token",
        body = buildJsonObject {
            put("code", code)
            put("client_id", CLIENT_ID)
            put("client_secret", CLIENT_SECRET)
            put("redirect_uri", REDIRECT_URL)
            put("grant_type", "authorization_code")
        }.toString().toRequestBody(jsonMime),
    )

    fun getCurrentUser(): String {
        return runBlocking {
            with(json) {
                authClient.newCall(GET("$API_URL/users/settings"))
                    .awaitSuccess()
                    .parseAs<TraktUser>()
                    .user.username
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun getMediaType(track: AnimeTrack): String {
        // tracking_url format: "https://trakt.tv/movies/..." or "https://trakt.tv/shows/..."
        return if (track.tracking_url.contains("/movies/")) "movies" else "shows"
    }

    companion object {
        val CLIENT_ID = eu.kanade.tachiyomi.BuildConfig.TRAKT_CLIENT_ID
        private val CLIENT_SECRET = eu.kanade.tachiyomi.BuildConfig.TRAKT_CLIENT_SECRET
        val TMDB_API_KEY = eu.kanade.tachiyomi.BuildConfig.TMDB_API_KEY

        private const val API_URL = "https://api.trakt.tv"
        private const val BASE_URL = "https://trakt.tv"
        private const val REDIRECT_URL = "aniyomi://trakt-auth"

        fun authUrl(): Uri =
            "$BASE_URL/oauth/authorize".toUri().buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URL)
                .build()
    }
}
