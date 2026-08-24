package de.x0bubbuff.needlebub.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePolicyTest {
    @Test
    fun `releases the isolated runtime after five idle seconds`() {
        assertEquals(5_000L, RuntimePolicy.IDLE_RELEASE_MS)
    }
}
