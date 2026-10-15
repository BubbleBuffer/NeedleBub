package de.x0bubbuff.needlebub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge payload the web layer reads. The auto-copy toggle must be present and must
 * follow the persisted setting instead of a hardcoded default.
 */
class PluginStatusJsonTest {
    private fun status(autoCopyEnabled: Boolean) = NeedleBubPlugin().statusJson(
        packInstalled = true,
        notificationAccess = true,
        notificationPermission = true,
        allApps = false,
        selectedAppCount = 2,
        excludedAppCount = 3,
        configured = true,
        automaticOtpEnabled = true,
        autoCopyEnabled = autoCopyEnabled,
        dark = false,
        macroDroidInstalled = true,
    )

    @Test
    fun `the status payload reports auto copy off by default`() {
        assertTrue(status(autoCopyEnabled = false).has("autoCopyEnabled"))
        assertFalse(status(autoCopyEnabled = false).getBoolean("autoCopyEnabled"))
    }

    @Test
    fun `the status payload reports the persisted auto copy setting`() {
        assertTrue(status(autoCopyEnabled = true).getBoolean("autoCopyEnabled"))
    }

    @Test
    fun `the status payload keeps the existing keys`() {
        val json = status(autoCopyEnabled = true)
        listOf(
            "otpPackInstalled", "notificationAccess", "notificationPermission", "allApps",
            "selectedAppCount", "excludedAppCount", "automaticOtpConfigured", "automaticOtpEnabled", "autoCopyEnabled",
            "colorScheme", "macroDroidInstalled",
        ).forEach { key -> assertTrue("missing $key", json.has(key)) }
        assertEquals("light", json.getString("colorScheme"))
        assertEquals(2, json.getInt("selectedAppCount"))
        assertEquals(3, json.getInt("excludedAppCount"))
    }
}
