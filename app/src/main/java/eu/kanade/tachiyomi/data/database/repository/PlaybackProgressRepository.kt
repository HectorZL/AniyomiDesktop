package eu.kanade.tachiyomi.data.database.repository

import eu.kanade.tachiyomi.data.database.dao.PlaybackProgressDao
import eu.kanade.tachiyomi.data.database.models.PlaybackProgress
import eu.kanade.tachiyomi.data.database.models.PlaybackStatus
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

/**
 * Repository for managing playback progress persistence with atomic transactions.
 *
 * This repository wraps [PlaybackProgressDao] operations and provides
 * business logic such as session validation and completion marking.
 */
class PlaybackProgressRepository(
    private val dao: PlaybackProgressDao,
) {

    /**
     * Saves playback progress atomically, handling failures gracefully.
     *
     * This operation uses [PlaybackProgressDao.updateProgressAtomic] which runs in a transaction
     * to ensure atomic writes. If the write fails, the exception is logged and rethrown
     * to allow the caller to retry.
     *
     * @param episodeId The episode identifier
     * @param deviceId The device identifier
     * @param positionMs Current playback position in milliseconds
     * @param durationMs Total video duration in milliseconds
     * @param status Playback status (defaults to IN_PROGRESS)
     * @throws PlaybackStorageException if the save operation fails
     */
    suspend fun saveProgress(
        episodeId: String,
        deviceId: String,
        positionMs: Long,
        durationMs: Long,
        status: PlaybackStatus = PlaybackStatus.IN_PROGRESS,
    ) {
        try {
            val progress = PlaybackProgress(
                id = UUID.randomUUID().toString(),
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                lastUpdateTime = System.currentTimeMillis(),
                deviceId = deviceId,
                status = status,
                version = 1,
            )
            // updateProgressAtomic is already annotated with @Transaction
            dao.updateProgressAtomic(progress)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to save playback progress for episode $episodeId: ${e.message}"
            }
            throw PlaybackStorageException("Failed to save progress", e)
        }
    }

    /**
     * Retrieves playback progress for an episode on a specific device.
     *
     * This method validates that the session is not expired (> 2 minutes old).
     * If the session is expired, it marks the progress as EXPIRED and returns null.
     *
     * @param episodeId The episode identifier
     * @param deviceId The device identifier
     * @return The playback progress if valid, null if not found or expired
     */
    suspend fun getProgressForEpisode(
        episodeId: String,
        deviceId: String,
    ): PlaybackProgress? {
        return try {
            val progress = dao.getProgressForEpisode(episodeId, deviceId)
            
            if (progress == null) {
                return null
            }

            // Validate session is not expired (> 2 minutes)
            val now = System.currentTimeMillis()
            val ageMs = now - progress.lastUpdateTime
            val isExpired = ageMs > SESSION_EXPIRY_MS

            if (isExpired) {
                logcat(LogPriority.DEBUG) {
                    "Playback session expired for episode $episodeId (age: ${ageMs / 1000}s)"
                }
                // Mark as expired in database
                markAsExpired(progress.id)
                return null
            }

            // Validate data integrity
            if (progress.status == PlaybackStatus.CORRUPTED) {
                logcat(LogPriority.WARN) {
                    "Corrupted playback progress detected for episode $episodeId"
                }
                return null
            }

            progress
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to get playback progress for episode $episodeId: ${e.message}"
            }
            null
        }
    }

    /**
     * Marks an episode as completed when it reaches 95% of playback.
     *
     * This updates the status to COMPLETED. The cleanup logic can later delete
     * completed progress entries.
     *
     * @param episodeId The episode identifier
     * @param deviceId The device identifier
     */
    suspend fun markAsCompleted(episodeId: String, deviceId: String) {
        try {
            val progress = dao.getProgressForEpisode(episodeId, deviceId)
            if (progress != null) {
                val completed = progress.copy(
                    status = PlaybackStatus.COMPLETED,
                    lastUpdateTime = System.currentTimeMillis(),
                )
                // updateProgressAtomic is already annotated with @Transaction
                dao.updateProgressAtomic(completed)
                logcat(LogPriority.DEBUG) {
                    "Marked episode $episodeId as completed"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to mark episode $episodeId as completed: ${e.message}"
            }
            // Don't rethrow - marking as completed is not critical
        }
    }

    /**
     * Marks a playback progress entry as expired.
     *
     * @param progressId The progress entry identifier
     */
    suspend fun markAsExpired(progressId: String) {
        try {
            val progress = dao.getProgressById(progressId)
            if (progress != null) {
                val expired = progress.copy(
                    status = PlaybackStatus.EXPIRED,
                    lastUpdateTime = System.currentTimeMillis(),
                )
                // updateProgressAtomic is already annotated with @Transaction
                dao.updateProgressAtomic(expired)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to mark progress $progressId as expired: ${e.message}"
            }
        }
    }

    /**
     * Marks a playback progress entry as corrupted.
     *
     * @param progressId The progress entry identifier
     */
    suspend fun markAsCorrupted(progressId: String) {
        try {
            val progress = dao.getProgressById(progressId)
            if (progress != null) {
                val corrupted = progress.copy(
                    status = PlaybackStatus.CORRUPTED,
                    lastUpdateTime = System.currentTimeMillis(),
                )
                // updateProgressAtomic is already annotated with @Transaction
                dao.updateProgressAtomic(corrupted)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to mark progress $progressId as corrupted: ${e.message}"
            }
        }
    }

    /**
     * Deletes completed playback progress entries.
     *
     * This can be called periodically to clean up old completed entries.
     *
     * @param episodeId The episode identifier
     * @return Number of entries deleted
     */
    suspend fun deleteCompletedProgress(episodeId: String): Int {
        return try {
            dao.deleteCompletedProgress(episodeId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) {
                "Failed to delete completed progress for episode $episodeId: ${e.message}"
            }
            0
        }
    }

    companion object {
        /** Session expiry time in milliseconds (2 minutes) */
        private const val SESSION_EXPIRY_MS = 2 * 60 * 1000L
    }
}

/**
 * Exception thrown when playback storage operations fail.
 */
class PlaybackStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
