package de.x0bubbuff.needlebub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContractsTest {
    @Test
    fun `internal response carries cold load and memory evidence`() {
        val response = RuntimeResponse(
            requestId = "request",
            status = "OK",
            toolName = "extract_otp",
            resultJson = null,
            errorCode = null,
            durationMs = 716L,
            coldLoad = true,
            pssKb = 60_058L,
        )

        assertTrue(response.coldLoad)
        assertEquals(60_058L, response.pssKb)
    }
}
