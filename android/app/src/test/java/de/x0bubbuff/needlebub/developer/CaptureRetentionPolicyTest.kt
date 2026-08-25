package de.x0bubbuff.needlebub.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRetentionPolicyTest {
    @Test
    fun `expires records after thirty days`() {
        val now = 1_800_000_000_000L
        assertTrue(CaptureRetentionPolicy.expired(now - CaptureRetentionPolicy.MAX_AGE_MS - 1, now))
        assertFalse(CaptureRetentionPolicy.expired(now - CaptureRetentionPolicy.MAX_AGE_MS, now))
    }

    @Test
    fun `prunes oldest records beyond ten thousand`() {
        assertTrue(CaptureRetentionPolicy.excessCount(10_004) == 4)
        assertTrue(CaptureRetentionPolicy.excessCount(9_999) == 0)
    }
}
