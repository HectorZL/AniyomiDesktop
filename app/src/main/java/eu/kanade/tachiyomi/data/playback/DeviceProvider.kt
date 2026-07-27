package eu.kanade.tachiyomi.data.playback

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Provides the identifier used to isolate playback progress per app installation.
 *
 * The identifier is created on first access rather than derived from hardware so it does not
 * expose device information. It is stored synchronously in app-private preferences, which keeps
 * it available after process termination and system reboots while allowing a reinstall to create
 * a new identifier.
 */
class DeviceProvider internal constructor(
    private val preferences: SharedPreferences,
) {

    constructor(context: Context) : this(
        (context.applicationContext ?: context).getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Returns the UUID for this app installation, creating and persisting it when necessary.
     *
     * The first call loads the persisted value (or creates it synchronously), then keeps the
     * result in memory so subsequent calls do not access disk-backed preferences.
     */
    fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        return synchronized(creationLock) {
            cachedDeviceId?.let { return@synchronized it }

            val deviceId = preferences.getString(DEVICE_ID_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    val generatedId = UUID.randomUUID().toString()
                    val persisted = preferences.edit()
                        .putString(DEVICE_ID_KEY, generatedId)
                        .commit()

                    check(persisted) {
                        "Unable to persist the playback device identifier"
                    }
                    generatedId
                }

            cachedDeviceId = deviceId
            deviceId
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "playback_progress"
        const val DEVICE_ID_KEY = "device_id"

        val creationLock = Any()
    }
}
