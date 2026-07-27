package eu.kanade.tachiyomi.data.database.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeadLinkTest {

    @Test
    fun `default expiration is exactly 24 hours after added time`() {
        val addedAt = 1_700_000_000_000L

        val deadLink = DeadLink(
            url = "https://example.com/stream.m3u8",
            addedAt = addedAt,
        )

        assertEquals(DeadLink.VALIDITY_MILLIS, deadLink.expiresAt - deadLink.addedAt)
    }
}
