package eu.kanade.tachiyomi.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import eu.kanade.tachiyomi.data.database.models.PlaybackProgress

/** Room access object for playback progress records. */
@Dao
interface PlaybackProgressDao {

    /**
     * Stores [progress], replacing an existing record with the same primary key atomically.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: PlaybackProgress)

    /**
     * Returns the most recently updated progress for [episodeId] on [deviceId].
     */
    @Query(
        """
        SELECT * FROM playback_progress
        WHERE episode_id = :episodeId
          AND device_id = :deviceId
        ORDER BY last_update_time DESC
        LIMIT 1
        """,
    )
    suspend fun getProgressForEpisode(
        episodeId: String,
        deviceId: String,
    ): PlaybackProgress?

    /** Removes a specific playback-progress record. */
    @Delete
    suspend fun deleteProgress(progress: PlaybackProgress)

    /**
     * Returns a progress entry by its unique ID.
     */
    @Query("SELECT * FROM playback_progress WHERE id = :progressId LIMIT 1")
    suspend fun getProgressById(progressId: String): PlaybackProgress?

    /**
     * Deletes completed progress entries for a specific episode.
     */
    @Query(
        """
        DELETE FROM playback_progress
        WHERE episode_id = :episodeId
          AND status = 'COMPLETED'
        """,
    )
    suspend fun deleteCompletedProgress(episodeId: String): Int

    /**
     * Replaces [progress] as one transaction, preventing callers from observing a partial update.
     */
    @Transaction
    suspend fun updateProgressAtomic(progress: PlaybackProgress) {
        deleteProgress(progress)
        insertProgress(progress)
    }
}
