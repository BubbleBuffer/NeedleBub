package de.x0bubbuff.needlebub.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Handle for a scheduled deadline; [cancel] is idempotent and safe from any thread. */
fun interface RuntimeDeadline {
    fun cancel()
}

/**
 * Deadline source for the runtime broker.
 *
 * The watchdog must not share a thread with anything it supervises, so implementations
 * never use the main looper: a blocked main thread must not be able to stall queue,
 * bind or inference deadlines.
 */
interface RuntimeDeadlineScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): RuntimeDeadline

    fun shutdown()
}

/** Deadlines on a dedicated daemon thread, independent of the main looper. */
class ThreadedDeadlineScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    },
) : RuntimeDeadlineScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): RuntimeDeadline {
        val cancelled = AtomicBoolean(false)
        val future = try {
            executor.schedule(
                { if (!cancelled.get()) action() },
                delayMs.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            return RuntimeDeadline { }
        }
        return RuntimeDeadline {
            cancelled.set(true)
            future.cancel(false)
        }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        const val THREAD_NAME = "NeedleRuntimeDeadline"
    }
}
