package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun `media navigation progress and service notifications skip the model`() {
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_NAVIGATION,
            NotificationInferencePolicy.decision("Turn left", "navigation", false, null),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_MEDIA,
            NotificationInferencePolicy.decision("Playing", "transport", false, null),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_MEDIA,
            NotificationInferencePolicy.decision("Playing", "msg", true, null),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_MEDIA,
            NotificationInferencePolicy.decision(
                "Playing",
                "msg",
                false,
                "android.app.Notification\$MediaStyle",
            ),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_PROGRESS,
            NotificationInferencePolicy.decision("Download 50%", "progress", false, null),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_PROGRESS,
            NotificationInferencePolicy.decision(
                "Download 50%",
                "msg",
                false,
                "android.app.Notification\$ProgressStyle",
            ),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_PROGRESS,
            NotificationInferencePolicy.decision(
                "Download 50%",
                "msg",
                false,
                null,
                progressMax = 100,
            ),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_PROGRESS,
            NotificationInferencePolicy.decision(
                "Downloading",
                "msg",
                false,
                null,
                progressIndeterminate = true,
            ),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_ONGOING_SERVICE,
            NotificationInferencePolicy.decision(
                "Protection active",
                "msg",
                false,
                null,
                flags = android.app.Notification.FLAG_FOREGROUND_SERVICE,
            ),
        )
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_ONGOING_SERVICE,
            NotificationInferencePolicy.decision(
                "Protection active",
                "service",
                false,
                null,
                flags = android.app.Notification.FLAG_ONGOING_EVENT,
            ),
        )
        assertFalse(NotificationInferencePolicy.shouldInfer(
            body = "Playing",
            category = "transport",
            hasMediaSession = false,
            template = null,
        ))
        assertTrue(NotificationInferencePolicy.shouldInfer("Backup finished", "service", false, null))
    }

    @Test
    fun `blank notifications skip while ordinary status notifications remain eligible`() {
        assertEquals(
            NotificationInferencePolicy.EXCLUDED_BLANK,
            NotificationInferencePolicy.decision("", "msg", false, null),
        )
        assertTrue(NotificationInferencePolicy.shouldInfer("System status", "sys", false, null))
    }
}
