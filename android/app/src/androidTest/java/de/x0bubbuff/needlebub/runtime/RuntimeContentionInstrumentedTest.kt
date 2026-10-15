package de.x0bubbuff.needlebub.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.x0bubbuff.needlebub.developer.DeveloperDataStore
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackManifest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Controlled contention, using only synthetic records and no native model. Emulator only. */
@RunWith(AndroidJUnit4::class)
class RuntimeContentionInstrumentedTest {
    private class Host : RuntimeHost {
        @Volatile var answer = false
        override fun bind(generation: Int, listener: RuntimeHostListener): Boolean {
            listener.onConnected(generation)
            return true
        }
        override fun unbind(generation: Int) = Unit
        override fun cancel(requestId: String, generation: Int) = Unit
        override fun dispatch(request: RuntimeRequest, pack: InstalledPack, generation: Int,
            onResult: (RuntimeResponse) -> Unit) {
            if (answer) onResult(RuntimeResponse(requestId = request.requestId, status = "NO_MATCH",
                toolName = null, resultJson = null, errorCode = null, durationMs = 1,
                coldLoad = false, pssKb = 0))
        }
    }

    @Test
    fun deadlinesAndRecoverySurviveMainLooperAndCaptureStoreContention() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DeveloperDataStore(context)
        val manifest = PackManifest(1, "de.x0bubbuff.needlebub.contention", "1.0.0", "Synthetic",
            "Tests", "No real model", "MIT", PackManifest.ENGINE_ABI, "model.cact", 1,
            "0".repeat(64), "{{input}}", setOf("notification"), emptyMap())
        val pack = InstalledPack(manifest, File(context.cacheDir, "contention-fixture"), true)
        try {
            for (capture in listOf(false, true)) {
                val host = Host()
                val broker = RuntimeBroker(host)
                val stalled = CountDownLatch(1)
                val release = CountDownLatch(1)
                val mainFree = CountDownLatch(1)
                val firstStarted = CountDownLatch(1)
                val firstDone = CountDownLatch(1)
                val secondDone = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                val id = if (capture) store.insertCapture(JSONObject()
                    .put("capturedAtEpochMs", System.currentTimeMillis())
                    .put("packageName", "com.example.synthetic")
                    .put("notificationKeyHash", "contention-synthetic")
                    .put("title", "Synthetic")
                    .put("body", "No real user content")) else null
                if (capture) assertNotNull(id)
                Handler(Looper.getMainLooper()).post {
                    try {
                        synchronized(store) {
                            stalled.countDown()
                            release.await(3, TimeUnit.SECONDS)
                        }
                    } finally { mainFree.countDown() }
                }
                try {
                    assertTrue(stalled.await(2, TimeUnit.SECONDS))
                    val firstAt = SystemClock.elapsedRealtime()
                    assertEquals(RuntimeAdmission.ACCEPTED, broker.infer("first-$capture", pack,
                        "synthetic", 250, surface = "notification") { response ->
                        try {
                            assertEquals(ErrorCodes.TIMEOUT, response.errorCode)
                            firstStarted.countDown()
                            if (id != null) store.attachRuntime(id, JSONObject().put("status", "ERROR"))
                        } catch (error: Throwable) { failure.set(error) }
                        finally { firstDone.countDown() }
                    })
                    assertTrue("deadline must fire while main is stalled", firstStarted.await(1, TimeUnit.SECONDS))
                    val firstMs = SystemClock.elapsedRealtime() - firstAt
                    val secondAt = SystemClock.elapsedRealtime()
                    assertEquals(RuntimeAdmission.ACCEPTED, broker.infer("second-$capture", pack,
                        "synthetic", 250, surface = "notification") { response ->
                        try { assertEquals(ErrorCodes.TIMEOUT, response.errorCode) }
                        catch (error: Throwable) { failure.set(error) }
                        finally { secondDone.countDown() }
                    })
                    assertTrue("blocked capture persistence must not delay another deadline",
                        secondDone.await(1, TimeUnit.SECONDS))
                    val secondMs = SystemClock.elapsedRealtime() - secondAt
                    assertEquals("main must remain stalled until explicitly released", 1L, mainFree.count)
                    if (capture) assertEquals("capture callback must still await the store", 1L, firstDone.count)
                    release.countDown()
                    assertTrue(mainFree.await(2, TimeUnit.SECONDS))
                    assertTrue(firstDone.await(2, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("callback failed", it) }
                    host.answer = true
                    val recovered = CountDownLatch(1)
                    assertEquals(RuntimeAdmission.ACCEPTED, broker.infer("recovered-$capture", pack,
                        "synthetic", 500, surface = "notification") { response ->
                        try { assertEquals("NO_MATCH", response.status) }
                        catch (error: Throwable) { failure.set(error) }
                        finally { recovered.countDown() }
                    })
                    assertTrue(recovered.await(1, TimeUnit.SECONDS))
                    failure.get()?.let { throw AssertionError("recovery failed", it) }
                    Log.i("NeedleContentionTest", "capture=$capture firstDeadlineMs=$firstMs secondDeadlineMs=$secondMs recovery=passed")
                } finally {
                    release.countDown()
                    mainFree.await(4, TimeUnit.SECONDS)
                    firstDone.await(3, TimeUnit.SECONDS)
                    broker.shutdown()
                }
            }
        } finally {
            store.clearCaptures()
            store.close()
        }
    }
}
