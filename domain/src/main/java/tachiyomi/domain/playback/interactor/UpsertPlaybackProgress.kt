package tachiyomi.domain.playback.interactor

import tachiyomi.domain.playback.model.PlaybackProgress
import tachiyomi.domain.playback.repository.PlaybackProgressRepository

class UpsertPlaybackProgress(
    private val repository: PlaybackProgressRepository,
) {

    suspend fun await(progress: PlaybackProgress) {
        repository.upsertProgress(progress)
    }
}
