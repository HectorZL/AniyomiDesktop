package eu.kanade.tachiyomi.data.playback

import eu.kanade.tachiyomi.data.database.models.PlaybackProgress
import eu.kanade.tachiyomi.data.database.models.PlaybackStatus

/**
 * Pure validation rules for playback progress records.
 *
 * Kept free of storage and coroutine concerns so the rules are simple to reason about
 * independently of [PlaybackProgressManager].
 */
class PlaybackSessionValidator {

    /** A position is only meaningful when it falls within the video's duration. */
    fun isValidPosition(positionMs: Long, durationMs: Long): Boolean {
        return positionMs >= 0 && durationMs > 0 && positionMs <= durationMs
    }

    /** A saved position older than [SESSION_EXPIRY_MS] is treated as stale and discarded. */
    fun isSessionExpired(lastUpdateTime: Long, now: Long = System.currentTimeMillis()): Boolean {
        return now - lastUpdateTime > SESSION_EXPIRY_MS
    }

    /**
     * Validates that a playback progress record is in a valid state.
     *
     * Checks that:
     * - The position is valid (within bounds of duration)
     * - The session is not expired
     * - The status is not CORRUPTED
     */
    fun isValidProgress(progress: PlaybackProgress): Boolean {
        return isValidPosition(progress.positionMs, progress.durationMs) &&
            !isSessionExpired(progress.lastUpdateTime) &&
            progress.status != PlaybackStatus.CORRUPTED
    }

    /** Whether a saved position is old enough to be worth offering as a resume point. */
    fun shouldShowResumeDialog(positionMs: Long): Boolean {
        return positionMs > RESUME_THRESHOLD_MS
    }

    /**
     * Clamps a saved position that exceeds the (possibly updated) video duration.
     *
     * This can happen if the source reports a slightly different duration between sessions.
     */
    fun adjustPositionIfNeeded(positionMs: Long, durationMs: Long): Long {
        return if (durationMs > 0 && positionMs > durationMs) {
            (durationMs * DURATION_OVERFLOW_FRACTION).toLong()
        } else {
            positionMs
        }
    }

    companion object {
        const val RESUME_THRESHOLD_MS = 10_000L
        const val SESSION_EXPIRY_MS = 2 * 60 * 1000L
        private const val DURATION_OVERFLOW_FRACTION = 0.90
    }
}
