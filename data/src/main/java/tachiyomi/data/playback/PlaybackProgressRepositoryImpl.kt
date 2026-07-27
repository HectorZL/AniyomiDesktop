package tachiyomi.data.playback

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.playback.model.PlaybackProgress
import tachiyomi.domain.playback.repository.PlaybackProgressRepository

class PlaybackProgressRepositoryImpl(
    private val handler: AnimeDatabaseHandler,
) : PlaybackProgressRepository {

    override suspend fun getProgress(episodeId: Long, deviceId: String): PlaybackProgress? {
        return try {
            handler.awaitOneOrNull {
                playback_progressQueries.getForEpisodeAndDevice(
                    episodeId,
                    deviceId,
                    PlaybackProgressMapper::map,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            null
        }
    }

    override suspend fun upsertProgress(progress: PlaybackProgress) {
        try {
            handler.await {
                playback_progressQueries.upsert(
                    episodeId = progress.episodeId,
                    deviceId = progress.deviceId,
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    lastUpdateTime = progress.lastUpdateTime,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
            throw e
        }
    }

    override suspend fun deleteProgress(episodeId: Long, deviceId: String) {
        try {
            handler.await {
                playback_progressQueries.deleteForEpisodeAndDevice(episodeId, deviceId)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e)
        }
    }
}
