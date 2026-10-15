package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourcePolicyTest {
    @Test
    fun `all apps allows everything when exclusions are empty including legacy settings`() {
        assertTrue(NotificationSourcePolicy.accepts("de.bank", true, emptySet(), emptySet()))
        assertTrue(NotificationSourcePolicy.accepts("de.chat", true, setOf("de.bank"), emptySet()))
    }

    @Test
    fun `all apps excludes only blocked packages regardless of saved allowlist`() {
        val selected = setOf("de.bank")
        val excluded = setOf("de.bank", "de.chat")
        assertFalse(NotificationSourcePolicy.accepts("de.bank", true, selected, excluded))
        assertFalse(NotificationSourcePolicy.accepts("de.chat", true, selected, excluded))
        assertTrue(NotificationSourcePolicy.accepts("de.mail", true, selected, excluded))
    }

    @Test
    fun `only selected mode ignores dormant exclusions`() {
        val selected = setOf("de.bank")
        val excluded = setOf("de.bank", "de.chat")
        assertTrue(NotificationSourcePolicy.accepts("de.bank", false, selected, excluded))
        assertFalse(NotificationSourcePolicy.accepts("de.chat", false, selected, excluded))
        assertFalse(NotificationSourcePolicy.accepts("de.mail", false, selected, excluded))
    }
}
