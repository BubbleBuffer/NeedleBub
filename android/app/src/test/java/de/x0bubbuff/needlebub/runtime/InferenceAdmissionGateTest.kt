package de.x0bubbuff.needlebub.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The cancel/start handshake in the isolated service.
 *
 * Exactly one outcome is allowed: either the run never starts, or the cancel asks for the
 * process to be terminated. A cancel that lands between the two old steps used to be lost.
 */
class InferenceAdmissionGateTest {
    @Test
    fun `a cancel before admission suppresses the start`() {
        val gate = InferenceAdmissionGate()

        assertFalse("the cancel must be observed before the run starts", gate.cancel("request"))
        assertFalse("a cancelled request must never start", gate.admit("request"))
    }

    @Test
    fun `a cancel of the running request asks for termination`() {
        val gate = InferenceAdmissionGate()
        assertTrue(gate.admit("request"))
        assertTrue(gate.isActive("request"))

        assertTrue("a running request must be killed", gate.cancel("request"))
        assertFalse(gate.isActive("request"))
    }

    @Test
    fun `a duplicate finish after a cancel is suppressed`() {
        val gate = InferenceAdmissionGate()
        gate.admit("request")
        assertTrue("the run completed before the cancel", gate.finish("request"))

        // A cancel that lands after the run must not let a second callback through.
        assertFalse("nothing is running any more", gate.cancel("request"))
        assertFalse(gate.finish("request"))
    }

    @Test
    fun `a completed run delivers once and admits the next request`() {
        val gate = InferenceAdmissionGate()
        gate.admit("first")
        assertTrue(gate.finish("first"))
        assertTrue(gate.admit("second"))
        assertTrue(gate.finish("second"))
    }

    @Test
    fun `a cancelled request never blocks the next one`() {
        val gate = InferenceAdmissionGate()
        gate.cancel("abandoned")
        assertTrue(gate.admit("next"))
        assertTrue(gate.finish("next"))
    }

    @Test
    fun `a concurrent cancel and start always resolve to one of the two outcomes`() {
        val rounds = 400
        var forbidden = 0
        var mismatched = 0
        repeat(rounds) { round ->
            val gate = InferenceAdmissionGate()
            val requestId = "request-$round"
            val start = CountDownLatch(1)
            var admitted = false
            var killed = false
            val startThread = Thread {
                start.await()
                admitted = gate.admit(requestId)
            }
            val cancelThread = Thread {
                start.await()
                killed = gate.cancel(requestId)
            }
            startThread.start()
            cancelThread.start()
            start.countDown()
            startThread.join(TimeUnit.SECONDS.toMillis(5))
            cancelThread.join(TimeUnit.SECONDS.toMillis(5))

            // A started run must always be killed; a suppressed start needs no kill.
            if (admitted && !killed) forbidden += 1
            if (admitted != killed) mismatched += 1
        }

        assertTrue("a started run must always be killed by its cancel", forbidden == 0)
        assertTrue("a cancel must never be recorded without suppressing the start", mismatched == 0)
    }
}
