package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Focused regression tests for the runtime watchdog and state machine.
 *
 * These are synthetic: no device, no model and no real notification data.
 */
class RuntimeBrokerCoreTest {
    private class Scheduled(val delayMs: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private class FakeScheduler : RuntimeDeadlineScheduler {
        val scheduled = ArrayList<Scheduled>()
        var shutdown = false

        override fun schedule(delayMs: Long, action: () -> Unit): RuntimeDeadline {
            val entry = Scheduled(delayMs, action)
            scheduled += entry
            return RuntimeDeadline { entry.cancelled = true }
        }

        override fun shutdown() {
            shutdown = true
        }

        fun latest(): Scheduled = scheduled.last()
    }

    private class Harness(val autoConnect: Boolean = true) {
        var now = 1_000L
        val scheduler = FakeScheduler()
        val core = RuntimeBrokerCore(elapsedRealtime = { now })
        val deliveries = ArrayList<Pair<String, RuntimeResponse>>()
        val dispatched = ArrayList<Pair<String, Int>>()
        val cancelledWork = ArrayList<String>()
        val boundGenerations = ArrayList<Int>()
        val unboundGenerations = ArrayList<Int>()
        val deadlines = HashMap<Pair<String, RuntimeDeadlineKind>, Scheduled>()
        private val handles = HashMap<Pair<String, RuntimeDeadlineKind>, RuntimeDeadline>()
        private val tokens = HashMap<Pair<String, RuntimeDeadlineKind>, Long>()
        var idleReleases = 0

        fun submit(
            id: String,
            timeoutMs: Long = 5_000L,
            forceReload: Boolean = false,
            onDelivery: ((RuntimeResponse) -> Unit)? = null,
        ): RuntimeAdmission {
            val work = RuntimeWork(
                internalRequestId = id,
                packId = "needlebub.otp.extractor",
                packVersion = "1.0.0",
                modelSize = 4_096L,
                input = "synthetic input",
                queryTemplate = "q:{{input}}",
                surface = "notification",
                timeoutMs = timeoutMs,
                forceReload = forceReload,
                requestedAtEpochMs = 1_700_000_000_000L,
                requestedAtElapsedMs = now,
                appVersionName = "0.1.0-beta.2",
                appVersionCode = 9L,
                modelIdentity = "pack:abi:size",
                callback = { response ->
                    deliveries += id to response
                    onDelivery?.invoke(response)
                },
            )
            val outcome = core.submit(work)
            apply(outcome.batch)
            return outcome.admission
        }

        fun apply(batch: EffectBatch): Unit = apply(batch.effects)

        fun apply(effects: List<RuntimeEffect>): Unit {
            effects.forEach { effect ->
                when (effect) {
                    is RuntimeEffect.ScheduleDeadline -> {
                        // The adapter owns scheduling: the core only asks for a deadline.
                        val key = effect.requestId to effect.kind
                        handles[key] = scheduler.schedule(effect.delayMs) {
                            apply(core.onDeadline(effect.requestId, effect.kind, effect.token))
                        }
                        val entry = scheduler.latest()
                        deadlines[key] = entry
                        tokens[key] = effect.token
                        assertEquals(effect.delayMs, entry.delayMs)
                    }

                    is RuntimeEffect.CancelDeadline -> {
                        val key = effect.requestId to effect.kind
                        handles.remove(key)?.cancel()
                        deadlines.remove(key)
                    }

                    is RuntimeEffect.ScheduleIdleRelease -> idleReleases += 1
                    RuntimeEffect.CancelIdleRelease -> Unit
                    is RuntimeEffect.BindRuntime -> {
                        boundGenerations += effect.generation
                        if (autoConnect) apply(core.onServiceConnected(effect.generation))
                    }

                    is RuntimeEffect.UnbindRuntime -> unboundGenerations += effect.generation
                    is RuntimeEffect.DispatchWork -> dispatched += effect.requestId to effect.generation
                    is RuntimeEffect.CancelRuntimeWork -> cancelledWork += effect.requestId
                    // The adapter invokes the terminal callback; the core only asks for it.
                    is RuntimeEffect.DeliverResponse -> effect.work.callback(effect.response)
                }
            }
        }

        fun fire(requestId: String, kind: RuntimeDeadlineKind) {
            val entry = deadlines[requestId to kind] ?: error("no $kind deadline for $requestId")
            assertFalse("$kind was already cancelled", entry.cancelled)
            entry.action()
        }

        fun deadlineToken(requestId: String, kind: RuntimeDeadlineKind): Long =
            tokens[requestId to kind] ?: error("no $kind deadline for $requestId")

        fun result(id: String, generation: Int = core.snapshot().generation, status: String = "OK") =
            apply(core.onResult(id, generation, RuntimeResponse(
                requestId = id,
                status = status,
                toolName = "extract_otp",
                resultJson = "{}",
                errorCode = null,
                durationMs = 12L,
                coldLoad = false,
                pssKb = 1_024L,
                loadState = RuntimeLoadState.WARM,
                inferenceMs = 10L,
            )))

        fun deliveredIds(): List<String> = deliveries.map { it.first }
    }

    @Test
    fun `a burst is bounded by the queue limit and rejected requests are not accepted`() {
        val harness = Harness(autoConnect = false)
        repeat(8) { index -> assertEquals(RuntimeAdmission.ACCEPTED, harness.submit("r$index")) }
        assertEquals(RuntimeAdmission.QUEUE_FULL, harness.submit("r8"))
        assertTrue(harness.deliveries.isEmpty())
    }

    @Test
    fun `a queued request expires instead of waiting forever and reports unknown load`() {
        val harness = Harness()
        harness.submit("running")
        harness.submit("waiting", timeoutMs = 5_000L)

        harness.now += 5_000L
        harness.fire("waiting", RuntimeDeadlineKind.QUEUE_EXPIRY)

        assertEquals(listOf("waiting"), harness.deliveredIds())
        val response = harness.deliveries.single().second
        assertEquals("ERROR", response.status)
        assertEquals(ErrorCodes.QUEUE_EXPIRED, response.errorCode)
        assertEquals(RuntimeTermination.QUEUE_EXPIRED.name, response.termination)
        assertEquals(RuntimeLoadState.UNKNOWN, response.loadState)
        assertNull("an unexecuted request must not claim a warm load", response.coldLoad)
        assertEquals(5_000L, response.queueWaitMs)
    }

    @Test
    fun `a service that never binds fails the request at the bind deadline`() {
        val harness = Harness(autoConnect = false)
        harness.submit("never-bound", timeoutMs = 5_000L)

        harness.now += RuntimePolicy.BIND_TIMEOUT_MS
        harness.fire("never-bound", RuntimeDeadlineKind.BIND_EXPIRY)

        val response = harness.deliveries.single().second
        assertEquals(ErrorCodes.BIND_FAILED, response.errorCode)
        assertEquals(RuntimeTermination.BIND_FAILED.name, response.termination)
        assertNull(response.coldLoad)
        assertTrue(harness.unboundGenerations.isNotEmpty())
    }

    @Test
    fun `an inference timeout recycles the runtime and recovers for the next request`() {
        val harness = Harness()
        harness.submit("first")
        harness.submit("second")
        val firstGeneration = harness.dispatched.single().second

        harness.now += 5_000L
        harness.fire("first", RuntimeDeadlineKind.INFERENCE_TIMEOUT)

        assertEquals(listOf("first"), harness.deliveredIds())
        val response = harness.deliveries.single().second
        assertEquals(ErrorCodes.TIMEOUT, response.errorCode)
        assertEquals(RuntimeTermination.INFERENCE_TIMEOUT.name, response.termination)
        assertTrue("the isolated runtime must be cancelled", harness.cancelledWork.contains("first"))
        assertTrue(harness.unboundGenerations.contains(firstGeneration))

        // Recovery: the queued request is rebound on a new generation and completes.
        val secondGeneration = harness.dispatched.single { it.first == "second" }.second
        assertTrue(secondGeneration > firstGeneration)
        harness.result("second")
        assertEquals(listOf("first", "second"), harness.deliveredIds())
        assertEquals("OK", harness.deliveries.last().second.status)
        assertEquals(RuntimeTermination.RESULT.name, harness.deliveries.last().second.termination)
    }

    @Test
    fun `a stale connection callback cannot disturb a newer generation`() {
        val harness = Harness(autoConnect = false)
        harness.submit("work", timeoutMs = 5_000L)
        val staleGeneration = harness.boundGenerations.single()
        harness.apply(harness.core.onServiceConnected(staleGeneration))
        harness.now += 5_000L
        harness.fire("work", RuntimeDeadlineKind.INFERENCE_TIMEOUT)
        assertEquals(listOf("work"), harness.deliveredIds())

        harness.submit("next", timeoutMs = 5_000L)
        val freshGeneration = harness.boundGenerations.last()
        assertTrue(freshGeneration > staleGeneration)

        // A late callback for the released generation must not mark the runtime bound.
        harness.apply(harness.core.onServiceConnected(staleGeneration))
        assertTrue(harness.unboundGenerations.contains(staleGeneration))
        assertFalse(harness.core.snapshot().bound)
        assertTrue(harness.dispatched.none { it.first == "next" })
    }

    @Test
    fun `a stale binder death cannot fail the current request`() {
        val harness = Harness(autoConnect = false)
        harness.submit("first")
        val staleGeneration = harness.boundGenerations.single()
        harness.apply(harness.core.onServiceConnected(staleGeneration))
        harness.now += 5_000L
        harness.fire("first", RuntimeDeadlineKind.INFERENCE_TIMEOUT)

        harness.submit("second")
        val freshGeneration = harness.boundGenerations.last()
        harness.apply(harness.core.onServiceConnected(freshGeneration))
        harness.now += 10L
        harness.apply(harness.core.onServiceLost(staleGeneration))

        assertEquals(listOf("first"), harness.deliveredIds())
        assertTrue(harness.core.snapshot().bound)
        assertEquals("second", harness.core.snapshot().currentRequestId)
    }

    @Test
    fun `a real runtime death fails the in flight request once and recovers`() {
        val harness = Harness()
        harness.submit("inflight")
        harness.submit("queued")
        val generation = harness.dispatched.single().second

        harness.apply(harness.core.onServiceLost(generation))

        val response = harness.deliveries.single().second
        assertEquals(ErrorCodes.RUNTIME_CRASH, response.errorCode)
        assertEquals(RuntimeTermination.RUNTIME_DEATH.name, response.termination)
        assertNull("a death before the result must not claim a load state", response.coldLoad)

        harness.result("queued")
        assertEquals(listOf("inflight", "queued"), harness.deliveredIds())
    }

    @Test
    fun `cancellation is distinct from an inference timeout`() {
        val harness = Harness()
        harness.submit("running")
        harness.submit("waiting")

        harness.apply(harness.core.cancel("waiting"))
        val queued = harness.deliveries.single().second
        assertEquals(ErrorCodes.CANCELLED, queued.errorCode)
        assertEquals(RuntimeTermination.CANCELLED.name, queued.termination)

        harness.apply(harness.core.cancel("running"))
        val running = harness.deliveries.last().second
        assertEquals(ErrorCodes.CANCELLED, running.errorCode)
        assertTrue(harness.cancelledWork.contains("running"))
    }

    @Test
    fun `a dispatch failure is reported and the runtime is recycled`() {
        val harness = Harness()
        harness.submit("work")
        val generation = harness.dispatched.single().second

        harness.apply(harness.core.onDispatchFailed("work", generation))

        val response = harness.deliveries.single().second
        assertEquals(RuntimeTermination.DISPATCH_FAILED.name, response.termination)
        assertEquals(ErrorCodes.RUNTIME_CRASH, response.errorCode)
        assertTrue(harness.unboundGenerations.contains(generation))
    }

    @Test
    fun `exactly one terminal callback is delivered when a result death and timeout race`() {
        val harness = Harness()
        harness.submit("work")
        val generation = harness.dispatched.single().second

        harness.result("work", generation = generation)
        harness.apply(harness.core.onServiceLost(generation))
        harness.now += 5_000L
        harness.apply(harness.core.onDeadline("work", RuntimeDeadlineKind.INFERENCE_TIMEOUT))
        harness.result("work", generation = generation)

        assertEquals(1, harness.deliveries.size)
        assertEquals(RuntimeTermination.RESULT.name, harness.deliveries.single().second.termination)
    }

    @Test
    fun `a result from an older generation is ignored`() {
        val harness = Harness(autoConnect = false)
        harness.submit("work")
        val generation = harness.boundGenerations.single()
        harness.apply(harness.core.onServiceConnected(generation))

        harness.apply(harness.core.onResult("work", generation + 1, RuntimeResponse(
            requestId = "work",
            status = "OK",
            toolName = "extract_otp",
            resultJson = "{}",
            errorCode = null,
            durationMs = 1L,
            coldLoad = false,
            pssKb = 0L,
        )))

        assertTrue(harness.deliveries.isEmpty())
        assertEquals("work", harness.core.snapshot().currentRequestId)
    }

    @Test
    fun `queue bind and inference deadlines are all bounded and distinct`() {
        val harness = Harness(autoConnect = false)
        harness.submit("work", timeoutMs = 5_000L)

        val queue = harness.scheduler.scheduled[0]
        val bind = harness.scheduler.scheduled[1]
        assertEquals(5_000L, queue.delayMs)
        assertEquals(RuntimePolicy.BIND_TIMEOUT_MS, bind.delayMs)
    }

    @Test
    fun `the idle release releases the runtime after the last request`() {
        val harness = Harness()
        harness.submit("only")
        val generation = harness.dispatched.single().second
        harness.result("only")

        harness.apply(harness.core.onIdleReleaseDue())

        assertTrue(harness.unboundGenerations.contains(generation))
        assertFalse(harness.core.snapshot().bound)
    }

    @Test
    fun `a callback may submit the next request without deadlocking the state machine`() {
        val harness = Harness()
        var nested: RuntimeAdmission? = null
        harness.submit("first", onDelivery = { nested = harness.submit("second") })

        harness.result("first")

        assertEquals(RuntimeAdmission.ACCEPTED, nested)
        assertEquals(listOf("first"), harness.deliveredIds())
        assertEquals("second", harness.core.snapshot().currentRequestId)
    }

    @Test
    fun `deadlines fire on their own thread while the main thread is blocked`() {
        val scheduler = ThreadedDeadlineScheduler()
        try {
            val core = RuntimeBrokerCore(elapsedRealtime = { 0L })
            val delivered = CountDownLatch(1)
            val callbackThread = arrayOfNulls<String>(1)
            val work = RuntimeWork(
                internalRequestId = "blocked-main",
                packId = "pack",
                packVersion = "1",
                modelSize = 1L,
                input = "input",
                queryTemplate = "{{input}}",
                surface = "notification",
                timeoutMs = 60L,
                forceReload = false,
                requestedAtEpochMs = 0L,
                requestedAtElapsedMs = 0L,
                appVersionName = "0.1.0-beta.2",
                appVersionCode = 9L,
                modelIdentity = "pack",
                callback = { response ->
                    assertEquals(ErrorCodes.TIMEOUT, response.errorCode)
                    callbackThread[0] = Thread.currentThread().name
                    delivered.countDown()
                },
            )
            fun apply(batch: EffectBatch): Unit {
                batch.effects.forEach { effect ->
                    when (effect) {
                        is RuntimeEffect.ScheduleDeadline -> scheduler.schedule(effect.delayMs) {
                            apply(core.onDeadline(effect.requestId, effect.kind))
                        }

                        is RuntimeEffect.BindRuntime -> apply(core.onServiceConnected(effect.generation))
                        // The dispatch is never answered: only the watchdog can settle it.
                        is RuntimeEffect.DispatchWork -> Unit
                        is RuntimeEffect.DeliverResponse -> effect.work.callback(effect.response)
                        else -> Unit
                    }
                }
            }
            apply(core.submit(work).batch)

            // Block the test (main) thread: the watchdog must still fire.
            val blockedUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400)
            var spins = 0
            while (System.nanoTime() < blockedUntil) spins += 1

            assertTrue("deadline did not fire while the main thread was blocked", delivered.await(2, TimeUnit.SECONDS))
            assertEquals(ThreadedDeadlineScheduler.THREAD_NAME, callbackThread[0])
            assertTrue(spins > 0)
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `a late bind timer from a cancelled bind cannot fail a newer bind`() {
        val harness = Harness(autoConnect = false)
        harness.submit("first", timeoutMs = 5_000L)
        val staleTimer = harness.deadlineToken("first", RuntimeDeadlineKind.BIND_EXPIRY)

        // The first request gives up, the runtime is recycled and a second request binds.
        harness.now += RuntimePolicy.BIND_TIMEOUT_MS
        harness.fire("first", RuntimeDeadlineKind.BIND_EXPIRY)
        assertEquals(listOf("first"), harness.deliveredIds())

        harness.submit("second", timeoutMs = 5_000L)
        val freshTimer = harness.deadlineToken("second", RuntimeDeadlineKind.BIND_EXPIRY)
        assertTrue("each bind attempt gets its own token", freshTimer > staleTimer)

        // The stale timer fires late with its own token: it must be ignored.
        harness.apply(harness.core.onDeadline("first", RuntimeDeadlineKind.BIND_EXPIRY, staleTimer))
        assertTrue("the newer bind must survive a stale timer", harness.core.snapshot().binding)
        assertEquals(listOf("first"), harness.deliveredIds())

        // The current timer still works and fails the current owner.
        harness.apply(harness.core.onDeadline("second", RuntimeDeadlineKind.BIND_EXPIRY, freshTimer))
        assertEquals(listOf("first", "second"), harness.deliveredIds())
    }

    @Test
    fun `bind wait is recorded separately from queue wait`() {
        val harness = Harness(autoConnect = false)
        harness.submit("work", timeoutMs = 5_000L)
        val generation = harness.boundGenerations.single()
        harness.now += 400L

        harness.apply(harness.core.onServiceConnected(generation))
        harness.now += 600L
        harness.result("work", generation = generation)

        val response = harness.deliveries.single().second
        assertEquals("queue wait ends when the bind starts", 0L, response.queueWaitMs)
        assertEquals("bind wait is the connection time", 400L, response.bindWaitMs)
        assertNull("a synthetic result carries no measured model load", response.modelLoadMs)
        assertEquals(10L, response.inferenceMs)
    }

    @Test
    fun `a rejected request is never delivered and never counted twice`() {
        val harness = Harness(autoConnect = false)
        repeat(8) { harness.submit("r$it") }
        val scheduledBefore = harness.scheduler.scheduled.size
        val deliveriesBefore = harness.deliveries.size

        assertEquals(RuntimeAdmission.QUEUE_FULL, harness.submit("r8"))

        assertEquals(deliveriesBefore, harness.deliveries.size)
        assertEquals(scheduledBefore, harness.scheduler.scheduled.size)
        assertEquals(8, harness.core.snapshot().queueSize)
    }
}
