package de.x0bubbuff.needlebub.developer

import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.x0bubbuff.needlebub.NeedleBubApplication
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * On-device regression coverage for the developer capture pipe.
 *
 * These tests exercise the real [AdbCaptureProvider] worker over real
 * [ParcelFileDescriptor] pipes, including a real shell consumer started through
 * UiAutomation, so a consumer disconnect, a revoked grant or a failing close would show up
 * as a process death instead of only as a helper result. The app is authenticated and
 * granted programmatically by the fixture on a dedicated emulator; no user data exists.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class AdbCaptureStreamingInstrumentedTest {
    private lateinit var app: NeedleBubApplication
    private var previousUnlocked = false
    private var previousAuthenticated = false

    private val store get() = app.developerDataStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        previousUnlocked = app.developerDataSettings.unlocked
        previousAuthenticated = app.developerDataSettings.labAuthenticated
        store.clearCaptures()
    }

    @After
    fun tearDown() {
        app.adbCaptureAccess.revoke()
        app.developerDataSettings.labAuthenticated = previousAuthenticated
        app.developerDataSettings.unlocked = previousUnlocked
        store.clearCaptures()
    }

    @Test
    fun streamsARealisticRecordCountOverARealPipe() {
        val pipe = ParcelFileDescriptor.createPipe()
        val result = AtomicReference<CaptureStream.Result>()
        val worker = thread(name = "instrumented-export") {
            result.set(AdbCaptureProvider.streamCaptures(SyntheticSource(), pipe[1]) { true })
        }

        val text = readAll(pipe[0])
        worker.join(WORKER_TIMEOUT_MS)

        assertFalse("export worker did not finish", worker.isAlive)
        assertEquals(CaptureStream.Result.Complete(REALISTIC_RECORDS), result.get())
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(REALISTIC_RECORDS + 1, lines.size)
        assertEquals(REALISTIC_RECORDS, JSONObject(lines.first()).getInt("recordCount"))
        val ids = HashSet<String>(REALISTIC_RECORDS)
        lines.drop(1).forEach { line -> assertTrue(ids.add(JSONObject(line).getString("id"))) }
        assertEquals(REALISTIC_RECORDS, ids.size)
    }

    @Test
    fun aConsumerThatClosesARealPipeMidStreamCannotCrashTheWorker() {
        val pipe = ParcelFileDescriptor.createPipe()
        val result = AtomicReference<CaptureStream.Result>()
        val worker = thread(name = "instrumented-export") {
            result.set(AdbCaptureProvider.streamCaptures(SyntheticSource(), pipe[1]) { true })
        }

        val reader = BufferedReader(
            InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), Charsets.UTF_8),
        )
        var lines = 0
        while (lines < OBSERVED_TRUNCATION_RECORDS && reader.readLine() != null) lines++
        reader.close()
        worker.join(WORKER_TIMEOUT_MS)

        assertEquals(OBSERVED_TRUNCATION_RECORDS, lines)
        assertFalse("export worker survived the consumer disconnect", worker.isAlive)
        val outcome = result.get()
        assertTrue("expected an incomplete stream, got $outcome", outcome is CaptureStream.Result.Incomplete)
        outcome as CaptureStream.Result.Incomplete
        assertEquals(CaptureStream.REASON_SINK_FAILURE, outcome.reason)
        assertEquals(REALISTIC_RECORDS, outcome.recordCount)
        assertTrue("worker stopped before the consumer did", outcome.written >= OBSERVED_TRUNCATION_RECORDS)

        // The process is still healthy: a fresh export over a new pipe completes.
        val followUpPipe = ParcelFileDescriptor.createPipe()
        val followUp = AtomicReference<CaptureStream.Result>()
        val followUpWorker = thread(name = "instrumented-export-follow-up") {
            followUp.set(AdbCaptureProvider.streamCaptures(SyntheticSource(count = 3), followUpPipe[1]) { true })
        }
        readAll(followUpPipe[0])
        followUpWorker.join(WORKER_TIMEOUT_MS)
        assertEquals(CaptureStream.Result.Complete(3), followUp.get())
    }

    @Test
    fun aRevokedGrantMidStreamTruncatesARealPipe() {
        val pipe = ParcelFileDescriptor.createPipe()
        var checks = 0
        val result = AtomicReference<CaptureStream.Result>()
        val worker = thread(name = "instrumented-export") {
            result.set(
                AdbCaptureProvider.streamCaptures(SyntheticSource(count = 3), pipe[1]) { ++checks <= 5 },
            )
        }

        val text = readAll(pipe[0])
        worker.join(WORKER_TIMEOUT_MS)

        assertFalse(worker.isAlive)
        assertEquals(CaptureStream.Result.Incomplete(1, 3, CaptureStream.REASON_GRANT_REVOKED), result.get())
        assertEquals(2, text.lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test
    fun theRealProviderStreamsToAShellConsumer() {
        insertRecords(STORE_RECORDS)
        assertEquals(STORE_RECORDS, store.summary().count)
        authenticate()

        val lines = readAll(shellPull(piped = true)).lineSequence().filter { it.isNotBlank() }.toList()

        assertEquals(STORE_RECORDS + 1, lines.size)
        assertEquals(STORE_RECORDS, JSONObject(lines.first()).getInt("recordCount"))
        lines.drop(1).forEach { line -> assertTrue(JSONObject(line).has("id")) }
    }

    @Test
    fun theRealProviderSurvivesAConsumerThatClosesMidStream() {
        insertRecords(STORE_RECORDS)
        authenticate()

        val reader = BufferedReader(
            InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(shellPull()), Charsets.UTF_8),
        )
        var lines = 0
        while (lines < SHELL_EARLY_CLOSE_LINES && reader.readLine() != null) lines++
        reader.close()
        assertEquals(SHELL_EARLY_CLOSE_LINES, lines)

        // A fresh pull over a new pipe must still work: the disconnected worker neither
        // crashed the process nor left the store locked.
        val text = readAll(shellPull())
        assertEquals(STORE_RECORDS + 1, text.lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test
    fun theRealProviderRejectsARevokedOrUnauthenticatedCaller() {
        insertRecords(2)

        authenticate()
        app.adbCaptureAccess.revoke()
        assertFalse(readAll(shellPull()).contains(CaptureStream.TYPE))

        authenticate()
        app.developerDataSettings.labAuthenticated = false
        assertFalse(readAll(shellPull()).contains(CaptureStream.TYPE))
    }

    @Test
    fun theProviderStillRejectsEveryNonShellCaller() {
        authenticate()
        val provider = AdbCaptureProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = captureUri().removePrefix("content://").substringBefore('/')
            packageName = app.packageName
            applicationInfo = app.applicationInfo
            exported = true
        })

        assertNotEquals(AdbCaptureAccess.SHELL_UID, Binder.getCallingUid())
        val failure = assertThrows(FileNotFoundException::class.java) {
            provider.openFile(Uri.parse(captureUri()), "r")
        }
        assertTrue(
            "expected the grant gate to reject the caller, got: ${failure.message}",
            failure.message.orEmpty().contains("ADB pull grant"),
        )
    }

    @Test
    fun anOversizedSnapshotIsRefusedInsteadOfTruncated() {
        insertRecords(2)
        val limited = DeveloperDataStore(app, maxSnapshotBytes = 1L)
        try {
            val failure = assertThrows(CaptureSnapshotUnavailable::class.java) { limited.exportSnapshot() }
            assertEquals(CaptureStream.REASON_SNAPSHOT_TOO_LARGE, failure.reason)

            val sink = StringWriter()
            val streamed = limited.writePlaintextJsonl(sink) { true }
            assertEquals(
                CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_SNAPSHOT_TOO_LARGE),
                streamed,
            )
            assertEquals("", sink.toString())

            assertThrows(CaptureSnapshotUnavailable::class.java) {
                limited.encryptedExport("synthetic-passphrase".toCharArray())
            }
        } finally {
            limited.close()
        }

        // The unbounded store still reports and exports every record: the refusal is a
        // failure, never a silent omission.
        assertEquals(2, store.summary().count)
        assertEquals(CaptureStream.Result.Complete(2), store.writePlaintextJsonl(StringWriter()) { true })
    }

    private fun authenticate() {
        app.developerDataSettings.unlocked = true
        app.developerDataSettings.labAuthenticated = true
        app.adbCaptureAccess.grant()
    }

    private fun captureUri() = "content://${app.packageName}.developer/captures"

    /**
     * Real shell consumer: the command runs as the shell UID, exactly like an adb pull.
     * [piped] adds the `| cat` hop that the host pull command uses.
     */
    private fun shellPull(piped: Boolean = false): ParcelFileDescriptor {
        val read = "content read --uri ${captureUri()}"
        // UiAutomation tokenizes argv; it does not itself interpret shell pipelines.
        // Keep the sh -c argument a single token, then let the shell expand IFS.
        val command = if (piped) "sh -c ${read.replace(" ", "\${IFS}")}|cat" else read
        return InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
    }

    private fun readAll(descriptor: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }

    private fun insertRecords(count: Int) {
        repeat(count) { index ->
            store.upsertCapture(JSONObject()
                .put("capturedAtEpochMs", System.currentTimeMillis() + index)
                .put("lifecycleKey", "stream-lifecycle-$index")
                .put("itemKey", "stream-item-$index")
                .put("packageName", "com.example.sender")
                .put("title", "Synthetic $index")
                .put("body", "Synthetic body ".repeat(50) + index))
        }
    }

    /**
     * Volume source. The real store is filled with [STORE_RECORDS] only: the per-insert
     * retention prune makes thousands of Keystore-backed inserts needlessly slow, while
     * the transfer behaviour under test depends on bytes and records, not on the origin.
     */
    private class SyntheticSource(
        private val count: Int = REALISTIC_RECORDS,
        private val body: String = "Synthetic body ".repeat(50),
    ) : CaptureExportSource {
        override fun exportSnapshot(): CaptureExportSnapshot = CaptureExportSnapshot(
            records = List(count) { index -> payload(index) },
            recordCount = count,
            lifecycleCount = count,
            observedRevisionCount = count.toLong(),
        )

        override fun decryptRecord(payload: ByteArray): String = payload.toString(Charsets.UTF_8)

        private fun payload(index: Int): ByteArray = JSONObject()
            .put("schema", "needlebub.capture.record.v3")
            .put("id", "synthetic-$index")
            .put("packageName", "com.example.sender")
            .put("title", "Synthetic $index")
            .put("body", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private companion object {
        /** The manifest count that was truncated in the field pull. */
        const val REALISTIC_RECORDS = 4711

        /** 192 complete records were recovered from the field pull before truncation. */
        const val OBSERVED_TRUNCATION_RECORDS = 192

        const val STORE_RECORDS = 250
        const val SHELL_EARLY_CLOSE_LINES = 60
        const val WORKER_TIMEOUT_MS = 60_000L
    }
}
