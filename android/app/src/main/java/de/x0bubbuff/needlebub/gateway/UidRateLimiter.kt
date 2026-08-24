package de.x0bubbuff.needlebub.gateway

import java.util.ArrayDeque

enum class RateDecision { ALLOWED, BUSY, RATE_LIMITED }

class UidRateLimiter(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class State(
        var tokens: Double = BURST_CAPACITY,
        var lastRefillMs: Long,
        var inFlight: Boolean = false,
        val window: ArrayDeque<Long> = ArrayDeque(),
    )

    private val states = mutableMapOf<Int, State>()

    @Synchronized
    fun acquire(uid: Int): RateDecision {
        val now = clockMs()
        val state = states.getOrPut(uid) { State(lastRefillMs = now) }
        if (state.inFlight) return RateDecision.BUSY

        refill(state, now)
        while (state.window.isNotEmpty() && state.window.first() <= now - WINDOW_MS) {
            state.window.removeFirst()
        }
        if (state.window.size >= WINDOW_LIMIT || state.tokens < 1.0) return RateDecision.RATE_LIMITED

        state.tokens -= 1.0
        state.window.addLast(now)
        state.inFlight = true
        return RateDecision.ALLOWED
    }

    @Synchronized
    fun finish(uid: Int) {
        states[uid]?.inFlight = false
    }

    private fun refill(state: State, now: Long) {
        val elapsed = (now - state.lastRefillMs).coerceAtLeast(0)
        state.tokens = (state.tokens + elapsed * REFILL_PER_MS).coerceAtMost(BURST_CAPACITY)
        state.lastRefillMs = now
    }

    private companion object {
        const val BURST_CAPACITY = 3.0
        const val WINDOW_LIMIT = 10
        const val WINDOW_MS = 60_000L
        const val REFILL_PER_MS = WINDOW_LIMIT.toDouble() / WINDOW_MS
    }
}
