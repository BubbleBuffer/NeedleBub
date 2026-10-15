package de.x0bubbuff.needlebub.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real platform host against the isolated service: bind, connect, dispatch and the
 * release boundary. The model is a synthetic fixture, so the runtime reports a
 * deterministic engine error instead of a result.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeHostInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingListener : RuntimeHostListener {
        val connected = Collections.synchronizedList(mutableListOf<Int>())
        val lost = Collections.synchronizedList(mutableListOf<Int>())
        val failed = Collections.synchronizedList(mutableListOf<Int>())

        override fun onConnected(generation: Int) {
            connected += generation
        }

        override fun onServiceLost(generation: Int) {
            lost += generation
        }

        override fun onBindFailed(generation: Int) {
            failed += generation
        }
    }

    private fun waitFor(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun syntheticPack(): InstalledPack {
        val model = "synthetic-emulator-model".encodeToByteArray()
        val directory = File(context.cacheDir, "runtime-host-pack").apply {
            deleteRecursively()
            mkdirs()
        }
        File(directory, "model.cact").writeBytes(model)
        File(directory, "tools.json").writeText(TOOLS)
        val manifest = PackManifest.parse(
            """
            {
              "formatVersion":1,
              "id":"de.x0bubbuff.needlebub.hosttest",
              "version":"1.0.0",
              "name":"Host test",
              "author":"BubbleBuffer",
              "description":"Synthetic host fixture.",
              "license":"MIT",
              "engine":{"abi":"${PackManifest.ENGINE_ABI}"},
              "model":{"path":"model.cact","size":${model.size},"sha256":"${model.sha256()}"},
              "queryTemplate":"{{input}}",
              "surfaces":["external"],
              "outputs":{}
            }
            """.trimIndent(),
        )
        return InstalledPack(manifest, directory, true)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    @Test
    fun bindsConnectsDispatchesAndReleasesTheSlot() {
        val host = AndroidRuntimeHost(context)
        val listener = RecordingListener()

        assertTrue(host.bind(11, listener))
        assertTrue("the isolated service must connect", waitFor { listener.connected.contains(11) })

        val responses = Collections.synchronizedList(mutableListOf<RuntimeResponse>())
        val completed = CountDownLatch(1)
        host.dispatch(
            RuntimeRequest(
                requestId = "host-dispatch",
                packId = "de.x0bubbuff.needlebub.hosttest",
                packVersion = "1.0.0",
                input = "synthetic input",
                queryTemplate = "{{input}}",
                modelSize = 22L,
                timeoutMs = 5_000L,
                surface = "notification",
                forceReload = true,
            ),
            syntheticPack(),
            11,
        ) { response ->
            responses += response
            completed.countDown()
        }

        assertTrue("the dispatch must be answered", completed.await(15, TimeUnit.SECONDS))
        assertEquals(1, responses.size)
        // The emulator runtime is a stub: the engine refuses the model deterministically.
        assertEquals(ErrorCodes.PACK_INVALID, responses.single().errorCode)

        host.unbind(11)
        assertTrue("the slot must be gone after unbind", waitFor { !host.slotIsLive(11) })
    }

    @Test
    fun aLateConnectionAfterUnbindIsDroppedAndLeavesNoSlot() {
        val host = AndroidRuntimeHost(context)
        val listener = RecordingListener()

        assertTrue(host.bind(12, listener))
        // Release immediately: the platform may still report the connection afterwards.
        host.unbind(12)

        Thread.sleep(1_500L)
        assertFalse("a released generation must not connect", listener.connected.contains(12))
        assertFalse(host.slotIsLive(12))
        assertEquals(0, host.slotCount())
    }

    @Test
    fun dispatchingToAReleasedGenerationFailsInsteadOfReusingAStaleBinder() {
        val host = AndroidRuntimeHost(context)
        val listener = RecordingListener()
        host.bind(13, listener)
        waitFor { listener.connected.contains(13) }
        host.unbind(13)

        var failed = false
        try {
            host.dispatch(
                RuntimeRequest(
                    requestId = "released",
                    packId = "de.x0bubbuff.needlebub.hosttest",
                    packVersion = "1.0.0",
                    input = "synthetic input",
                    queryTemplate = "{{input}}",
                    modelSize = 22L,
                    timeoutMs = 1_000L,
                    surface = "notification",
                    forceReload = false,
                ),
                syntheticPack(),
                13,
            ) { }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a released generation must not accept a dispatch", failed)
    }

    @Test
    fun aBrokerRequestAgainstTheIsolatedServiceDeliversExactlyOneCallback() {
        val broker = RuntimeBroker(AndroidRuntimeHost(context))
        val deliveries = AtomicInteger(0)
        val completed = CountDownLatch(1)

        assertEquals(
            RuntimeAdmission.ACCEPTED,
            broker.infer(
                "broker-instrumented",
                syntheticPack(),
                "synthetic input",
                5_000L,
                surface = "notification",
                forceReload = true,
            ) { response ->
                deliveries.incrementAndGet()
                assertEquals(ErrorCodes.PACK_INVALID, response.errorCode)
                assertEquals(RuntimeTermination.RESULT.name, response.termination)
                completed.countDown()
            },
        )

        assertTrue("the broker must answer the request", completed.await(15, TimeUnit.SECONDS))
        Thread.sleep(300L)
        assertEquals("exactly one terminal callback", 1, deliveries.get())
        broker.shutdown()
    }

    private companion object {
        const val TOOLS = """{"formatVersion":1,"tools":[{"name":"extract_otp","description":"Extract an OTP.","parameters":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}}]}"""
    }
}
