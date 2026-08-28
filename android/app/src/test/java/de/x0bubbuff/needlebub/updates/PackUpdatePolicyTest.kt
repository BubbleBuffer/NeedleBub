package de.x0bubbuff.needlebub.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class PackUpdatePolicyTest {
    @Test
    fun `never downgrades and respects automatic mobile download policy`() {
        assertEquals(UpdateAction.UP_TO_DATE, PackUpdatePolicy.decide("1.0.0-alpha.2", "1.0.0-alpha.1", false, false))
        assertEquals(UpdateAction.UP_TO_DATE, PackUpdatePolicy.decide("1.0.0-alpha.2", "1.0.0-alpha.2", true, true))
        assertEquals(UpdateAction.WAIT_FOR_WIFI, PackUpdatePolicy.decide("1.0.0-alpha.1", "1.0.0-alpha.2", true, false))
        assertEquals(UpdateAction.DOWNLOAD, PackUpdatePolicy.decide("1.0.0-alpha.1", "1.0.0-alpha.2", true, true))
        assertEquals(UpdateAction.DOWNLOAD, PackUpdatePolicy.decide("1.0.0-alpha.1", "1.0.0-alpha.2", false, false))
        assertEquals(UpdateAction.DOWNLOAD, PackUpdatePolicy.decide(null, "1.0.0-alpha.2", false, false))
    }
}
