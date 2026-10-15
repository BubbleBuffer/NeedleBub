package de.x0bubbuff.needlebub.notifications

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.runtime.RuntimeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRuntimeOutcomeTest {
    @Test
    fun `engine failure wins over a no match status`() {
        assertEquals(
            "ENGINE_TRUNCATED",
            NotificationRuntimeOutcome.failureReason(response(
                status = "NO_MATCH",
                engineSuccess = false,
                engineErrorCode = "truncated",
            )),
        )
    }

    @Test
    fun `broker errors remain specific and clean no match is not an error`() {
        assertEquals(
            ErrorCodes.RUNTIME_CRASH,
            NotificationRuntimeOutcome.failureReason(response(
                status = "ERROR",
                errorCode = ErrorCodes.RUNTIME_CRASH,
            )),
        )
        assertNull(NotificationRuntimeOutcome.failureReason(response(status = "NO_MATCH")))
        assertNull(NotificationRuntimeOutcome.failureReason(response(
            status = "NO_MATCH",
            errorCode = ErrorCodes.NO_MATCH,
        )))
    }

    private fun response(
        status: String,
        errorCode: String? = null,
        engineSuccess: Boolean? = true,
        engineErrorCode: String? = null,
    ) = RuntimeResponse(
        requestId = "request",
        status = status,
        toolName = null,
        resultJson = null,
        errorCode = errorCode,
        durationMs = 1,
        coldLoad = false,
        pssKb = 1,
        engineSuccess = engineSuccess,
        engineErrorCode = engineErrorCode,
    )
}
