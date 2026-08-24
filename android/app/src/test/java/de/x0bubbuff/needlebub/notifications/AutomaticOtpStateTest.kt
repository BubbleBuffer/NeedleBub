package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticOtpStateTest {
    @Test
    fun `configured requires the pack permissions and a source boundary`() {
        assertTrue(AutomaticOtpState.configured(true, true, true, true))
        assertFalse(AutomaticOtpState.configured(false, true, true, true))
        assertFalse(AutomaticOtpState.configured(true, false, true, true))
        assertFalse(AutomaticOtpState.configured(true, true, false, true))
        assertFalse(AutomaticOtpState.configured(true, true, true, false))
    }

    @Test
    fun `paused automation neither inspects notifications nor publishes an in-flight result`() {
        assertFalse(AutomaticOtpState.mayInspectNotification(false, false, true))
        assertFalse(AutomaticOtpState.mayPublishResult(false))
        assertTrue(AutomaticOtpState.mayInspectNotification(true, false, true))
        assertTrue(AutomaticOtpState.mayPublishResult(true))
    }
}
