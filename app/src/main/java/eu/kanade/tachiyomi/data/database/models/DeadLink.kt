package eu.kanade.tachiyomi.data.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

private const val DEAD_LINK_VALIDITY_MILLIS: Long = 24 * 60 * 60 * 1000L

/**
 * A URL that failed validation and should be skipped until its short-lived cache entry expires.
 *
 * Dead links are intentionally keyed by their URL so inserting a newer failure replaces the
 * previous expiry for that URL. The default expiry is exactly 24 hours after [addedAt].
 */
@Entity(
    tableName = "dead_links",
    indices = [
        Index(value = ["expires_at"]),
    ],
)
data class DeadLink(
    @PrimaryKey
    val url: String,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long = addedAt + DEAD_LINK_VALIDITY_MILLIS,
) {
    companion object {
        const val VALIDITY_MILLIS: Long = DEAD_LINK_VALIDITY_MILLIS
    }
}
