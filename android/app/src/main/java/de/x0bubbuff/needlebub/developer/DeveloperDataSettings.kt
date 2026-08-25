package de.x0bubbuff.needlebub.developer

import android.content.Context

class DeveloperDataSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("developer_data", Context.MODE_PRIVATE)

    var unlocked: Boolean
        get() = preferences.getBoolean("unlocked", false)
        set(value) { preferences.edit().putBoolean("unlocked", value).apply() }

    var captureEnabled: Boolean
        get() = unlocked && preferences.getBoolean("capture_enabled", false)
        set(value) { preferences.edit().putBoolean("capture_enabled", value && unlocked).apply() }
}
