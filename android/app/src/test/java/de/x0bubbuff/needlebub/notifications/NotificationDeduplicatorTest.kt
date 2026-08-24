package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDeduplicatorTest {
    @Test
    fun `deduplicates content updates by package key and hash until ttl`() {
        var now = 100L
        val deduplicator = NotificationDeduplicator(ttlMs = 1_000L, clockMs = { now })
        assertTrue(deduplicator.shouldProcess("app", "key", "code 123456"))
        assertFalse(deduplicator.shouldProcess("app", "key", "code 123456"))
        assertTrue(deduplicator.shouldProcess("app", "key", "code 654321"))
        now += 1_001L
        assertTrue(deduplicator.shouldProcess("app", "key", "code 123456"))
    }
}
