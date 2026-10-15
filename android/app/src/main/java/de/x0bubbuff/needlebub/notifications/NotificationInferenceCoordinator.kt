package de.x0bubbuff.needlebub.notifications

import de.x0bubbuff.needlebub.runtime.RuntimeAdmission
import de.x0bubbuff.needlebub.runtime.RuntimeDeadline
import de.x0bubbuff.needlebub.runtime.RuntimeDeadlineScheduler
import de.x0bubbuff.needlebub.runtime.ThreadedDeadlineScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class NotificationInferenceWork<T>(
    val notificationKey: String,
    val dispatch: ((T) -> Unit) -> RuntimeAdmission,
    val onResult: (T) -> Unit,
    val onSuperseded: () -> Unit,
    val onBackpressure: () -> Unit,
    /** The work waited longer than the pending budget without being dispatched. */
    val onExpired: () -> Unit = onSuperseded,
    /**
     * Checked immediately before dispatch. A notification that was removed or revised
     * while it waited is dropped instead of being inferred from stale content. Must be a
     * cheap, non-blocking read: it runs on the dispatching thread.
     */
    val isFresh: () -> Boolean = { true },
)

/**
 * Coalesces notification revisions around the runtime.
 *
 * The pending queue is bounded twice: by [maxPendingKeys] and by [pendingTimeoutMs], so a
 * runtime that never answers can no longer leave queued revisions waiting forever.
 * Superseding, expiry and staleness all end in exactly one callback for the affected work.
 *
 * Capacity counts work until its terminal callback has finished, not until it leaves the
 * queue, so a blocked callback cannot let more work in than the bound allows and no accepted
 * outcome is ever dropped to make room. Every drain step re-checks the identity of the
 * queued work it snapshotted, and a failing freshness predicate is contained and treated as
 * stale, so neither a replaced revision nor a throwing predicate can stall draining.
 */
class NotificationInferenceCoordinator<T>(
    private val maxPendingKeys: Int = 8,
    private val pendingTimeoutMs: Long = DEFAULT_PENDING_TIMEOUT_MS,
    private val scheduler: RuntimeDeadlineScheduler = ThreadedDeadlineScheduler(),
    private val callbackExecutor: ExecutorService = newCallbackExecutor(),
) {
    private class Pending<T>(val work: NotificationInferenceWork<T>, val deadline: RuntimeDeadline)

    private val pending = LinkedHashMap<String, Pending<T>>()

    /**
     * Work whose terminal callback is queued or running.
     *
     * Compared by identity: a replacement for the same notification key is different work.
     */
    private val terminating = ArrayList<NotificationInferenceWork<T>>()
    private var active: NotificationInferenceWork<T>? = null

    init {
        require(maxPendingKeys > 0)
        require(pendingTimeoutMs > 0)
    }

    fun submit(work: NotificationInferenceWork<T>) {
        var start: NotificationInferenceWork<T>? = null
        var superseded: NotificationInferenceWork<T>? = null
        var backpressure = false
        synchronized(this) {
            val outstanding = pending.size + terminating.size + if (active == null) 0 else 1
            if (outstanding >= maxPendingKeys + 1) {
                // Replacements also add a terminal outcome for the old revision. They
                // obey this bound even while the active slot is temporarily empty.
                backpressure = true
            } else if (active == null) {
                active = work
                start = work
            } else {
                val existing = pending[work.notificationKey]
                if (existing != null) {
                    existing.deadline.cancel()
                    pending[work.notificationKey] = Pending(work, scheduleExpiry(work.notificationKey, work))
                    // The replaced revision keeps its slot until its own callback is done.
                    terminating += existing.work
                    superseded = existing.work
                } else {
                    pending[work.notificationKey] = Pending(work, scheduleExpiry(work.notificationKey, work))
                }
            }
        }
        superseded?.let { stale -> dispatchTerminal(stale) { stale.onSuperseded() } }
        if (backpressure) {
            safely { work.onBackpressure() }
            return
        }
        start?.let(::dispatch)
    }

    /** Stops the pending deadlines; the owning service is going away. */
    fun shutdown() {
        val waiting = synchronized(this) {
            val works = pending.values.map { it.work }
            pending.values.forEach { it.deadline.cancel() }
            pending.clear()
            works.forEach { terminating += it }
            works
        }
        waiting.forEach { work -> dispatchTerminal(work) { work.onExpired() } }
        scheduler.shutdown()
        callbackExecutor.shutdown()
    }

    private fun scheduleExpiry(key: String, work: NotificationInferenceWork<T>): RuntimeDeadline =
        scheduler.schedule(pendingTimeoutMs) {
            val expired = synchronized(this) {
                val entry = pending[key]
                if (entry?.work !== work) {
                    null
                } else {
                    pending.remove(key)
                    terminating += work
                    entry.work
                }
            }
            // Never run a caller callback on the deadline thread: it may write to the
            // database and would otherwise delay every other deadline.
            expired?.let { expiredWork -> dispatchTerminal(expiredWork) { expiredWork.onExpired() } }
        }

    private fun dispatch(work: NotificationInferenceWork<T>) {
        val admission = try {
            work.dispatch { result -> complete(work, result) }
        } catch (_: Throwable) {
            RuntimeAdmission.QUEUE_FULL
        }
        if (admission == RuntimeAdmission.QUEUE_FULL) {
            synchronized(this) {
                if (active === work) active = null
                terminating += work
            }
            dispatchTerminal(work) { work.onBackpressure() }
            drain()
        }
    }

    private fun complete(work: NotificationInferenceWork<T>, result: T) {
        val accepted = synchronized(this) {
            if (active !== work) {
                false
            } else {
                active = null
                terminating += work
                true
            }
        }
        if (!accepted) return
        try {
            safely { work.onResult(result) }
        } finally {
            releaseTerminal(work)
        }
    }

    private fun drain() {
        while (true) {
            val candidate = synchronized(this) {
                if (active != null || pending.isEmpty()) return
                pending.entries.first()
            }
            val work = candidate.value.work
            // Freshness is checked outside the lock: the predicate may read service state,
            // and a throwing predicate is contained as "not fresh" instead of stalling.
            if (!isFresh(work)) {
                val removed = synchronized(this) {
                    val entry = pending[candidate.key]
                    if (entry?.work !== work) {
                        null
                    } else {
                        pending.remove(candidate.key)
                        terminating += work
                        entry
                    }
                }
                if (removed != null) {
                    removed.deadline.cancel()
                    dispatchTerminal(work) { work.onSuperseded() }
                }
                continue
            }
            val started = synchronized(this) {
                val entry = pending[candidate.key]
                if (active != null || entry == null || entry.work !== work) {
                    null
                } else {
                    pending.remove(candidate.key)
                    entry.deadline.cancel()
                    active = entry.work
                    entry.work
                }
            }
            if (started == null) continue
            dispatch(started)
            return
        }
    }

    private fun isFresh(work: NotificationInferenceWork<T>): Boolean = try {
        work.isFresh()
    } catch (_: Throwable) {
        // Unknown freshness fails closed, exactly like a removed notification.
        false
    }

    /**
     * Runs a terminal callback and releases the work's capacity when it finishes.
     *
     * The caller must already have added [work] to [terminating] under the lock.
     */
    private fun dispatchTerminal(work: NotificationInferenceWork<T>, callback: () -> Unit) {
        try {
            callbackExecutor.execute {
                try {
                    safely(callback)
                } finally {
                    releaseTerminal(work)
                }
            }
        } catch (_: RejectedExecutionException) {
            releaseTerminal(work)
        }
    }

    private fun releaseTerminal(work: NotificationInferenceWork<T>) {
        val released = synchronized(this) { terminating.removeAll { it === work } }
        if (released) drain()
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // A failing caller callback must never stall the queue.
        }
    }

    private companion object {
        const val DEFAULT_PENDING_TIMEOUT_MS = 5_000L

        fun newCallbackExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "NeedleNotificationCallbacks").apply { isDaemon = true }
            }
    }
}
