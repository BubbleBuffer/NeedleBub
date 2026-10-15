package de.x0bubbuff.needlebub.notifications

import de.x0bubbuff.needlebub.runtime.RuntimeAdmission
import de.x0bubbuff.needlebub.runtime.RuntimeDeadline
import de.x0bubbuff.needlebub.runtime.RuntimeDeadlineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotificationInferenceCoordinatorTest {
    @Test
    fun `replacements cannot bypass capacity while supersede callback is blocked`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val coordinator = NotificationInferenceCoordinator<String>(maxPendingKeys = 2, scheduler = FakeScheduler())
        var refused = 0
        fun fixture(key: String, block: Boolean = false) = NotificationInferenceWork<String>(
            notificationKey = key, dispatch = { RuntimeAdmission.ACCEPTED }, onResult = {},
            onSuperseded = { if (block) { entered.countDown(); release.await(3, TimeUnit.SECONDS) } },
            onBackpressure = { refused++ },
        )
        try {
            coordinator.submit(fixture("active"))
            coordinator.submit(fixture("same", true))
            coordinator.submit(fixture("same"))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(20) { coordinator.submit(fixture("same")) }
            assertEquals("every replacement needs its own terminal capacity", 20, refused)
        } finally { release.countDown(); coordinator.shutdown() }
    }

    @Test
    fun `a running result callback still consumes capacity when active slot is empty`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val callbacks = mutableMapOf<String, (String) -> Unit>()
        val coordinator = NotificationInferenceCoordinator<String>(maxPendingKeys = 2, scheduler = FakeScheduler())
        var refused = false
        coordinator.submit(NotificationInferenceWork("active", dispatch = { callbacks["active"] = it; RuntimeAdmission.ACCEPTED },
            onResult = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }, onSuperseded = {}, onBackpressure = {}))
        coordinator.submit(work("pending1", "one", mutableListOf(), callbacks))
        coordinator.submit(work("pending2", "two", mutableListOf(), callbacks))
        val finishing = Thread { callbacks.getValue("active")("done") }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            coordinator.submit(NotificationInferenceWork("overflow", dispatch = { RuntimeAdmission.ACCEPTED },
                onResult = {}, onSuperseded = {}, onBackpressure = { refused = true }))
            assertTrue("delivery must retain its admission slot", refused)
        } finally { release.countDown(); finishing.join(2_000); coordinator.shutdown() }
    }

    @Test
    fun `keeps one active request and replaces the latest pending revision by key`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = Collections.synchronizedMap(mutableMapOf<String, (String) -> Unit>())
        val coordinator = NotificationInferenceCoordinator<String>()

        coordinator.submit(work("a1", "a", events, callbacks))
        coordinator.submit(work("a2", "a", events, callbacks))
        coordinator.submit(work("b1", "b", events, callbacks))
        coordinator.submit(work("a3", "a", events, callbacks))

        // The replaced revision gets exactly one terminal callback, which runs on the
        // callback thread rather than on the submitting thread.
        assertTrue("the replaced revision must be superseded", waitFor { events.contains("superseded:a2") })
        assertEquals(listOf("dispatch:a1", "superseded:a2"), events)

        callbacks.getValue("a1")("result-a1")
        assertTrue("the replacement must be dispatched next", waitFor { events.contains("dispatch:a3") })
        assertEquals(
            listOf("dispatch:a1", "superseded:a2", "result:a1:result-a1", "dispatch:a3"),
            events,
        )

        callbacks.getValue("a3")("result-a3")
        assertTrue("the untouched pending revision must still run", waitFor { events.last() == "dispatch:b1" })
    }

    @Test
    fun `uses fifo fairness across keys and reports distinct-key capacity pressure`() {
        val events = mutableListOf<String>()
        val callbacks = mutableMapOf<String, (String) -> Unit>()
        val coordinator = NotificationInferenceCoordinator<String>(maxPendingKeys = 2)

        coordinator.submit(work("active", "active", events, callbacks))
        coordinator.submit(work("b", "b", events, callbacks))
        coordinator.submit(work("c", "c", events, callbacks))
        coordinator.submit(work("d", "d", events, callbacks))

        assertEquals(listOf("dispatch:active", "backpressure:d"), events)
        callbacks.getValue("active")("done")
        assertEquals("dispatch:b", events.last())
        callbacks.getValue("b")("done")
        assertEquals("dispatch:c", events.last())
    }

    @Test
    fun `maps broker queue refusal to backpressure without leaving an active request`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = NotificationInferenceCoordinator<String>()
        coordinator.submit(NotificationInferenceWork(
            notificationKey = "a",
            dispatch = {
                events += "dispatch"
                RuntimeAdmission.QUEUE_FULL
            },
            onResult = { events += "result" },
            onSuperseded = { events += "superseded" },
            onBackpressure = { events += "backpressure" },
        ))

        // A refusal must release the active slot, so the next revision is dispatched too.
        coordinator.submit(NotificationInferenceWork(
            notificationKey = "b",
            dispatch = {
                events += "dispatch-b"
                RuntimeAdmission.QUEUE_FULL
            },
            onResult = { },
            onSuperseded = { },
            onBackpressure = { events += "backpressure-b" },
        ))

        assertTrue(waitFor { events.contains("backpressure") })
        assertTrue(waitFor { events.contains("backpressure-b") })
        assertEquals(listOf("dispatch", "dispatch-b"), events.filter { it.startsWith("dispatch") })
        assertTrue(
            "the refusals are reported in submission order",
            events.indexOf("backpressure") < events.indexOf("backpressure-b"),
        )
    }

    private fun work(
        id: String,
        key: String,
        events: MutableList<String>,
        callbacks: MutableMap<String, (String) -> Unit>,
    ) = NotificationInferenceWork(
        notificationKey = key,
        dispatch = { callback ->
            events += "dispatch:$id"
            callbacks[id] = callback
            RuntimeAdmission.ACCEPTED
        },
        onResult = { events += "result:$id:$it" },
        onSuperseded = { events += "superseded:$id" },
        onBackpressure = { events += "backpressure:$id" },
        onExpired = { events += "expired:$id" },
    )

    @Test
    fun `a stale queued revision expires instead of waiting forever`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val scheduler = FakeScheduler()
        val coordinator = NotificationInferenceCoordinator<String>(
            pendingTimeoutMs = 250L,
            scheduler = scheduler,
        )

        coordinator.submit(work("active", "active", events, mutableMapOf()))
        coordinator.submit(work("waiting", "waiting", events, mutableMapOf()))

        assertTrue("the pending revision must expire", scheduler.fireLatest(250L))
        assertTrue(waitFor { events.contains("expired:waiting") })
        assertFalse(events.contains("dispatch:waiting"))
        coordinator.shutdown()
    }

    @Test
    fun `a revision replaced while freshness is checked is neither removed nor dispatched stale`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = Collections.synchronizedMap(mutableMapOf<String, (String) -> Unit>())
        val coordinator = NotificationInferenceCoordinator<String>()
        val replacementSubmitted = CountDownLatch(1)

        // The freshness check of the queued revision runs while the notification is
        // replaced, which is exactly the race the identity guard must survive.
        val stale = NotificationInferenceWork(
            notificationKey = "same-key",
            dispatch = { callback ->
                events += "dispatch:stale"
                callbacks["stale"] = callback
                RuntimeAdmission.ACCEPTED
            },
            onResult = { events += "result:stale" },
            onSuperseded = { events += "superseded:stale" },
            onBackpressure = { events += "backpressure:stale" },
            isFresh = {
                if (replacementSubmitted.count > 0) {
                    coordinator.submit(work("replacement", "same-key", events, callbacks))
                    replacementSubmitted.countDown()
                }
                false
            },
        )

        coordinator.submit(work("active", "active", events, callbacks))
        coordinator.submit(stale)

        // Completing the active work drains the queue and runs the freshness check.
        callbacks.getValue("active")("done")

        assertTrue(waitFor { events.contains("superseded:stale") })
        assertTrue("the replacement must be dispatched", waitFor { events.contains("dispatch:replacement") })
        assertFalse("a stale revision must never be dispatched", events.contains("dispatch:stale"))
        coordinator.shutdown()
    }

    @Test
    fun `a throwing result callback cannot stall the queue`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = Collections.synchronizedMap(mutableMapOf<String, (String) -> Unit>())
        val coordinator = NotificationInferenceCoordinator<String>()

        coordinator.submit(NotificationInferenceWork(
            notificationKey = "first",
            dispatch = { callback ->
                callbacks["first"] = callback
                RuntimeAdmission.ACCEPTED
            },
            onResult = { throw IllegalStateException("synthetic persistence failure") },
            onSuperseded = { },
            onBackpressure = { },
        ))
        coordinator.submit(work("second", "second", events, callbacks))

        callbacks.getValue("first")("done")

        assertTrue("the next revision must still be dispatched", waitFor { events.contains("dispatch:second") })
        coordinator.shutdown()
    }

    @Test
    fun `an expiry callback never runs on the deadline thread`() {
        val scheduler = FakeScheduler()
        val coordinator = NotificationInferenceCoordinator<String>(
            pendingTimeoutMs = 250L,
            scheduler = scheduler,
        )
        val callbackThread = arrayOfNulls<String>(1)
        val expired = CountDownLatch(1)

        coordinator.submit(work("active", "active", mutableListOf(), mutableMapOf()))
        coordinator.submit(NotificationInferenceWork(
            notificationKey = "waiting",
            dispatch = { RuntimeAdmission.ACCEPTED },
            onResult = { },
            onSuperseded = { },
            onBackpressure = { },
            onExpired = {
                callbackThread[0] = Thread.currentThread().name
                expired.countDown()
            },
        ))

        assertTrue(scheduler.fireLatest(250L))
        assertTrue(expired.await(2, TimeUnit.SECONDS))
        assertFalse(
            "a blocking callback must not occupy the deadline thread",
            callbackThread[0] == "test-deadline",
        )
        coordinator.shutdown()
    }

    @Test
    fun `a blocked terminal callback bounds capacity instead of accumulating work`() {
        val scheduler = FakeScheduler()
        val coordinator = NotificationInferenceCoordinator<String>(
            maxPendingKeys = 3,
            pendingTimeoutMs = 250L,
            scheduler = scheduler,
        )
        val callbacks = Collections.synchronizedMap(mutableMapOf<String, (String) -> Unit>())
        val blocking = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val terminals = Collections.synchronizedList(mutableListOf<String>())

        coordinator.submit(work("active", "active", mutableListOf(), callbacks))
        repeat(3) { index ->
            coordinator.submit(NotificationInferenceWork(
                notificationKey = "waiting-$index",
                dispatch = { RuntimeAdmission.ACCEPTED },
                onResult = { },
                onSuperseded = { },
                onBackpressure = { },
                onExpired = {
                    terminals += "expired-$index"
                    entered.countDown()
                    blocking.await(5, TimeUnit.SECONDS)
                    finished.countDown()
                },
            ))
        }

        // All three pending revisions expire, and the first callback blocks the thread.
        assertEquals(3, scheduler.fireAll(250L))
        assertTrue(entered.await(3, TimeUnit.SECONDS))

        // The expired work still occupies capacity while its callback runs, so new
        // revisions are refused instead of piling up behind an unbounded callback backlog.
        val refused = Collections.synchronizedList(mutableListOf<String>())
        repeat(4) { index ->
            coordinator.submit(NotificationInferenceWork(
                notificationKey = "overflow-$index",
                dispatch = { RuntimeAdmission.ACCEPTED },
                onResult = { },
                onSuperseded = { },
                onBackpressure = { refused += "overflow-$index" },
            ))
        }
        assertEquals(4, refused.size)

        // Nothing accepted is dropped, and capacity recovers once the callbacks finish.
        blocking.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertTrue(waitFor { terminals.size == 3 })
        callbacks.getValue("active")("done")

        val recovered = CountDownLatch(1)
        coordinator.submit(NotificationInferenceWork(
            notificationKey = "recovered",
            dispatch = {
                recovered.countDown()
                RuntimeAdmission.ACCEPTED
            },
            onResult = { },
            onSuperseded = { },
            onBackpressure = { },
        ))
        assertTrue("capacity must recover after the callbacks finish", recovered.await(3, TimeUnit.SECONDS))
        coordinator.shutdown()
    }

    @Test
    fun `a throwing freshness predicate is contained and cannot stall the queue`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = Collections.synchronizedMap(mutableMapOf<String, (String) -> Unit>())
        val coordinator = NotificationInferenceCoordinator<String>()

        coordinator.submit(work("active", "active", events, callbacks))
        coordinator.submit(NotificationInferenceWork(
            notificationKey = "throwing",
            dispatch = { callback ->
                events += "dispatch:throwing"
                callbacks["throwing"] = callback
                RuntimeAdmission.ACCEPTED
            },
            onResult = { events += "result:throwing" },
            onSuperseded = { events += "superseded:throwing" },
            onBackpressure = { events += "backpressure:throwing" },
            isFresh = { throw IllegalStateException("synthetic service query failure") },
        ))
        coordinator.submit(work("next", "next", events, callbacks))

        callbacks.getValue("active")("done")

        assertTrue(waitFor { events.contains("superseded:throwing") })
        assertFalse("unknown freshness must not be inferred", events.contains("dispatch:throwing"))
        assertTrue("draining must continue", waitFor { events.contains("dispatch:next") })
        coordinator.shutdown()
    }

    private class FakeScheduler : RuntimeDeadlineScheduler {
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMs: Long, action: () -> Unit): RuntimeDeadline {
            scheduled += delayMs to action
            return RuntimeDeadline { }
        }

        override fun shutdown() = Unit

        /** Runs the newest scheduled action on this (test) thread, like the real timer. */
        fun fireLatest(expectedDelayMs: Long): Boolean {
            val (delayMs, action) = scheduled.lastOrNull() ?: return false
            if (delayMs != expectedDelayMs) return false
            action()
            return true
        }

        /** Runs every action scheduled with [expectedDelayMs]; returns how many ran. */
        fun fireAll(expectedDelayMs: Long): Int {
            val matching = scheduled.filter { it.first == expectedDelayMs }
            matching.forEach { it.second() }
            return matching.size
        }
    }

    private fun waitFor(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
