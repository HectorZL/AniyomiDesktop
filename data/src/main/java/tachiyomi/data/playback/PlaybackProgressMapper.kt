package tachiyomi.data.playback

import tachiyomi.domain.playback.model.PlaybackProgress

object PlaybackProgressMapper {
    fun map(
        id: Long,
        episodeId: Long,
        deviceId: String,
        positionMs: Long,
        durationMs: Long,
        lastUpdateTime: Long,
    ): PlaybackProgress = PlaybackProgress(
        episodeId = episodeId,
        deviceId = deviceId,
        positionMs = positionMs,
        durationMs = durationMs,
        lastUpdateTime = lastUpdateTime,
    )
}
