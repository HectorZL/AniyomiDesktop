package tachiyomi.domain.playback.interactor

import tachiyomi.domain.playback.repository.PlaybackProgressRepository

class DeletePlaybackProgress(
    private val repository: PlaybackProgressRepository,
) {

    suspend fun await(episodeId: Long, deviceId: String) {
        repository.deleteProgress(episodeId, deviceId)
    }
}
