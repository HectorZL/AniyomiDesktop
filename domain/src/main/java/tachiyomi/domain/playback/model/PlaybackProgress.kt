package tachiyomi.domain.playback.model

/**
 * The last known playback position for an episode on a specific device.
 *
 * Records are scoped by [episodeId] and [deviceId] so that progress saved on one device never
 * automatically resumes playback on another device.
 */
data class PlaybackProgress(
    val episodeId: Long,
    val deviceId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdateTime: Long,
)
