package de.x0bubbuff.needlebub.notifications

/** Why an accepted code was or was not placed on the clipboard. */
enum class OtpCopyDecision {
    COPY,
    SKIP_NOT_ACCEPTED,
    SKIP_SUPERSEDED,
    SKIP_AUTOMATION_PAUSED,
    SKIP_AUTO_COPY_DISABLED,
    SKIP_LOCKED,
    SKIP_DUPLICATE,
    ;

    val copies: Boolean get() = this == COPY
}

/**
 * Delivery-time policy for auto-copy.
 *
 * Everything is rechecked at the moment the code is delivered: a setting changed or a
 * device locked while inference was running suppresses the copy instead of replaying it
 * later. Nothing is queued for unlock.
 *
 * Deduplication is on the notification item and code pair over a bounded recent window and
 * covers both copied and already delivered pairs, so an interleaved repeat (A, B, A) never
 * copies twice and a code that was delivered while the phone was locked is not copied later
 * by a revision. A genuinely new code on the same item stays eligible.
 */
object OtpCopyPolicy {
    /**
     * Identity used to deduplicate a copy.
     *
     * Ordinary notifications derive their item identity from the text, so an edit that keeps
     * the same code would look like a new item and could replay a code missed while locked.
     * They are therefore deduplicated on the stable lifecycle identity instead. Messaging and
     * inbox notifications keep their item identity, because two genuinely different messages
     * may carry different codes that must both stay eligible.
     */
    fun copyIdentity(
        lifecycleKey: String,
        itemKey: String,
        kind: NotificationExtractionKind,
    ): String = when (kind) {
        NotificationExtractionKind.ORDINARY, NotificationExtractionKind.POLICY -> lifecycleKey
        NotificationExtractionKind.MESSAGING, NotificationExtractionKind.INBOX -> "$lifecycleKey:$itemKey"
    }

    fun decide(
        accepted: Boolean,
        fresh: Boolean,
        automationEnabled: Boolean,
        autoCopyEnabled: Boolean,
        unlocked: Boolean,
        candidate: OtpDeliveryRecord,
        alreadyHandled: List<OtpDeliveryRecord>,
    ): OtpCopyDecision = when {
        !accepted -> OtpCopyDecision.SKIP_NOT_ACCEPTED
        !fresh -> OtpCopyDecision.SKIP_SUPERSEDED
        !automationEnabled -> OtpCopyDecision.SKIP_AUTOMATION_PAUSED
        !autoCopyEnabled -> OtpCopyDecision.SKIP_AUTO_COPY_DISABLED
        !unlocked -> OtpCopyDecision.SKIP_LOCKED
        alreadyHandled.any { it == candidate } -> OtpCopyDecision.SKIP_DUPLICATE
        else -> OtpCopyDecision.COPY
    }

    /**
     * The result notification is deduplicated on the item and code pair too: an identical
     * revision is not re-posted, a changed code still is.
     */
    fun shouldPublish(candidate: OtpDeliveryRecord, recentlyDelivered: List<OtpDeliveryRecord>): Boolean =
        recentlyDelivered.none { it == candidate }
}
