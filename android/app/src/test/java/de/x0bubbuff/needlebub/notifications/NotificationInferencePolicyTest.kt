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
    fun `youtube playback notifications reach the model`() {
        assertTrue(NotificationInferencePolicy.shouldInfer(
            body = "User0332",
            category = "transport",
            hasMediaSession = true,
            template = "android.app.Notification\$MediaStyle",
        ))
    }

    @Test
    fun `only blank notifications skip the model`() {
        assertFalse(NotificationInferencePolicy.shouldInfer("", "msg", false, null))
        assertTrue(NotificationInferencePolicy.shouldInfer("System status", "sys", false, null))
        assertTrue(NotificationInferencePolicy.shouldInfer("Download 50%", "progress", false, null))
    }
}
