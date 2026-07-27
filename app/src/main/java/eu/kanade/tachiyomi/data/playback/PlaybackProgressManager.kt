package eu.kanade.tachiyomi.data.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.playback.interactor.DeletePlaybackProgress
import tachiyomi.domain.playback.interactor.GetPlaybackProgress
import tachiyomi.domain.playback.interactor.UpsertPlaybackProgress
import tachiyomi.domain.playback.model.PlaybackProgress

/**
 * Coordinates saving and restoring per-device playback positions.
 *
 * Position updates arrive very frequently while a video plays (multiple times per second from
 * mpv), so this class throttles writes to at most once every [SAVE_INTERVAL_MS] via a periodic
 * ticker rather than writing on every update. Pausing or closing the player bypasses the ticker
 * and saves immediately. Failed writes are queued in memory and retried until the database is
 * reachable again.
 *
 * This is registered as an app-wide singleton so its retry queue and ticker survive across
 * episode changes within the same process.
 */
class PlaybackProgressManager(
    private val getPlaybackProgress: GetPlaybackProgress,
    private val upsertPlaybackProgress: UpsertPlaybackProgress,
    private val deletePlaybackProgress: DeletePlaybackProgress,
    private val deviceProvider: DeviceProvider,
    private val validator: PlaybackSessionValidator = PlaybackSessionValidator(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val writeMutex = Mutex()

    /** Episodes completed in the current playback session; prevents a final ticker write from recreating them. */
    private val clearedEpisodeIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Updates that failed to persist, keyed by episode id, waiting to be retried. */
    private val pendingUpdates = mutableMapOf<Long, PlaybackProgress>()

    /** Timestamps of recent write failures, used to detect repeated failures. */
    private val recentFailures = mutableListOf<Long>()

    private var tickerJob: Job? = null

    // Written only from the caller's thread (the player's main-thread position callback) and
    // read from the ticker coroutine, so volatility is enough to publish updates safely.
    @Volatile private var activeEpisodeId: Long? = null
    @Volatile private var latestPositionMs: Long = 0
    @Volatile private var latestDurationMs: Long = 0

    private val _persistenceWarnings = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when writes have failed repeatedly, so the UI can show a non-blocking warning. */
    val persistenceWarnings: SharedFlow<Unit> = _persistenceWarnings.asSharedFlow()

    /**
     * Called on every position update from the player. The position is recorded immediately but
     * only written to disk at most once every [SAVE_INTERVAL_MS] while playback continues.
     */
    fun onPlaybackPositionChanged(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (!validator.isValidPosition(positionMs, durationMs)) return

        val previousEpisodeId = activeEpisodeId
        val previousPositionMs = latestPositionMs
        val previousDurationMs = latestDurationMs
        val episodeChanged = previousEpisodeId != episodeId

        latestPositionMs = positionMs
        latestDurationMs = durationMs

        if (episodeChanged) {
            // Returning to an episode begins a fresh playback session, so allow it to be tracked
            // again even if it was completed earlier in this process.
            clearedEpisodeIds.remove(episodeId)

            // An episode can be switched before its next scheduled tick. Save its last observed
            // position once, then start tracking the new episode.
            if (previousEpisodeId != null) {
                scope.launch {
                    persist(previousEpisodeId, previousPositionMs, previousDurationMs)
                }
            }
            activeEpisodeId = episodeId
        }

        // Pausing cancels the ticker for an immediate save. Restart it either when playback
        // resumes for the same episode or when the active episode changed.
        if (episodeChanged || tickerJob?.isActive != true) {
            restartTicker(episodeId)
        }
    }

    private fun restartTicker(episodeId: Long) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (activeEpisodeId != episodeId) return@launch
                persist(episodeId, latestPositionMs, latestDurationMs)
            }
        }
    }

    /** Called when the user pauses playback; the position is captured immediately. */
    fun onPlaybackPaused(episodeId: Long, positionMs: Long, durationMs: Long) {
        tickerJob?.cancel()
        if (!validator.isValidPosition(positionMs, durationMs)) return

        scope.launch { persist(episodeId, positionMs, durationMs) }
    }

    /**
     * Cancels the ticker and saves the given position immediately, suspending until the write
     * (or its failure) completes.
     */
    suspend fun flushPendingProgress(episodeId: Long, positionMs: Long, durationMs: Long) {
        tickerJob?.cancel()
        if (!validator.isValidPosition(positionMs, durationMs)) return

        persist(episodeId, positionMs, durationMs)
    }

    /**
     * Fire-and-forget variant of [flushPendingProgress] for callers that cannot suspend, such as
     * `ViewModel.onCleared`.
     *
     * Uses this manager's own long-lived [scope] rather than the caller's, since a ViewModel's
     * `viewModelScope` is already cancelled by the time `onCleared` runs and would silently drop
     * anything launched on it there.
     */
    fun flushOnTeardown(episodeId: Long, positionMs: Long, durationMs: Long) {
        scope.launch { flushPendingProgress(episodeId, positionMs, durationMs) }
    }

    /**
     * Loads the saved position for this episode on this device, if there's one worth resuming.
     *
     * Returns null when there is nothing to resume: no record, an expired session (untouched for
     * more than [PlaybackSessionValidator.SESSION_EXPIRY_MS]), a corrupted record, or a position
     * too small to be worth a resume prompt. Expired or corrupted records are cleared as a side
     * effect so they don't linger.
     */
    suspend fun loadResumePosition(episodeId: Long): PlaybackProgress? {
        val deviceId = deviceProvider.getDeviceId()
        val progress = getPlaybackProgress.await(episodeId, deviceId) ?: return null

        val isExpired = validator.isSessionExpired(progress.lastUpdateTime)
        val isValid = validator.isValidPosition(progress.positionMs, progress.durationMs)
        if (isExpired || !isValid) {
            deletePlaybackProgress.await(episodeId, deviceId)
            return null
        }

        if (!validator.shouldShowResumeDialog(progress.positionMs)) return null

        return progress.copy(
            positionMs = validator.adjustPositionIfNeeded(progress.positionMs, progress.durationMs),
        )
    }

    /** Removes the saved position, e.g. once an episode is considered watched. */
    fun clearProgress(episodeId: Long) {
        clearedEpisodeIds.add(episodeId)
        if (activeEpisodeId == episodeId) {
            tickerJob?.cancel()
        }
        scope.launch {
            mutex.withLock { pendingUpdates.remove(episodeId) }
            writeMutex.withLock {
                deletePlaybackProgress.await(episodeId, deviceProvider.getDeviceId())
            }
        }
    }

    private suspend fun persist(episodeId: Long, positionMs: Long, durationMs: Long) {
        if (!validator.isValidPosition(positionMs, durationMs)) return

        val progress = PlaybackProgress(
            episodeId = episodeId,
            deviceId = deviceProvider.getDeviceId(),
            positionMs = positionMs,
            durationMs = durationMs,
            lastUpdateTime = System.currentTimeMillis(),
        )

        try {
            val persisted = writeMutex.withLock {
                if (clearedEpisodeIds.contains(episodeId)) {
                    false
                } else {
                    upsertPlaybackProgress.await(progress)
                    true
                }
            }
            if (persisted) {
                mutex.withLock { pendingUpdates.remove(episodeId) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, throwable = e) {
                "Failed to save playback progress for episode $episodeId, queueing retry"
            }
            queueForRetry(progress)
        }
    }

    private fun queueForRetry(progress: PlaybackProgress) {
        scope.launch {
            mutex.withLock {
                pendingUpdates[progress.episodeId] = progress

                val now = System.currentTimeMillis()
                recentFailures.add(now)
                recentFailures.removeAll { now - it > FAILURE_WINDOW_MS }
                if (recentFailures.size >= MIN_FAILURES_FOR_WARNING) {
                    _persistenceWarnings.tryEmit(Unit)
                }

                if (retryJob?.isActive != true) {
                    retryJob = scope.launch { retryUntilDrained() }
                }
            }
        }
    }

    private var retryJob: Job? = null

    private suspend fun retryUntilDrained() {
        while (true) {
            delay(RETRY_DELAY_MS)
            val updates = mutex.withLock { pendingUpdates.values.toList() }
            if (updates.isEmpty()) return

            for (update in updates) {
                try {
                    val persisted = writeMutex.withLock {
                        if (clearedEpisodeIds.contains(update.episodeId)) {
                            false
                        } else {
                            upsertPlaybackProgress.await(update)
                            true
                        }
                    }
                    if (persisted) {
                        mutex.withLock { pendingUpdates.remove(update.episodeId) }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, throwable = e) {
                        "Retry failed for episode ${update.episodeId}"
                    }
                }
            }
        }
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val FAILURE_WINDOW_MS = 30_000L
        private const val MIN_FAILURES_FOR_WARNING = 3
    }
}
