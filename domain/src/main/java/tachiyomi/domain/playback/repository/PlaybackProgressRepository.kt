package tachiyomi.domain.playback.repository

import tachiyomi.domain.playback.model.PlaybackProgress

interface PlaybackProgressRepository {

    suspend fun getProgress(episodeId: Long, deviceId: String): PlaybackProgress?

    suspend fun upsertProgress(progress: PlaybackProgress)

    suspend fun deleteProgress(episodeId: Long, deviceId: String)
}
