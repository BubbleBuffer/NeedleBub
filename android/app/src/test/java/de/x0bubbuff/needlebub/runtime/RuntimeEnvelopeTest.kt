package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEnvelopeTest {
    @Test
    fun `call envelope with code and source maps to an OK extract_otp call`() {
        val envelope = parse(
            """{"type":"call","success":true,"function_calls":[{"name":"extract_otp","arguments":{"code":"482913","source":"ExampleBank"}}]}""",
        )

        assertEquals("OK", envelope.status)
        assertEquals("extract_otp", envelope.toolName)
        assertEquals(1, envelope.callCount)
        assertNull(envelope.errorCode)
        assertFalse(envelope.truncated == true)
        val arguments = JSONObject(envelope.resultJson!!)
        assertEquals("482913", arguments.getString("code"))
        assertEquals("ExampleBank", arguments.getString("source"))
    }

    @Test
    fun `call without the optional source passes only the code argument`() {
        val envelope = parse(
            """{"type":"call","success":true,"function_calls":[{"name":"extract_otp","arguments":{"code":"0042"}}]}""",
        )

        assertEquals("OK", envelope.status)
        assertEquals("extract_otp", envelope.toolName)
        val arguments = JSONObject(envelope.resultJson!!)
        assertEquals("0042", arguments.getString("code"))
        assertFalse(arguments.has("source"))
    }

    @Test
    fun `no-call envelopes map to NO_MATCH without a tool`() {
        val empty = parse("""{"type":"text","success":true,"function_calls":[]}""")
        val absent = parse("""{"type":"text","success":true}""")

        for (envelope in listOf(empty, absent)) {
            assertEquals("NO_MATCH", envelope.status)
            assertEquals(ErrorCodes.NO_MATCH, envelope.errorCode)
            assertNull(envelope.toolName)
            assertNull(envelope.resultJson)
        }
        assertEquals(0, empty.callCount)
    }

    @Test
    fun `call with missing arguments is reported as OUTPUT_TRUNCATED`() {
        val envelope = parse("""{"type":"call","success":true,"function_calls":[{"name":"extract_otp"}]}""")

        assertEquals("ERROR", envelope.status)
        assertEquals(ErrorCodes.OUTPUT_TRUNCATED, envelope.errorCode)
        assertNull(envelope.resultJson)
        assertTrue(envelope.truncated == true)
    }

    private fun parse(envelope: String): RuntimeEnvelope = RuntimeEnvelope.parse(envelope, maxReasoningChars = 200)
}
