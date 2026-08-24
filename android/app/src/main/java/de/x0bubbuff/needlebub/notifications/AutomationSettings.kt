package de.x0bubbuff.needlebub.notifications

import android.content.Context

class AutomationSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("automation", Context.MODE_PRIVATE)

    var allApps: Boolean
        get() = preferences.getBoolean("all_apps", false)
        set(value) { preferences.edit().putBoolean("all_apps", value).apply() }

    var selectedPackages: Set<String>
        get() = preferences.getStringSet("selected_packages", emptySet()).orEmpty().toSet()
        set(value) { preferences.edit().putStringSet("selected_packages", value.toSet()).apply() }

    fun accepts(packageName: String): Boolean = allApps || packageName in selectedPackages
}
