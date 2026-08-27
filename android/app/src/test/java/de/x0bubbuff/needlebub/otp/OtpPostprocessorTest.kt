package de.x0bubbuff.needlebub.otp

import org.junit.Assert.assertEquals
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
            OtpOutcome.Accepted(OtpResult("A7B9Q", "Needle Bank"), SourceDisposition.GROUNDED),
            OtpPostprocessor.process(query, """[{"name":"extract_otp","arguments":{"code":"A7B9Q","source":"Needle Bank"}}]"""),
        )
    }

    @Test
    fun `reports exact rejection reasons`() {
        assertEquals(
            OtpOutcome.Rejected(OtpReason.PROMOTIONAL_CONTEXT),
            OtpPostprocessor.process("Message: promo code SAVE20", """[{"name":"extract_otp","arguments":{"code":"SAVE20"}}]"""),
        )
        assertEquals(
            OtpOutcome.Rejected(OtpReason.TRACKING_CONTEXT),
            OtpPostprocessor.process("Message: tracking reference ABC123", """[{"name":"extract_otp","arguments":{"code":"ABC123"}}]"""),
        )
        assertEquals(
            OtpOutcome.Rejected(OtpReason.CODE_NOT_GROUNDED),
            OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"654321"}}]"""),
        )
        assertEquals(
            OtpOutcome.Rejected(OtpReason.MODEL_MULTIPLE_CALLS),
            OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"123456"}},{"name":"extract_otp","arguments":{"code":"123456"}}]"""),
        )
    }

    @Test
    fun `drops an ungrounded source without dropping a grounded code`() {
        assertEquals(
            OtpOutcome.Accepted(OtpResult("123456", null), SourceDisposition.DROPPED_UNGROUNDED),
            OtpPostprocessor.process("Message: code 123456", """[{"name":"extract_otp","arguments":{"code":"123456","source":"Unknown"}}]"""),
        )
    }

    @Test
    fun `rejects codes grounded only in structural sender or label`() {
        assertEquals(
            OtpOutcome.Rejected(OtpReason.CODE_NOT_GROUNDED),
            OtpPostprocessor.process(
                "Sender: Noah\nMessage: See you at lunch.",
                """[{"name":"extract_otp","arguments":{"code":"Noah"}}]""",
            ),
        )
        assertEquals(
            OtpOutcome.Rejected(OtpReason.CODE_NOT_GROUNDED),
            OtpPostprocessor.process(
                "Sender: YouTube\nMessage: Avoiding Bot Detection by User0332",
                """[{"name":"extract_otp","arguments":{"code":"Message"}}]""",
            ),
        )
    }

    @Test
    fun `matches the original amount and delivery fixtures`() {
        assertEquals(
            OtpOutcome.Rejected(OtpReason.CODE_NOT_GROUNDED),
            OtpPostprocessor.process("Payment of EUR 165.60 was received.", """[{"name":"extract_otp","arguments":{"code":"16560"}}]"""),
        )
        assertEquals(
            OtpOutcome.Accepted(OtpResult("4821", null), SourceDisposition.ABSENT),
            OtpPostprocessor.process("Your delivery code is 4821.", """[{"name":"extract_otp","arguments":{"code":"4821"}}]"""),
        )
    }
}
