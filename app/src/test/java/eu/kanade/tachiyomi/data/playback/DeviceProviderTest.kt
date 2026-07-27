package eu.kanade.tachiyomi.data.playback

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceProviderTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private var storedDeviceId: String? = null

    @BeforeEach
    fun setUp() {
        preferences = mockk()
        editor = mockk()

        every { preferences.getString(any(), any()) } answers { storedDeviceId }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            storedDeviceId = secondArg()
            editor
        }
        every { editor.commit() } returns true
    }

    @Test
    fun `creates and synchronously persists a UUID when no identifier exists`() {
        val deviceId = DeviceProvider(preferences).getDeviceId()

        assertDoesNotThrow { UUID.fromString(deviceId) }
        assertEquals(deviceId, storedDeviceId)
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `reuses the persisted identifier across provider instances`() {
        val firstDeviceId = DeviceProvider(preferences).getDeviceId()

        val secondDeviceId = DeviceProvider(preferences).getDeviceId()

        assertEquals(firstDeviceId, secondDeviceId)
        verify(exactly = 1) { editor.commit() }
    }

    @Test
    fun `caches the identifier after the first access`() {
        val persistedId = UUID.randomUUID().toString()
        storedDeviceId = persistedId
        val provider = DeviceProvider(preferences)

        val firstDeviceId = provider.getDeviceId()
        storedDeviceId = UUID.randomUUID().toString()
        val secondDeviceId = provider.getDeviceId()

        assertEquals(persistedId, firstDeviceId)
        assertEquals(firstDeviceId, secondDeviceId)
        verify(exactly = 1) { preferences.getString(any(), any()) }
    }
}
