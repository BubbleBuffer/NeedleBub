package de.x0bubbuff.needlebub.developer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Regression coverage for the developer capture stream: a failing consumer, a revoked
 * grant, an oversized snapshot and a failing flush or close must never escape, the
 * manifest count must always match the records actually written, and a parked sink must
 * never hold the store monitor.
 */
class CaptureStreamTest {
    private class FakeSource(
        private val records: List<String>,
        private val broken: Set<Int> = emptySet(),
    ) : CaptureExportSource {
        @Volatile
        var insideCall = false

        override fun exportSnapshot(): CaptureExportSnapshot = synchronized(this) {
            insideCall = true
            try {
                CaptureExportSnapshot(
                    records = records.mapIndexed { index, text ->
                        (if (index in broken) BROKEN_PAYLOAD else text.toByteArray(Charsets.UTF_8))
                    },
                    recordCount = records.size,
                    lifecycleCount = records.size,
                    observedRevisionCount = records.size.toLong(),
                )
            } finally {
                insideCall = false
            }
        }

        override fun decryptRecord(payload: ByteArray): String = synchronized(this) {
            insideCall = true
            try {
                if (payload.contentEquals(BROKEN_PAYLOAD)) throw IllegalStateException("CIPHER_BROKEN")
                payload.toString(Charsets.UTF_8)
            } finally {
                insideCall = false
            }
        }

        private companion object {
            val BROKEN_PAYLOAD = "BROKEN".toByteArray(Charsets.UTF_8)
        }
    }

    /** Sink that fails once [maxCompleteLines] terminated lines have been written. */
    private open class LineLimitedSink(
        private val maxCompleteLines: Int,
        private val failFlush: Boolean = false,
    ) : Writer() {
        private val text = StringBuilder()
        private var lines = 0
        private var failed = false

        val writtenText: String get() = text.toString()

        /** Lines terminated by a newline; a truncated trailing record does not count. */
        val completeLines: Int get() = text.count { it == '\n' }

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (failed) throw IOException("SINK_CLOSED")
            for (index in off until off + len) {
                val character = cbuf[index]
                if (character == '\n') {
                    if (lines >= maxCompleteLines) {
                        failed = true
                        throw IOException("SINK_CLOSED")
                    }
                    lines++
                }
                text.append(character)
            }
        }

        override fun flush() {
            if (failed || failFlush) throw IOException("SINK_CLOSED")
        }

        override fun close() = Unit
    }

    private class CloseFailingSink : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() {
            throw IOException("CLOSE_FAILED")
        }
    }

    private class BlockingSink(
        private val source: FakeSource,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : Writer() {
        private var lines = 0
        private var parked = false

        @Volatile
        var monitorHeldDuringWrite = false

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (Thread.holdsLock(source)) monitorHeldDuringWrite = true
            if (!parked && lines >= HEADER_LINES) {
                parked = true
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            for (index in off until off + len) {
                if (cbuf[index] == '\n') lines++
            }
        }

        override fun flush() = Unit
        override fun close() = Unit

        private companion object {
            const val HEADER_LINES = 1
        }
    }

    private fun record(index: Int) = JSONObject()
        .put("schema", "needlebub.capture.record.v3")
        .put("id", "id-$index")
        .put("title", "Synthetic title $index")
        .put("body", "Synthetic synthetic body $index")
        .toString()

    private fun source(count: Int, broken: Set<Int> = emptySet()) =
        FakeSource(List(count) { record(it) }, broken)

    private fun renderedLines(writer: Writer) =
        writer.toString().lineSequence().filter { it.isNotBlank() }.toList()

    @Test
    fun aCompleteStreamMatchesItsManifestCount() {
        val sink = StringWriter()
        val result = CaptureStream.run(source(3), { sink }, { true })

        assertEquals(CaptureStream.Result.Complete(3), result)
        val lines = renderedLines(sink)
        assertEquals(4, lines.size)
        val manifest = JSONObject(lines.first())
        assertEquals(CaptureStream.TYPE, manifest.getString("type"))
        assertEquals(CaptureStream.FORMAT_VERSION, manifest.getInt("formatVersion"))
        assertEquals(3, manifest.getInt("recordCount"))
        assertEquals(3, manifest.getInt("lifecycleCount"))
        assertEquals(3L, manifest.getLong("observedRevisionCount"))
        lines.drop(1).forEachIndexed { index, line ->
            assertEquals("id-$index", JSONObject(line).getString("id"))
        }
    }

    @Test
    fun aSinkFailureStopsTheStreamAndReportsAPrefix() {
        val sink = LineLimitedSink(maxCompleteLines = 3)
        val result = CaptureStream.run(source(4), { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(2, 4, CaptureStream.REASON_SINK_FAILURE), result)
        assertEquals(CaptureStream.TYPE, JSONObject(sink.writtenText.lineSequence().first()).getString("type"))
        assertEquals(3, sink.completeLines)
    }

    @Test
    fun aConsumerThatVanishesBeforeTheFirstRecordIsContained() {
        val sink = LineLimitedSink(maxCompleteLines = 1)
        val result = CaptureStream.run(source(4), { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(0, 4, CaptureStream.REASON_SINK_FAILURE), result)
        assertEquals(1, sink.completeLines)
        assertEquals(4, JSONObject(sink.writtenText.lineSequence().first()).getInt("recordCount"))
    }

    @Test
    fun aSinkThatFailsOnlyWhileFlushingIsReportedAsIncomplete() {
        val sink = LineLimitedSink(maxCompleteLines = 64, failFlush = true)
        val result = CaptureStream.run(source(2), { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(2, 2, CaptureStream.REASON_SINK_FAILURE), result)
    }

    @Test
    fun aFailingCloseIsReportedAsIncompleteInsteadOfComplete() {
        val result = CaptureStream.run(source(2), { CloseFailingSink() }, { true })

        assertEquals(CaptureStream.Result.Incomplete(2, 2, CaptureStream.REASON_SINK_FAILURE), result)
    }

    @Test
    fun aFailingSinkInitializationIsContained() {
        val result = CaptureStream.run(source(2), { throw IOException("NO_PIPE") }, { true })

        assertEquals(CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_SINK_UNAVAILABLE), result)
    }

    @Test
    fun aThrowingStoreIsContained() {
        val broken = object : CaptureExportSource {
            override fun exportSnapshot(): CaptureExportSnapshot = throw IllegalStateException("STORE_BROKEN")
            override fun decryptRecord(payload: ByteArray): String = ""
        }
        val sink = StringWriter()
        val result = CaptureStream.run(broken, { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_INTERNAL_FAILURE), result)
        assertEquals("", sink.toString())
    }

    @Test
    fun anOversizedSnapshotFailsSafelyBeforeAnythingIsWritten() {
        val oversized = object : CaptureExportSource {
            override fun exportSnapshot(): CaptureExportSnapshot =
                throw CaptureSnapshotUnavailable(CaptureStream.REASON_SNAPSHOT_TOO_LARGE)

            override fun decryptRecord(payload: ByteArray): String = ""
        }
        val sink = StringWriter()
        val result = CaptureStream.run(oversized, { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_SNAPSHOT_TOO_LARGE), result)
        assertEquals("", sink.toString())
    }

    @Test
    fun aRevokedGrantBeforeTheSnapshotWritesNothing() {
        val sink = StringWriter()
        val result = CaptureStream.run(source(2), { sink }, { false })

        assertEquals(CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_GRANT_REVOKED), result)
        assertEquals("", sink.toString())
    }

    @Test
    fun aRevokedGrantAtThePostSnapshotCheckWritesNoManifest() {
        val sink = StringWriter()
        var checks = 0
        val result = CaptureStream.run(source(3), { sink }) { ++checks <= 1 }

        assertEquals(CaptureStream.Result.Incomplete(0, 3, CaptureStream.REASON_GRANT_REVOKED), result)
        assertEquals("", sink.toString())
    }

    @Test
    fun aRevokedGrantBeforeTheFirstRecordLeavesAManifestPrefix() {
        val sink = StringWriter()
        var checks = 0
        val result = CaptureStream.run(source(3), { sink }) { ++checks <= 2 }

        assertEquals(CaptureStream.Result.Incomplete(0, 3, CaptureStream.REASON_GRANT_REVOKED), result)
        val lines = renderedLines(sink)
        assertEquals(1, lines.size)
        assertEquals(3, JSONObject(lines.first()).getInt("recordCount"))
    }

    @Test
    fun aRevocationDuringDecryptionDoesNotAuthorizeTheWrite() {
        val sink = StringWriter()
        var checks = 0
        val result = CaptureStream.run(source(3), { sink }) { ++checks <= 3 }

        assertEquals(CaptureStream.Result.Incomplete(0, 3, CaptureStream.REASON_GRANT_REVOKED), result)
        assertEquals(1, renderedLines(sink).size)
    }

    @Test
    fun aRevokedGrantMidStreamStopsAtTheRevokedRecord() {
        val sink = StringWriter()
        var checks = 0
        val result = CaptureStream.run(source(3), { sink }) { ++checks <= 5 }

        assertEquals(CaptureStream.Result.Incomplete(1, 3, CaptureStream.REASON_GRANT_REVOKED), result)
        assertEquals(2, renderedLines(sink).size)
    }

    @Test
    fun aRevocationBeforeTheFinalFlushIsReported() {
        val sink = StringWriter()
        var checks = 0
        val result = CaptureStream.run(source(2), { sink }) { ++checks <= 6 }

        assertEquals(CaptureStream.Result.Incomplete(2, 2, CaptureStream.REASON_GRANT_REVOKED), result)
        assertEquals(3, renderedLines(sink).size)
    }

    @Test
    fun aPayloadThatCannotBeDecryptedStopsTheStreamInsteadOfSkipping() {
        val sink = StringWriter()
        val result = CaptureStream.run(source(3, broken = setOf(1)), { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(1, 3, CaptureStream.REASON_RECORD_UNAVAILABLE), result)
        assertEquals(2, renderedLines(sink).size)
    }

    @Test
    fun aSnapshotThatDisagreesWithItsManifestIsRejectedBeforeWriting() {
        val inconsistent = object : CaptureExportSource {
            override fun exportSnapshot() = CaptureExportSnapshot(
                records = listOf("only-one".toByteArray(Charsets.UTF_8)),
                recordCount = 2,
                lifecycleCount = 1,
                observedRevisionCount = 1L,
            )

            override fun decryptRecord(payload: ByteArray): String = payload.toString(Charsets.UTF_8)
        }
        val sink = StringWriter()
        val result = CaptureStream.run(inconsistent, { sink }, { true })

        assertEquals(CaptureStream.Result.Incomplete(0, 2, CaptureStream.REASON_INTERNAL_FAILURE), result)
        assertEquals("", sink.toString())
    }

    @Test
    fun streamingDoesNotCloseACallerOwnedWriter() {
        val result = CaptureStream.stream(CloseFailingSink(), source(1), { true })

        assertEquals(CaptureStream.Result.Complete(1), result)
    }

    @Test
    fun aBlockedSinkNeverHoldsTheSourceMonitor() {
        val source = FakeSource(List(4) { record(it) })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = BlockingSink(source, entered, release)
        val result = AtomicReference<CaptureStream.Result>()
        val exporter = thread(name = "blocked-export") {
            result.set(CaptureStream.run(source, { sink }, { true }))
        }

        assertTrue("export never reached the sink", entered.await(5, TimeUnit.SECONDS))
        assertFalse("source monitor held across a sink write", sink.monitorHeldDuringWrite)
        assertFalse("source call active while the sink is parked", source.insideCall)

        val reader = thread(name = "store-reader") { source.exportSnapshot() }
        reader.join(2_000)
        assertFalse("live capture store was blocked by the export sink", reader.isAlive)

        release.countDown()
        exporter.join(5_000)
        assertFalse(exporter.isAlive)
        assertEquals(CaptureStream.Result.Complete(4), result.get())
    }
}
