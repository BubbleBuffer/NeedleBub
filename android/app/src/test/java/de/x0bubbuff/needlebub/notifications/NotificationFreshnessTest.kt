package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revision freshness: a key match alone is not enough, and unknown freshness fails closed.
 */
class NotificationFreshnessTest {
    @Test
    fun `old and future dated notifications are not eligible for automatic copy`() {
        assertTrue(NotificationFreshness.isRecent(1_000L, 121_000L))
        assertFalse(NotificationFreshness.isRecent(1_000L, 121_001L))
        assertFalse(NotificationFreshness.isRecent(2_000L, 1_000L))
    }

    private val key = "0|com.example.sender|1|null|10001"

    @Test
    fun `the posted revision with the same post time is current`() {
        assertTrue(NotificationFreshness.isCurrentRevision(listOf(key to 42L), key, 42L))
    }

    @Test
    fun `a superseded revision of the same key is not current`() {
        assertFalse(NotificationFreshness.isCurrentRevision(listOf(key to 43L), key, 42L))
    }

    @Test
    fun `a removed notification is not current`() {
        assertFalse(NotificationFreshness.isCurrentRevision(emptyList(), key, 42L))
    }

    @Test
    fun `an unreadable freshness query fails closed`() {
        assertFalse(NotificationFreshness.isCurrentRevision(null, key, 42L))
    }

    @Test
    fun `another notification with the same post time is not a match`() {
        assertFalse(NotificationFreshness.isCurrentRevision(listOf("other" to 42L), key, 42L))
    }
}
