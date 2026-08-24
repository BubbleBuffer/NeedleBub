package de.x0bubbuff.needlebub.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpPostprocessorTest {
    @Test
    fun `formats the measured query exactly`() {
        assertEquals(
            "Sender: Needle Bank\nMessage: Your code is A7B9Q",
            OtpPostprocessor.formatQuery("Needle Bank", "Your code is A7B9Q"),
        )
    }

    @Test
    fun `accepts one grounded tool call`() {
        val query = OtpPostprocessor.formatQuery("Needle Bank", "Your code is A7B9Q")
        assertEquals(
            OtpResult("A7B9Q", "Needle Bank"),
            OtpPostprocessor.process(query, """[{"name":"extract_otp","arguments":{"code":"A7B9Q","source":"Needle Bank"}}]"""),
        )
    }

    @Test
    fun `rejects promotional tracking multiple and ungrounded calls`() {
        assertNull(OtpPostprocessor.process("Message: promo code SAVE20", """[{"name":"extract_otp","arguments":{"code":"SAVE20"}}]"""))
        assertNull(OtpPostprocessor.process("Message: tracking reference ABC123", """[{"name":"extract_otp","arguments":{"code":"ABC123"}}]"""))
        assertNull(OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"654321"}}]"""))
        assertNull(OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"123456"}},{"name":"extract_otp","arguments":{"code":"123456"}}]"""))
    }

    @Test
    fun `drops an ungrounded source without dropping a grounded code`() {
        assertEquals(
            OtpResult("123456", null),
            OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"123456","source":"Unknown"}}]"""),
        )
    }

    @Test
    fun `matches the original amount and delivery fixtures`() {
        assertNull(OtpPostprocessor.process("Payment of EUR 165.60 was received.", """[{"name":"extract_otp","arguments":{"code":"16560"}}]"""))
        assertEquals(
            OtpResult("4821", null),
            OtpPostprocessor.process("Your delivery code is 4821.", """[{"name":"extract_otp","arguments":{"code":"4821"}}]"""),
        )
    }
}
