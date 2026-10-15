package de.x0bubbuff.needlebub.runtime

/**
 * Admission and deadline policy for the isolated inference runtime.
 *
 * Every wait a request can enter is bounded here: queue wait, bind wait and inference
 * time. Deadlines are enforced by [RuntimeDeadlineScheduler], never by the main looper.
 */
object RuntimePolicy {
    /** How long a warm runtime is kept resident after the last request settles. */
    const val IDLE_RELEASE_MS = 5_000L

    /** Accepted-but-unfinished requests, counting the one currently in flight. */
    const val MAX_QUEUE = 8

    /** Upper bound for `bindService` plus `onServiceConnected`. */
    const val BIND_TIMEOUT_MS = 3_000L

    /** Bounds applied to a caller supplied inference budget. */
    const val MIN_TIMEOUT_MS = 250L
    const val MAX_TIMEOUT_MS = 30_000L

    /** Surfaces accepted from callers; anything else is reported as `gateway`. */
    val SURFACES = setOf("notification", "gateway", "macro", "check")

    fun inferenceTimeoutMs(requested: Long): Long = requested.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    fun surface(requested: String): String = requested.takeIf { it in SURFACES } ?: "gateway"

    /**
     * Queue and bind wait are bounded by the caller's own inference budget, so a request
     * can never wait forever for a runtime that is not coming. This does not extend the
     * inference budget itself: the model still gets the full requested timeout once the
     * request is dispatched.
     */
    fun queueWaitMs(timeoutMs: Long): Long = timeoutMs
}
