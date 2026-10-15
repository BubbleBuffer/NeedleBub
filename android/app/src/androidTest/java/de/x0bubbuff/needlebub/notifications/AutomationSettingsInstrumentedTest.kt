package de.x0bubbuff.needlebub.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationSettingsInstrumentedTest {
    @Test
    fun exclusionsPersistIndependentlyAndLegacyAllAppsAllowsEverything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
        val hadAllApps = preferences.contains("all_apps")
        val previousAllApps = preferences.getBoolean("all_apps", false)
        val hadSelected = preferences.contains("selected_packages")
        val previousSelected = preferences.getStringSet("selected_packages", emptySet()).orEmpty().toSet()
        val hadExcluded = preferences.contains("excluded_packages")
        val previousExcluded = preferences.getStringSet("excluded_packages", emptySet()).orEmpty().toSet()
        try {
            // Simulate the preferences of an installation before exclusions existed.
            preferences.edit()
                .putBoolean("all_apps", true)
                .putStringSet("selected_packages", setOf("de.bank"))
                .remove("excluded_packages")
                .commit()
            assertEquals(emptySet<String>(), AutomationSettings(context).excludedPackages)
            assertTrue(AutomationSettings(context).accepts("de.chat"))

            AutomationSettings(context).saveSources(true, setOf("de.bank"), setOf("de.chat"))
            val allMode = AutomationSettings(context)
            assertEquals(setOf("de.chat"), allMode.excludedPackages)
            assertFalse(allMode.accepts("de.chat"))
            assertTrue(allMode.accepts("de.bank"))

            allMode.allApps = false
            val onlyMode = AutomationSettings(context)
            assertEquals(setOf("de.chat"), onlyMode.excludedPackages)
            assertTrue(onlyMode.accepts("de.bank"))
            assertFalse(onlyMode.accepts("de.chat"))
            onlyMode.selectedPackages = setOf("de.chat")
            assertTrue(AutomationSettings(context).accepts("de.chat"))
        } finally {
            val editor = preferences.edit()
            if (hadAllApps) editor.putBoolean("all_apps", previousAllApps) else editor.remove("all_apps")
            if (hadSelected) editor.putStringSet("selected_packages", previousSelected) else editor.remove("selected_packages")
            if (hadExcluded) editor.putStringSet("excluded_packages", previousExcluded) else editor.remove("excluded_packages")
            editor.commit()
        }
    }
}
