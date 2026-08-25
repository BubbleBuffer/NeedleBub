package de.x0bubbuff.needlebub.developer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackManifest
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeveloperDataStoreInstrumentedTest {
    private lateinit var store: DeveloperDataStore

    @Before
    fun setUp() {
        store = DeveloperDataStore(ApplicationProvider.getApplicationContext())
        store.clearCaptures()
    }

    @After
    fun tearDown() {
        store.clearCaptures()
        store.close()
    }

    @Test
    fun insertsAndUpdatesAnEncryptedCaptureWithAndroidKeystore() {
        val id = store.insertCapture(JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", "com.example.sender")
            .put("notificationKeyHash", "fixture-key")
            .put("title", "Security code")
            .put("body", "Your code is 123456"))

        assertNotNull(id)
        store.attachRuntime(requireNotNull(id), JSONObject()
            .put("status", "OK")
            .put("matched", false)
            .put("durationMs", 42))
        assertEquals(1, store.summary().count)
    }

    @Test
    fun attachesAnAsynchronousRuntimeFailureWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = "malformed-model".encodeToByteArray()
        val directory = File(context.cacheDir, "runtime-callback-pack").apply {
            deleteRecursively()
            mkdirs()
        }
        File(directory, "model.cact").writeBytes(model)
        File(directory, "tools.json").writeText(TOOLS)
        val manifest = PackManifest.parse("""
            {
              "formatVersion":1,
              "id":"de.x0bubbuff.needlebub.test",
              "version":"1.0.0",
              "name":"Runtime callback test",
              "author":"BubbleBuffer",
              "description":"Exercises a failed isolated runtime callback.",
              "license":"MIT",
              "engine":{"abi":"${PackManifest.ENGINE_ABI}"},
              "model":{"path":"model.cact","size":${model.size},"sha256":"${model.sha256()}"},
              "queryTemplate":"{{input}}",
              "surfaces":["external"],
              "outputs":{}
            }
        """.trimIndent())
        val id = requireNotNull(store.insertCapture(JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", "com.example.sender")
            .put("notificationKeyHash", "runtime-fixture")
            .put("title", "Runtime fixture")
            .put("body", "No one-time code here")))
        val completed = CountDownLatch(1)
        val pack = InstalledPack(manifest, directory, true)
        val broker = RuntimeBroker(context)

        val accepted = broker.infer(
            "instrumented-runtime-failure",
            pack,
            "Sender: Fixture\nMessage: No one-time code here",
            5_000,
            surface = "notification",
        ) { response ->
            assertEquals(ErrorCodes.PACK_INVALID, response.errorCode)
            store.attachRuntime(id, JSONObject()
                .put("status", response.status)
                .put("errorCode", response.errorCode)
                .put("durationMs", response.durationMs))
            completed.countDown()
        }

        org.junit.Assert.assertTrue(accepted)
        org.junit.Assert.assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertEquals(1, store.summary().count)
        directory.deleteRecursively()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOOLS = """{"formatVersion":1,"tools":[{"name":"extract_otp","description":"Extract an OTP.","parameters":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}}]}"""
    }
}
