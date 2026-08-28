package de.x0bubbuff.needlebub.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Modifier

class FeatureActivityLedgerTest {
    @Test
    fun recordsContentFreeOutcomesAndComputesSevenDaySummary() {
        val ledger = FeatureActivityLedger(retentionDays = 7)
        var buckets = emptyList<FeatureActivityBucket>()

        buckets = ledger.record(buckets, epochDay = 100, occurredAt = 10_000, decision = "OTP", durationMs = 700)
        buckets = ledger.record(buckets, epochDay = 100, occurredAt = 11_000, decision = "REJECTED", durationMs = 500)
        buckets = ledger.record(buckets, epochDay = 100, occurredAt = 12_000, decision = "ERROR", durationMs = null)
        buckets = ledger.record(buckets, epochDay = 99, occurredAt = 9_000, decision = "NOT_RUN", durationMs = null)
        val summary = ledger.summary("otp", buckets, todayEpochDay = 100, days = 7)

        assertEquals(1, summary.todayOtp)
        assertEquals(1, summary.todayRejected)
        assertEquals(1, summary.todayErrors)
        assertEquals(0, summary.todayNotRun)
        assertEquals(1, summary.totalNotRun)
        assertEquals(2, summary.completedInferenceCount)
        assertEquals(600L, summary.averageDurationMs)
        assertEquals(12_000L, summary.lastActivityAt)
    }

    @Test
    fun prunesBucketsOutsideTheRollingWindowAndIgnoresPendingOutcomes() {
        val ledger = FeatureActivityLedger(retentionDays = 7)
        val stale = FeatureActivityBucket(epochDay = 93, otp = 9, lastActivityAt = 1)
        var buckets = listOf(stale)

        buckets = ledger.record(buckets, epochDay = 100, occurredAt = 20_000, decision = "PENDING", durationMs = 900)
        val summary = ledger.summary("otp", buckets, todayEpochDay = 100, days = 7)

        assertEquals(0, summary.totalOtp)
        assertEquals(0, summary.completedInferenceCount)
        assertNull(summary.averageDurationMs)
        assertNull(summary.lastActivityAt)
        assertEquals(emptyList<FeatureActivityBucket>(), buckets)
    }

    @Test
    fun persistedBucketSchemaCannotContainNotificationOrInferenceContent() {
        val fields = FeatureActivityBucket::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(setOf(
            "epochDay",
            "otp",
            "rejected",
            "errors",
            "suppressed",
            "notRun",
            "completedInferenceCount",
            "durationTotalMs",
            "lastActivityAt",
        ), fields)
    }
}
