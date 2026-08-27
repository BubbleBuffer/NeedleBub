package de.x0bubbuff.needlebub.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class PackUpdatePolicyTest {
    @Test
    fun `never downgrades and waits for unmetered network before download`() {
        assertEquals(UpdateAction.UP_TO_DATE, PackUpdatePolicy.decide("1.0.0-alpha.2", "1.0.0-alpha.1", false))
        assertEquals(UpdateAction.UP_TO_DATE, PackUpdatePolicy.decide("1.0.0-alpha.2", "1.0.0-alpha.2", false))
        assertEquals(UpdateAction.WAIT_FOR_WIFI, PackUpdatePolicy.decide("1.0.0-alpha.1", "1.0.0-alpha.2", true))
        assertEquals(UpdateAction.DOWNLOAD, PackUpdatePolicy.decide("1.0.0-alpha.1", "1.0.0-alpha.2", false))
        assertEquals(UpdateAction.DOWNLOAD, PackUpdatePolicy.decide(null, "1.0.0-alpha.2", false))
    }
}
