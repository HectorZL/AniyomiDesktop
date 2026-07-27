package eu.kanade.tachiyomi.data.playback

import eu.kanade.tachiyomi.data.database.models.PlaybackProgress
import eu.kanade.tachiyomi.data.database.models.PlaybackStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PlaybackSessionValidatorTest {

    private val validator = PlaybackSessionValidator()

    // ==================== isValidPosition() Tests ====================

    @Test
    fun `isValidPosition returns true for valid position within duration`() {
        val result = validator.isValidPosition(positionMs = 5000L, durationMs = 10000L)
        assertTrue(result)
    }

    @Test
    fun `isValidPosition returns true for position at zero`() {
        val result = validator.isValidPosition(positionMs = 0L, durationMs = 10000L)
        assertTrue(result)
    }

    @Test
    fun `isValidPosition returns true for position equal to duration`() {
        val result = validator.isValidPosition(positionMs = 10000L, durationMs = 10000L)
        assertTrue(result)
    }

    @Test
    fun `isValidPosition returns false for negative position`() {
        val result = validator.isValidPosition(positionMs = -1L, durationMs = 10000L)
        assertFalse(result)
    }

    @Test
    fun `isValidPosition returns false for position exceeding duration`() {
        val result = validator.isValidPosition(positionMs = 15000L, durationMs = 10000L)
        assertFalse(result)
    }

    @Test
    fun `isValidPosition returns false for zero or negative duration`() {
        assertFalse(validator.isValidPosition(positionMs = 5000L, durationMs = 0L))
        assertFalse(validator.isValidPosition(positionMs = 5000L, durationMs = -1L))
    }

    // ==================== isSessionExpired() Tests ====================

    @Test
    fun `isSessionExpired returns false for recent timestamp`() {
        val now = System.currentTimeMillis()
        val lastUpdateTime = now - (1 * 60 * 1000L) // 1 minute ago
        
        val result = validator.isSessionExpired(lastUpdateTime, now)
        assertFalse(result)
    }

    @Test
    fun `isSessionExpired returns false for timestamp exactly at expiry threshold`() {
        val now = System.currentTimeMillis()
        val lastUpdateTime = now - PlaybackSessionValidator.SESSION_EXPIRY_MS
        
        val result = validator.isSessionExpired(lastUpdateTime, now)
        assertFalse(result)
    }

    @Test
    fun `isSessionExpired returns true for timestamp beyond expiry threshold`() {
        val now = System.currentTimeMillis()
        val lastUpdateTime = now - (3 * 60 * 1000L) // 3 minutes ago (> 2 minutes)
        
        val result = validator.isSessionExpired(lastUpdateTime, now)
        assertTrue(result)
    }

    @Test
    fun `isSessionExpired returns true for very old timestamp`() {
        val now = System.currentTimeMillis()
        val lastUpdateTime = now - (24 * 60 * 60 * 1000L) // 24 hours ago
        
        val result = validator.isSessionExpired(lastUpdateTime, now)
        assertTrue(result)
    }

    // ==================== isValidProgress() Tests ====================

    @Test
    fun `isValidProgress returns true for valid progress with IN_PROGRESS status`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = 5000L,
            durationMs = 10000L,
            lastUpdateTime = now - 60000L, // 1 minute ago
            status = PlaybackStatus.IN_PROGRESS
        )
        
        val result = validator.isValidProgress(progress)
        assertTrue(result)
    }

    @Test
    fun `isValidProgress returns true for valid progress with PAUSED status`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = 5000L,
            durationMs = 10000L,
            lastUpdateTime = now - 60000L,
            status = PlaybackStatus.PAUSED
        )
        
        val result = validator.isValidProgress(progress)
        assertTrue(result)
    }

    @Test
    fun `isValidProgress returns false for progress with CORRUPTED status`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = 5000L,
            durationMs = 10000L,
            lastUpdateTime = now - 60000L,
            status = PlaybackStatus.CORRUPTED
        )
        
        val result = validator.isValidProgress(progress)
        assertFalse(result)
    }

    @Test
    fun `isValidProgress returns false for expired session`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = 5000L,
            durationMs = 10000L,
            lastUpdateTime = now - (3 * 60 * 1000L), // 3 minutes ago (expired)
            status = PlaybackStatus.IN_PROGRESS
        )
        
        val result = validator.isValidProgress(progress)
        assertFalse(result)
    }

    @Test
    fun `isValidProgress returns false for invalid position`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = 15000L, // exceeds duration
            durationMs = 10000L,
            lastUpdateTime = now - 60000L,
            status = PlaybackStatus.IN_PROGRESS
        )
        
        val result = validator.isValidProgress(progress)
        assertFalse(result)
    }

    @Test
    fun `isValidProgress returns false for negative position`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = -1L,
            durationMs = 10000L,
            lastUpdateTime = now - 60000L,
            status = PlaybackStatus.IN_PROGRESS
        )
        
        val result = validator.isValidProgress(progress)
        assertFalse(result)
    }

    @Test
    fun `isValidProgress returns false when all conditions fail`() {
        val now = System.currentTimeMillis()
        val progress = createPlaybackProgress(
            positionMs = -1L, // invalid position
            durationMs = 10000L,
            lastUpdateTime = now - (3 * 60 * 1000L), // expired
            status = PlaybackStatus.CORRUPTED
        )
        
        val result = validator.isValidProgress(progress)
        assertFalse(result)
    }

    // ==================== shouldShowResumeDialog() Tests ====================

    @Test
    fun `shouldShowResumeDialog returns true for position greater than threshold`() {
        val result = validator.shouldShowResumeDialog(positionMs = 15000L)
        assertTrue(result)
    }

    @Test
    fun `shouldShowResumeDialog returns false for position equal to threshold`() {
        val result = validator.shouldShowResumeDialog(
            positionMs = PlaybackSessionValidator.RESUME_THRESHOLD_MS
        )
        assertFalse(result)
    }

    @Test
    fun `shouldShowResumeDialog returns false for position below threshold`() {
        val result = validator.shouldShowResumeDialog(positionMs = 5000L)
        assertFalse(result)
    }

    @Test
    fun `shouldShowResumeDialog returns false for zero position`() {
        val result = validator.shouldShowResumeDialog(positionMs = 0L)
        assertFalse(result)
    }

    // ==================== adjustPositionIfNeeded() Tests ====================

    @Test
    fun `adjustPositionIfNeeded returns same position when within bounds`() {
        val result = validator.adjustPositionIfNeeded(positionMs = 5000L, durationMs = 10000L)
        assertEquals(5000L, result)
    }

    @Test
    fun `adjustPositionIfNeeded returns 90 percent of duration when position exceeds`() {
        val durationMs = 10000L
        val result = validator.adjustPositionIfNeeded(positionMs = 15000L, durationMs = durationMs)
        
        val expected = (durationMs * 0.90).toLong()
        assertEquals(expected, result)
    }

    @Test
    fun `adjustPositionIfNeeded returns same position when duration is zero`() {
        val result = validator.adjustPositionIfNeeded(positionMs = 5000L, durationMs = 0L)
        assertEquals(5000L, result)
    }

    @Test
    fun `adjustPositionIfNeeded returns same position when duration is negative`() {
        val result = validator.adjustPositionIfNeeded(positionMs = 5000L, durationMs = -1L)
        assertEquals(5000L, result)
    }

    @Test
    fun `adjustPositionIfNeeded handles edge case at exactly duration`() {
        val result = validator.adjustPositionIfNeeded(positionMs = 10000L, durationMs = 10000L)
        assertEquals(10000L, result)
    }

    // ==================== Helper Methods ====================

    private fun createPlaybackProgress(
        positionMs: Long,
        durationMs: Long,
        lastUpdateTime: Long,
        status: PlaybackStatus,
        episodeId: String = "test-episode-123",
        deviceId: String = "test-device-456"
    ): PlaybackProgress {
        return PlaybackProgress(
            id = UUID.randomUUID().toString(),
            episodeId = episodeId,
            positionMs = positionMs,
            durationMs = durationMs,
            lastUpdateTime = lastUpdateTime,
            deviceId = deviceId,
            status = status,
            version = 1
        )
    }
}
