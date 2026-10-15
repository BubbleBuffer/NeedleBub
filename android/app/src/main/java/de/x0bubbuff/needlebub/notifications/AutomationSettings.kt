package de.x0bubbuff.needlebub.notifications

import android.content.Context

class AutomationSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("automation", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    /**
     * Auto-copy is opt-in: codes are only placed on the clipboard after the user turns
     * this on, while the device is unlocked and automation is running.
     */
    var autoCopyEnabled: Boolean
        get() = preferences.getBoolean("auto_copy_enabled", false)
        set(value) { preferences.edit().putBoolean("auto_copy_enabled", value).apply() }

    var allApps: Boolean
        get() = preferences.getBoolean("all_apps", false)
        set(value) { preferences.edit().putBoolean("all_apps", value).apply() }

    var selectedPackages: Set<String>
        get() = preferences.getStringSet("selected_packages", emptySet()).orEmpty().toSet()
        set(value) { preferences.edit().putStringSet("selected_packages", value.toSet()).apply() }

    /** Separate from the allowlist so switching modes does not lose explicit exclusions. */
    var excludedPackages: Set<String>
        get() = preferences.getStringSet("excluded_packages", emptySet()).orEmpty().toSet()
        set(value) { preferences.edit().putStringSet("excluded_packages", value.toSet()).apply() }

    fun saveSources(allApps: Boolean, selectedPackages: Set<String>, excludedPackages: Set<String>) {
        preferences.edit()
            .putBoolean("all_apps", allApps)
            .putStringSet("selected_packages", selectedPackages.toSet())
            .putStringSet("excluded_packages", excludedPackages.toSet())
            .apply()
    }

    fun accepts(packageName: String): Boolean = NotificationSourcePolicy.accepts(
        packageName, allApps, selectedPackages, excludedPackages,
    )
}

internal object NotificationSourcePolicy {
    fun accepts(
        packageName: String,
        allApps: Boolean,
        selectedPackages: Set<String>,
        excludedPackages: Set<String>,
    ): Boolean = if (allApps) packageName !in excludedPackages else packageName in selectedPackages
}
