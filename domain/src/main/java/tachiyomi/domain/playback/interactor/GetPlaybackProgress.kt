package tachiyomi.domain.playback.interactor

import tachiyomi.domain.playback.model.PlaybackProgress
import tachiyomi.domain.playback.repository.PlaybackProgressRepository

class GetPlaybackProgress(
    private val repository: PlaybackProgressRepository,
) {

    suspend fun await(episodeId: Long, deviceId: String): PlaybackProgress? {
        return repository.getProgress(episodeId, deviceId)
    }
}
