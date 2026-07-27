package eu.kanade.tachiyomi.data.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Persisted playback state for one episode on one device.
 *
 * The compound index supports loading the newest progress entry for an episode on the current
 * device, while [lastUpdateTime] supports chronological cleanup.
 */
@Entity(
    tableName = "playback_progress",
    indices = [
        Index(value = ["episode_id", "device_id"]),
        Index(value = ["last_update_time"]),
    ],
)
data class PlaybackProgress(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    @ColumnInfo(name = "position_ms")
    val positionMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "last_update_time")
    val lastUpdateTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "status")
    val status: PlaybackStatus = PlaybackStatus.IN_PROGRESS,

    @ColumnInfo(name = "version")
    val version: Int = 1,
)

enum class PlaybackStatus {
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    EXPIRED,
    CORRUPTED,
}
