package de.x0bubbuff.needlebub.updates

import android.content.Context

data class PackUpdateStatus(
    val enabled: Boolean,
    val allowMetered: Boolean,
    val state: String,
    val currentVersion: String?,
    val availableVersion: String?,
    val lastCheckedAt: Long?,
    val lastUpdatedAt: Long?,
    val lastError: String?,
)

class PackUpdateState(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pack_updates", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    var allowMetered: Boolean
        get() = preferences.getBoolean("allow_metered", false)
        set(value) { preferences.edit().putBoolean("allow_metered", value).apply() }

    fun read(currentVersion: String?): PackUpdateStatus = PackUpdateStatus(
        enabled = enabled,
        allowMetered = allowMetered,
        state = preferences.getString("state", "idle") ?: "idle",
        currentVersion = currentVersion,
        availableVersion = preferences.getString("available_version", null),
        lastCheckedAt = preferences.getLong("last_checked_at", 0L).takeIf { it > 0 },
        lastUpdatedAt = preferences.getLong("last_updated_at", 0L).takeIf { it > 0 },
        lastError = preferences.getString("last_error", null),
    )

    fun record(
        state: String,
        availableVersion: String? = null,
        error: String? = null,
        checked: Boolean = false,
        updated: Boolean = false,
    ) {
        preferences.edit()
            .putString("state", state)
            .apply {
                if (availableVersion == null) remove("available_version") else putString("available_version", availableVersion)
                if (error == null) remove("last_error") else putString("last_error", error)
                if (checked) putLong("last_checked_at", System.currentTimeMillis())
                if (updated) putLong("last_updated_at", System.currentTimeMillis())
            }
            .apply()
    }
}
