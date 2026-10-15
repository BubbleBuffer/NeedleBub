package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapter-level tests: a fake host stands in for the platform, so blocking callbacks,
 * delayed or refused binds and concurrent submissions can be reproduced deterministically.
 */
class RuntimeBrokerAdapterTest {
    private val pack = InstalledPack(
        manifest = PackManifest(
            formatVersion = 1,
            id = "test.pack",
            version = "1.0.0",
            name = "Test pack",
            author = "test",
            description = "synthetic",
            license = "test",
            engineAbi = PackManifest.ENGINE_ABI,
            modelPath = "model.cact",
            modelSize = 42L,
            modelSha256 = "0".repeat(64),
            queryTemplate = "{{input}}",
            surfaces = setOf("external"),
            outputs = emptyMap(),
        ),
        directory = File("."),
        verified = true,
    )

    private class FakeHost : RuntimeHost {
        val bound = Collections.synchronizedList(mutableListOf<Int>())
        val unbound = Collections.synchronizedList(mutableListOf<Int>())
        val dispatched = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        private val listeners = ConcurrentHashMap<Int, RuntimeHostListener>()
        private val pending = ConcurrentHashMap<String, (RuntimeResponse) -> Unit>()

        @Volatile var connectImmediately = true

        @Volatile var acceptBind = true

        @Volatile var dispatchBlocker: CountDownLatch? = null

        @Volatile var dispatchFailure: Exception? = null

        @Volatile var respondImmediately = true

        override fun bind(generation: Int, listener: RuntimeHostListener): Boolean {
            bound += generation
            listeners[generation] = listener
            if (!acceptBind) return false
            if (connectImmediately) listener.onConnected(generation)
            return true
        }

        fun connect(generation: Int) = listeners[generation]?.onConnected(generation)

        fun lose(generation: Int) = listeners[generation]?.onServiceLost(generation)

        override fun unbind(generation: Int) {
            unbound += generation
            listeners.remove(generation)
        }

        override fun dispatch(
            request: RuntimeRequest,
            pack: InstalledPack,
            generation: Int,
            onResult: (RuntimeResponse) -> Unit,
        ) {
            dispatchBlocker?.await(5, TimeUnit.SECONDS)
            dispatchFailure?.let { throw it }
            dispatched += request.requestId
            pending[request.requestId] = onResult
            if (respondImmediately) onResult(ok(request.requestId))
        }

        fun complete(requestId: String, response: RuntimeResponse = ok(requestId)) {
            pending[requestId]?.invoke(response)
        }

        override fun cancel(requestId: String, generation: Int) {
            cancelled += requestId
        }
    }

    private fun broker(
        host: FakeHost,
        bindTimeoutMs: Long = 300L,
    ): RuntimeBroker = RuntimeBroker(
        host = host,
        scheduler = ThreadedDeadlineScheduler(),
        core = RuntimeBrokerCore(bindTimeoutMs = bindTimeoutMs, elapsedRealtime = System::nanoTime),
        elapsedRealtime = System::nanoTime,
        wallClock = System::currentTimeMillis,
        appVersionName = "test",
        appVersionCode = 1L,
        logLine = { },
    )

    private fun submit(
        broker: RuntimeBroker,
        id: String,
        timeoutMs: Long = 1_000L,
        callback: (RuntimeResponse) -> Unit = { },
    ): RuntimeAdmission = broker.infer(
        internalRequestId = id,
        pack = pack,
        input = "synthetic input",
        timeoutMs = timeoutMs,
        surface = "notification",
        callback = callback,
    )

    @Test
    fun `a slow callback never blocks the next request or its timeout`() {
        val host = FakeHost()
        val broker = broker(host)
        val slowCallbackEntered = CountDownLatch(1)
        val releaseSlowCallback = CountDownLatch(1)
        val firstDelivered = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)

        assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "first") {
            slowCallbackEntered.countDown()
            releaseSlowCallback.await(5, TimeUnit.SECONDS)
            firstDelivered.countDown()
        })
        assertTrue(slowCallbackEntered.await(2, TimeUnit.SECONDS))

        // The first callback is still running; a second request must still be dispatched.
        host.respondImmediately = false
        assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "second", timeoutMs = 400L) {
            secondDelivered.countDown()
        })
        val dispatched = waitFor { host.dispatched.contains("second") }
        assertTrue("second request must reach the runtime", dispatched)

        // Its inference deadline must still fire even though a callback is stuck.
        assertTrue("second request must time out", secondDelivered.await(3, TimeUnit.SECONDS))
        releaseSlowCallback.countDown()
        assertTrue(firstDelivered.await(3, TimeUnit.SECONDS))
        broker.shutdown()
    }

    @Test
    fun `a blocked binder dispatch delays neither infer nor deadline recovery`() {
        val host = FakeHost()
        val broker = broker(host)
        val blocker = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val firstDelivered = CountDownLatch(1)
        val secondDelivered = CountDownLatch(1)
        host.dispatchBlocker = blocker

        assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "blocked", timeoutMs = 400L) {
            firstDelivered.countDown()
        })
        assertTrue(waitFor { host.dispatched.isEmpty() && host.bound.isNotEmpty() })

        // A second request is admitted promptly even though the dispatch thread is stuck.
        val admittedInTime = CountDownLatch(1)
        assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "second", timeoutMs = 400L) {
            secondDelivered.countDown()
        })
        admittedInTime.countDown()
        assertTrue(admittedInTime.await(1, TimeUnit.SECONDS))

        // The blocked request still times out and is delivered while dispatch is stuck.
        assertTrue("timeout must not wait for the blocked binder", firstDelivered.await(3, TimeUnit.SECONDS))
        assertTrue(secondDelivered.await(3, TimeUnit.SECONDS))
        assertTrue(host.cancelled.contains("blocked"))
        releaseBlocker.countDown()
        broker.shutdown()
    }

    @Test
    fun `a host that never connects fails the request at the bind deadline`() {
        val host = FakeHost().apply { connectImmediately = false }
        val broker = broker(host, bindTimeoutMs = 200L)
        val delivered = CountDownLatch(1)
        val responses = Collections.synchronizedList(mutableListOf<RuntimeResponse>())

        assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "never-bound", timeoutMs = 5_000L) {
            responses += it
            delivered.countDown()
        })
        assertTrue("bind deadline must fail the request", delivered.await(3, TimeUnit.SECONDS))
        assertEquals(RuntimeTermination.BIND_FAILED.name, responses.single().termination)
        assertEquals(de.x0bubbuff.needlebub.gateway.ErrorCodes.BIND_FAILED, responses.single().errorCode)
        assertFalse(host.dispatched.contains("never-bound"))
        broker.shutdown()
    }

    @Test
    fun `a refused bind is reported as a bind failure rather than a timeout`() {
        val host = FakeHost().apply { acceptBind = false }
        val broker = broker(host)
        val delivered = CountDownLatch(1)
        val responses = Collections.synchronizedList(mutableListOf<RuntimeResponse>())

        submit(broker, "refused") {
            responses += it
            delivered.countDown()
        }
        assertTrue(delivered.await(3, TimeUnit.SECONDS))
        assertEquals(RuntimeTermination.BIND_FAILED.name, responses.single().termination)
        broker.shutdown()
    }

    @Test
    fun `a late connect from a stale generation is unbound and cannot dispatch`() {
        val host = FakeHost().apply { connectImmediately = false }
        val broker = broker(host, bindTimeoutMs = 200L)
        val delivered = CountDownLatch(1)

        submit(broker, "stale") { delivered.countDown() }
        assertTrue(delivered.await(3, TimeUnit.SECONDS))
        val staleGeneration = host.bound.single()

        // The platform reports the connection after the request already failed.
        host.connect(staleGeneration)
        assertTrue(waitFor { host.unbound.contains(staleGeneration) })
        assertFalse(host.dispatched.contains("stale"))
        broker.shutdown()
    }

    @Test
    fun `a result from a lost generation is ignored`() {
        val host = FakeHost()
        val broker = broker(host)
        val deliveries = AtomicInteger(0)
        val delivered = CountDownLatch(1)
        host.respondImmediately = false

        submit(broker, "lost") {
            deliveries.incrementAndGet()
            delivered.countDown()
        }
        assertTrue(waitFor { host.dispatched.contains("lost") })
        val generation = host.bound.last()
        host.lose(generation)
        assertTrue(delivered.await(3, TimeUnit.SECONDS))
        assertEquals(1, deliveries.get())

        // A late result for the dead generation must not produce a second callback.
        host.complete("lost")
        Thread.sleep(150)
        assertEquals(1, deliveries.get())
        broker.shutdown()
    }

    @Test
    fun `concurrent submissions stay bounded and deliver exactly once`() {
        val host = FakeHost()
        val broker = broker(host)
        val threads = 12
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val admissions = Collections.synchronizedList(mutableListOf<RuntimeAdmission>())
        val deliveries = ConcurrentHashMap<String, AtomicInteger>()
        val delivered = CountDownLatch(RuntimePolicy.MAX_QUEUE)

        for (index in 0 until threads) {
            Thread {
                start.await()
                val id = "concurrent-$index"
                val admission = submit(broker, id, timeoutMs = 5_000L) {
                    deliveries.computeIfAbsent(id) { AtomicInteger(0) }.incrementAndGet()
                    delivered.countDown()
                }
                admissions += admission
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(RuntimePolicy.MAX_QUEUE, admissions.count { it == RuntimeAdmission.ACCEPTED })
        assertEquals(threads - RuntimePolicy.MAX_QUEUE, admissions.count { it == RuntimeAdmission.QUEUE_FULL })
        assertTrue("every accepted request must settle", delivered.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(RuntimePolicy.MAX_QUEUE, deliveries.size)
        deliveries.forEach { (id, count) -> assertEquals("exactly one callback for $id", 1, count.get()) }
        broker.shutdown()
    }

    @Test
    fun `blocked callbacks saturate admission and recover afterwards`() {
        val host = FakeHost()
        val broker = broker(host)
        val blocker = CountDownLatch(1)
        val entered = CountDownLatch(2)
        val released = CountDownLatch(2)

        // Two blocked callbacks occupy both callback threads while their slots stay taken.
        repeat(2) { index ->
            assertEquals(RuntimeAdmission.ACCEPTED, submit(broker, "blocked-$index") {
                entered.countDown()
                blocker.await(5, TimeUnit.SECONDS)
                released.countDown()
            })
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))

        // Fill the remaining admission slots; every accepted request keeps its slot.
        var accepted = 2
        for (index in 2 until RuntimePolicy.MAX_QUEUE) {
            val admission = submit(broker, "fill-$index") { blocker.await(5, TimeUnit.SECONDS) }
            if (admission == RuntimeAdmission.ACCEPTED) accepted += 1
        }
        assertEquals(RuntimePolicy.MAX_QUEUE, accepted)

        // Saturated: no new work is admitted, and no callback is dropped to make room.
        assertEquals(RuntimeAdmission.QUEUE_FULL, submit(broker, "overflow") { })
        assertTrue(waitFor { host.dispatched.size <= RuntimePolicy.MAX_QUEUE })

        // Releasing the callbacks frees the slots and admission recovers.
        blocker.countDown()
        assertTrue(released.await(5, TimeUnit.SECONDS))
        val recovered = CountDownLatch(1)
        assertTrue(waitFor {
            submit(broker, "recovered") { recovered.countDown() } == RuntimeAdmission.ACCEPTED
        })
        assertTrue("admission must recover once callbacks finish", recovered.await(3, TimeUnit.SECONDS))
        broker.shutdown()
    }

    @Test
    fun `a throwing callback does not stop later deliveries`() {
        val host = FakeHost()
        val broker = broker(host)
        val secondDelivered = CountDownLatch(1)
        val firstReturned = CountDownLatch(1)

        submit(broker, "throwing") {
            firstReturned.countDown()
            throw IllegalStateException("synthetic persistence failure")
        }
        assertTrue(firstReturned.await(3, TimeUnit.SECONDS))
        submit(broker, "healthy") { secondDelivered.countDown() }
        assertTrue("broker must survive a throwing callback", secondDelivered.await(3, TimeUnit.SECONDS))
        broker.shutdown()
    }

    @Test
    fun `a cancelled request is never dispatched to the runtime`() {
        val host = FakeHost()
        val broker = broker(host)
        val blocker = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        host.dispatchBlocker = blocker

        submit(broker, "blocker", timeoutMs = 5_000L)
        assertTrue(waitFor { host.dispatched.isEmpty() && host.bound.isNotEmpty() })

        val cancelled = CountDownLatch(1)
        submit(broker, "cancelled", timeoutMs = 5_000L) { cancelled.countDown() }
        broker.cancel("cancelled")
        assertTrue(cancelled.await(3, TimeUnit.SECONDS))

        releaseBlocker.countDown()
        Thread.sleep(200)
        assertFalse("a settled request must not reach the runtime", host.dispatched.contains("cancelled"))
        broker.shutdown()
    }

    @Test
    fun `dispatch failure is reported as a runtime crash and the next request recovers`() {
        val host = FakeHost().apply { dispatchFailure = IllegalStateException("synthetic binder failure") }
        val broker = broker(host)
        val failed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val responses = Collections.synchronizedList(mutableListOf<RuntimeResponse>())

        submit(broker, "failing") {
            responses += it
            failed.countDown()
        }
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        assertEquals(RuntimeTermination.DISPATCH_FAILED.name, responses.single().termination)
        assertEquals(de.x0bubbuff.needlebub.gateway.ErrorCodes.RUNTIME_CRASH, responses.single().errorCode)

        host.dispatchFailure = null
        submit(broker, "recovered") { recovered.countDown() }
        assertTrue("broker must recover after a dispatch failure", recovered.await(3, TimeUnit.SECONDS))
        broker.shutdown()
    }

    private fun waitFor(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private companion object {
        fun ok(requestId: String) = RuntimeResponse(
            requestId = requestId,
            status = "OK",
            toolName = "answer",
            resultJson = "{}",
            errorCode = null,
            durationMs = 1L,
            coldLoad = false,
            pssKb = 1L,
            loadState = RuntimeLoadState.WARM,
            termination = RuntimeTermination.RESULT.name,
        )
    }
}
