package de.x0bubbuff.needlebub.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delivery-time gating for auto-copy. Synthetic pairs only: the policy sees salted hashes
 * and never a notification body or a code.
 */
class OtpCopyPolicyTest {
    private val itemA = OtpDeliveryRecord("item-a", "code-111111")
    private val itemACorrected = OtpDeliveryRecord("item-a", "code-222222")
    private val itemB = OtpDeliveryRecord("item-b", "code-333333")

    private fun decide(
        candidate: OtpDeliveryRecord,
        alreadyHandled: List<OtpDeliveryRecord> = emptyList(),
        accepted: Boolean = true,
        fresh: Boolean = true,
        automationEnabled: Boolean = true,
        autoCopyEnabled: Boolean = true,
        unlocked: Boolean = true,
    ) = OtpCopyPolicy.decide(
        accepted = accepted,
        fresh = fresh,
        automationEnabled = automationEnabled,
        autoCopyEnabled = autoCopyEnabled,
        unlocked = unlocked,
        candidate = candidate,
        alreadyHandled = alreadyHandled,
    )

    @Test
    fun `an accepted fresh code is copied when everything is enabled and unlocked`() {
        assertEquals(OtpCopyDecision.COPY, decide(itemA))
    }

    @Test
    fun `auto copy is off by default so a code is not copied without the setting`() {
        assertEquals(
            OtpCopyDecision.SKIP_AUTO_COPY_DISABLED,
            decide(itemA, autoCopyEnabled = false),
        )
    }

    @Test
    fun `a locked device suppresses the copy and nothing is replayed on unlock`() {
        assertEquals(OtpCopyDecision.SKIP_LOCKED, decide(itemA, unlocked = false))
        // Unlock alone never copies: the decision is only made at delivery time.
        assertEquals(OtpCopyDecision.COPY, decide(itemA, unlocked = true))
    }

    @Test
    fun `pausing automation while inference runs suppresses the delivery`() {
        assertEquals(
            OtpCopyDecision.SKIP_AUTOMATION_PAUSED,
            decide(itemA, automationEnabled = false),
        )
    }

    @Test
    fun `a setting changed between inference start and delivery is honoured`() {
        // Inference started with auto-copy on; the user turns it off before the result
        // arrives, so the delivery recheck must refuse the copy.
        val enabledAtStart = decide(itemA, autoCopyEnabled = true)
        val disabledAtDelivery = decide(itemA, autoCopyEnabled = false)
        assertEquals(OtpCopyDecision.COPY, enabledAtStart)
        assertEquals(OtpCopyDecision.SKIP_AUTO_COPY_DISABLED, disabledAtDelivery)
    }

    @Test
    fun `a removed or superseded notification is not copied`() {
        assertEquals(OtpCopyDecision.SKIP_SUPERSEDED, decide(itemA, fresh = false))
    }

    @Test
    fun `a rejected result is never copied`() {
        assertEquals(OtpCopyDecision.SKIP_NOT_ACCEPTED, decide(itemA, accepted = false))
    }

    @Test
    fun `an interleaved repeat does not copy twice`() {
        // A, B, A within the fresh window: the second A is a duplicate pair.
        val copied = mutableListOf<OtpDeliveryRecord>()
        assertEquals(OtpCopyDecision.COPY, decide(itemA, copied))
        copied += itemA
        assertEquals(OtpCopyDecision.COPY, decide(itemB, copied))
        copied += itemB
        assertEquals(OtpCopyDecision.SKIP_DUPLICATE, decide(itemA, copied))
        assertEquals(OtpCopyDecision.SKIP_DUPLICATE, decide(itemB, copied))
    }

    @Test
    fun `repeated revisions of the same item and code never recopy`() {
        assertEquals(OtpCopyDecision.SKIP_DUPLICATE, decide(itemA, listOf(itemA)))
    }

    @Test
    fun `a genuinely new code on the same item stays eligible`() {
        assertEquals(OtpCopyDecision.COPY, decide(itemACorrected, listOf(itemA)))
    }

    @Test
    fun `a pair that left the fresh window may be copied again`() {
        // The window is enforced by OtpDeliveryMemory; an empty recent list is exactly
        // what a pruned window looks like to the policy.
        assertEquals(OtpCopyDecision.COPY, decide(itemA, emptyList()))
    }

    @Test
    fun `an identical revision is not republished but a changed code is`() {
        assertFalse(OtpCopyPolicy.shouldPublish(itemA, listOf(itemA)))
        assertTrue(OtpCopyPolicy.shouldPublish(itemACorrected, listOf(itemA)))
        assertTrue(OtpCopyPolicy.shouldPublish(itemA, emptyList()))
    }

    @Test
    fun `a duplicate result is still published once when nothing was delivered yet`() {
        val published = OtpCopyPolicy.shouldPublish(itemA, emptyList())
        assertTrue(published)
        assertFalse(OtpCopyPolicy.shouldPublish(itemA, listOf(itemA)))
    }

    @Test
    fun `a code delivered while locked is not copied later by a revision`() {
        // First delivery while the phone was locked: nothing was copied, so the pair is
        // only in the delivered set. A revision of the same message after unlock must not
        // auto-copy the code the user never received.
        val delivered = listOf(itemA)
        assertEquals(
            OtpCopyDecision.SKIP_DUPLICATE,
            decide(itemA, alreadyHandled = delivered, unlocked = true),
        )
    }

    @Test
    fun `a genuinely changed code after an unlock is still copied`() {
        assertEquals(
            OtpCopyDecision.COPY,
            decide(itemACorrected, alreadyHandled = listOf(itemA), unlocked = true),
        )
    }

    @Test
    fun `unknown freshness fails closed for the copy but keeps the manual action`() {
        assertEquals(OtpCopyDecision.SKIP_SUPERSEDED, decide(itemA, fresh = false))
        assertFalse(OtpCopyDecision.SKIP_SUPERSEDED.copies)
    }

    @Test
    fun `an ordinary notification is deduplicated on its stable lifecycle identity`() {
        // Ordinary items hash the text, so an edit that keeps the code produces a different
        // item key. The copy identity must not follow the text.
        val original = OtpCopyPolicy.copyIdentity(
            lifecycleKey = "lifecycle-a",
            itemKey = "ordinary-hash-of-original-text",
            kind = NotificationExtractionKind.ORDINARY,
        )
        val revised = OtpCopyPolicy.copyIdentity(
            lifecycleKey = "lifecycle-a",
            itemKey = "ordinary-hash-of-edited-text",
            kind = NotificationExtractionKind.ORDINARY,
        )
        assertEquals(original, revised)
        assertEquals("lifecycle-a", original)

        // Same code after an edit: the pair is identical, so a code missed while locked is
        // not copied later by the revised text.
        val deliveredWhileLocked = OtpDeliveryRecord(original, "code-111111")
        assertEquals(
            OtpCopyDecision.SKIP_DUPLICATE,
            decide(OtpDeliveryRecord(revised, "code-111111"), alreadyHandled = listOf(deliveredWhileLocked)),
        )

        // A genuinely changed code on the same notification stays eligible.
        assertEquals(
            OtpCopyDecision.COPY,
            decide(OtpDeliveryRecord(revised, "code-222222"), alreadyHandled = listOf(deliveredWhileLocked)),
        )
    }

    @Test
    fun `genuinely distinct messages keep their own copy identity`() {
        val first = OtpCopyPolicy.copyIdentity(
            lifecycleKey = "lifecycle-a",
            itemKey = "messaging-hash-of-message-one",
            kind = NotificationExtractionKind.MESSAGING,
        )
        val second = OtpCopyPolicy.copyIdentity(
            lifecycleKey = "lifecycle-a",
            itemKey = "messaging-hash-of-message-two",
            kind = NotificationExtractionKind.MESSAGING,
        )
        assertFalse("two different messages must stay distinct", first == second)

        // Both messages carry their own code, and both stay copyable.
        assertEquals(OtpCopyDecision.COPY, decide(OtpDeliveryRecord(first, "code-111111")))
        assertEquals(
            OtpCopyDecision.COPY,
            decide(
                OtpDeliveryRecord(second, "code-222222"),
                alreadyHandled = listOf(OtpDeliveryRecord(first, "code-111111")),
            ),
        )
    }
}
