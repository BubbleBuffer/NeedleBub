package de.x0bubbuff.needlebub.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UidRateLimiterTest {
    @Test
    fun `allows burst three but limits the fourth request`() {
        var now = 10_000L
        val limiter = UidRateLimiter(clockMs = { now })
        assertEquals(RateDecision.ALLOWED, limiter.acquire(42))
        limiter.finish(42)
        assertEquals(RateDecision.ALLOWED, limiter.acquire(42))
        limiter.finish(42)
        assertEquals(RateDecision.ALLOWED, limiter.acquire(42))
        limiter.finish(42)
        assertEquals(RateDecision.RATE_LIMITED, limiter.acquire(42))

        now += 20_001
        assertEquals(RateDecision.ALLOWED, limiter.acquire(42))
    }

    @Test
    fun `permits one in flight request per uid`() {
        val limiter = UidRateLimiter(clockMs = { 10_000L })
        assertEquals(RateDecision.ALLOWED, limiter.acquire(7))
        assertEquals(RateDecision.BUSY, limiter.acquire(7))
        limiter.finish(7)
        assertTrue(limiter.acquire(7) == RateDecision.ALLOWED)
    }

    @Test
    fun `enforces ten requests per minute after burst refills`() {
        var now = 0L
        val limiter = UidRateLimiter(clockMs = { now })
        repeat(10) {
            assertEquals(RateDecision.ALLOWED, limiter.acquire(9))
            limiter.finish(9)
            now += 6_001
        }
        now = 59_999L
        assertEquals(RateDecision.RATE_LIMITED, limiter.acquire(9))
        now = 60_001L
        assertEquals(RateDecision.ALLOWED, limiter.acquire(9))
    }
}
