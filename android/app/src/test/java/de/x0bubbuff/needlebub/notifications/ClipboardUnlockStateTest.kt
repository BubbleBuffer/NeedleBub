package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unlock gate boundary: unlocked-only copying, swipe-only lockscreen included.
 */
class ClipboardUnlockStateTest {
    @Test
    fun `a fully unlocked phone allows the copy gate`() {
        assertTrue(ClipboardHelper.isUnlockedState(userUnlocked = true, keyguardLocked = false, deviceLocked = false))
    }

    @Test
    fun `a swipe-only lockscreen suppresses the copy even though the device is not secure-locked`() {
        assertFalse(ClipboardHelper.isUnlockedState(userUnlocked = true, keyguardLocked = true, deviceLocked = false))
    }

    @Test
    fun `a secure lockscreen suppresses the copy`() {
        assertFalse(ClipboardHelper.isUnlockedState(userUnlocked = true, keyguardLocked = true, deviceLocked = true))
    }

    @Test
    fun `a locked user suppresses the copy before the app can read anything`() {
        assertFalse(ClipboardHelper.isUnlockedState(userUnlocked = false, keyguardLocked = false, deviceLocked = false))
    }

    @Test
    fun `unknown state fails closed`() {
        // A missing platform service is reported as keyguard-locked and device-locked.
        assertFalse(ClipboardHelper.isUnlockedState(userUnlocked = false, keyguardLocked = true, deviceLocked = true))
    }

    @Test
    fun `the policy refuses to copy whenever the unlock gate is closed`() {
        val pair = OtpDeliveryRecord("item-a", "code-111111")
        assertEqualsSkip(OtpCopyDecision.SKIP_LOCKED, unlocked = false, candidate = pair)
        assertEqualsSkip(
            OtpCopyDecision.COPY,
            unlocked = ClipboardHelper.isUnlockedState(true, false, false),
            candidate = pair,
        )
    }

    private fun assertEqualsSkip(
        expected: OtpCopyDecision,
        unlocked: Boolean,
        candidate: OtpDeliveryRecord,
    ) {
        val decision = OtpCopyPolicy.decide(
            accepted = true,
            fresh = true,
            automationEnabled = true,
            autoCopyEnabled = true,
            unlocked = unlocked,
            candidate = candidate,
            alreadyHandled = emptyList(),
        )
        org.junit.Assert.assertEquals(expected, decision)
    }
}
