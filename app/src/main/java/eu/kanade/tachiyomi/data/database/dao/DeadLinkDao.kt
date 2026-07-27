package eu.kanade.tachiyomi.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import eu.kanade.tachiyomi.data.database.models.DeadLink

/** Room access object for the temporary dead-link cache. */
@Dao
interface DeadLinkDao {

    /**
     * Stores a failed URL, replacing a previous entry for the same URL so its expiry is renewed.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeadLink(deadLink: DeadLink)

    /**
     * Returns the URL only while its 24-hour validity window is still open.
     *
     * Filtering in the query prevents expired entries from being treated as dead links even when
     * the periodic cleanup has not run yet.
     */
    @Query(
        """
        SELECT * FROM dead_links
        WHERE url = :url
          AND expires_at > :now
        LIMIT 1
        """,
    )
    suspend fun getDeadLink(
        url: String,
        now: Long = System.currentTimeMillis(),
    ): DeadLink?

    /** Returns whether [url] is currently cached as a dead link. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM dead_links
            WHERE url = :url
              AND expires_at > :now
        )
        """,
    )
    suspend fun isDeadLink(
        url: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean

    /** Removes expired entries and returns the number of rows deleted. */
    @Query("DELETE FROM dead_links WHERE expires_at <= :now")
    suspend fun deleteExpired(
        now: Long = System.currentTimeMillis(),
    ): Int

    /** Removes one URL from the dead-link cache. */
    @Query("DELETE FROM dead_links WHERE url = :url")
    suspend fun deleteDeadLink(url: String)
}
