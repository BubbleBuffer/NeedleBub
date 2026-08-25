package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInferencePolicyTest {
    @Test
    fun `ordinary non-empty notifications reach the model without a code-shaped token`() {
        assertTrue(NotificationInferencePolicy.shouldInfer(
            body = "Sign-in request opened",
            category = "msg",
            hasMediaSession = false,
            template = null,
        ))
    }

    @Test
    fun `youtube playback notifications never reach the model`() {
        assertFalse(NotificationInferencePolicy.shouldInfer(
            body = "User0332",
            category = "transport",
            hasMediaSession = true,
            template = "android.app.Notification\$MediaStyle",
        ))
    }

    @Test
    fun `blank system and progress notifications do not reach the model`() {
        assertFalse(NotificationInferencePolicy.shouldInfer("", "msg", false, null))
        assertFalse(NotificationInferencePolicy.shouldInfer("System status", "sys", false, null))
        assertFalse(NotificationInferencePolicy.shouldInfer("Download 50%", "progress", false, null))
    }
}
