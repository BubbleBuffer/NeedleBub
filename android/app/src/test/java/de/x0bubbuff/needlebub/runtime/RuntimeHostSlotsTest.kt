package de.x0bubbuff.needlebub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slot lifecycle at the host seam, driven deterministically.
 *
 * The platform reports a connection on the main thread while the broker binds and unbinds
 * on its control thread; a connection that arrives after the release must be dropped.
 */
class RuntimeHostSlotsTest {
    @Test
    fun `a binder attached after release is refused and never reinserted`() {
        val slots = RuntimeHostSlots<String, String>()
        slots.open(1)
        slots.release(1)

        assertFalse(slots.attach(1, "stale-binder"))
        assertNull(slots.runtime(1))
        assertEquals(0, slots.size())
    }

    @Test
    fun `a released slot drops its binder and connection`() {
        val slots = RuntimeHostSlots<String, String>()
        slots.open(2)
        assertTrue(slots.setConnection(2, "connection"))
        assertTrue(slots.attach(2, "binder"))

        val released = slots.release(2)

        assertEquals("connection", released?.connection)
        assertTrue(released!!.released)
        assertNull(released.runtime)
        assertNull(slots.runtime(2))
        assertFalse(slots.isLive(2))
    }

    @Test
    fun `release is idempotent so the connection is unbound once`() {
        val slots = RuntimeHostSlots<String, String>()
        slots.open(3)
        slots.setConnection(3, "connection")

        assertEquals("connection", slots.release(3)?.connection)
        assertNull(slots.release(3))
    }

    @Test
    fun `an open generation is not tracked twice`() {
        val slots = RuntimeHostSlots<String, String>()
        assertTrue(slots.open(4) != null)
        assertNull(slots.open(4))
        assertEquals(1, slots.size())
    }

    @Test
    fun `a new generation after a release is fully usable`() {
        val slots = RuntimeHostSlots<String, String>()
        slots.open(5)
        slots.release(5)

        assertTrue(slots.open(6) != null)
        assertTrue(slots.setConnection(6, "connection-6"))
        assertTrue(slots.attach(6, "binder-6"))
        assertTrue(slots.isLive(6))
        assertEquals("binder-6", slots.runtime(6))
    }

    @Test
    fun `a late connection for a released generation cannot revive it`() {
        val slots = RuntimeHostSlots<String, String>()
        slots.open(7)
        slots.release(7)

        // The platform reports the connection late, and the stale binder is refused.
        assertFalse(slots.setConnection(7, "late-connection"))
        assertFalse(slots.attach(7, "late-binder"))
        assertNull(slots.release(7))
    }

    @Test
    fun `concurrent attaches and releases never leave a stale binder readable`() {
        val slots = RuntimeHostSlots<String, String>()
        val generations = 64
        val threads = ArrayList<Thread>()
        for (generation in 0 until generations) {
            slots.open(generation)
            slots.attach(generation, "binder-$generation")
            threads += Thread { slots.release(generation) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        for (generation in 0 until generations) {
            assertNull("generation $generation must not expose a binder", slots.runtime(generation))
            assertFalse(slots.isLive(generation))
        }
        assertEquals(0, slots.size())
    }
}
