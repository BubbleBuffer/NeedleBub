package de.x0bubbuff.needlebub.developer

import org.json.JSONObject
import java.io.IOException
import java.io.Writer

/**
 * Raised by a source that cannot produce a consistent snapshot.
 *
 * [reason] is the machine-readable code used by the ADB stream; [message] is the
 * human-readable text that reaches the supported encrypted-export path (the plugin
 * rejects with it, and nothing is deleted when an export fails).
 */
class CaptureSnapshotUnavailable(
    val reason: String,
    message: String = reason,
) : Exception(message)

/**
 * Immutable capture snapshot taken under the store lock.
 *
 * [records] holds owned copies of the encrypted payloads in stream order, so later
 * concurrent upserts cannot change what a started export contains. [recordCount] always
 * equals `records.size`, which is what makes the manifest count trustworthy.
 */
data class CaptureExportSnapshot(
    val records: List<ByteArray>,
    val recordCount: Int,
    val lifecycleCount: Int,
    val observedRevisionCount: Long,
)

/**
 * Read-only view of the encrypted capture store used by the developer export.
 *
 * [exportSnapshot] runs under the store lock and must stay short; [decryptRecord] runs on
 * the streaming thread outside that lock, so a blocked consumer can never stall live
 * notification capture.
 */
interface CaptureExportSource {
    /** @throws CaptureSnapshotUnavailable when no consistent snapshot can be produced. */
    fun exportSnapshot(): CaptureExportSnapshot

    /** Decrypted JSONL record for one snapshot payload; never called under the store lock. */
    fun decryptRecord(payload: ByteArray): String
}

/**
 * Streams a developer capture snapshot to a caller-owned sink without changing the
 * on-the-wire format.
 *
 * Containment contract: a consumer disconnect, an I/O failure, an expired or revoked ADB
 * grant, an oversized snapshot, a payload that cannot be decrypted, a sink that cannot be
 * created and a sink that fails while flushing or closing never escape as an exception.
 * The caller learns what happened from [Result]. When the sink died before the records
 * were handed over, the stream is a truncated prefix and the manifest record count exposes
 * it; a failure at the final flush or close can leave `written == recordCount` while still
 * reporting [Result.Incomplete], because the consumer may never have received the tail.
 *
 * [Exception] is contained by design; JVM-level [Error]s are deliberately not swallowed.
 */
object CaptureStream {
    const val TYPE = "needlebub.capture.adb"
    const val FORMAT_VERSION = 3

    /**
     * Default upper bound for the encrypted payload copies held by one snapshot. The store
     * keeps at most 10,000 records, so a realistic export is a few megabytes; a store that
     * would need more fails loudly with [REASON_SNAPSHOT_TOO_LARGE] instead of risking an
     * out-of-memory kill that would also stop normal OTP delivery. The bound is never a
     * truncation: both the ADB stream and the encrypted archive refuse the export rather
     * than omit records.
     */
    const val MAX_SNAPSHOT_BYTES = 32L * 1024 * 1024

    const val REASON_SINK_FAILURE = "SINK_FAILURE"
    const val REASON_SINK_UNAVAILABLE = "SINK_UNAVAILABLE"
    const val REASON_GRANT_REVOKED = "GRANT_REVOKED"
    const val REASON_RECORD_UNAVAILABLE = "RECORD_UNAVAILABLE"
    const val REASON_SNAPSHOT_TOO_LARGE = "SNAPSHOT_TOO_LARGE"
    const val REASON_INTERNAL_FAILURE = "INTERNAL_FAILURE"

    sealed interface Result {
        /** The stream carries exactly [recordCount] records after the manifest line. */
        data class Complete(val recordCount: Int) : Result

        /**
         * The stream did not complete. [written] is the number of records handed to the
         * sink, normally fewer than [recordCount] (a truncated prefix the reading tool
         * detects); it stays equal to [recordCount] when only the final flush or close
         * failed, because the consumer may still not have received the tail.
         */
        data class Incomplete(val written: Int, val recordCount: Int, val reason: String) : Result
    }

    /**
     * Writes the manifest line and then every snapshot record into [writer].
     *
     * The writer is owned by the caller: this function never closes it and never takes over
     * its lifecycle. It never throws for sink, grant or store failures.
     */
    fun stream(
        writer: Writer,
        source: CaptureExportSource,
        canContinue: () -> Boolean,
    ): Result {
        var written = 0
        var expected = 0
        return try {
            if (!canContinue()) return Result.Incomplete(written, expected, REASON_GRANT_REVOKED)
            val snapshot = try {
                source.exportSnapshot()
            } catch (error: CaptureSnapshotUnavailable) {
                return Result.Incomplete(written, expected, error.reason)
            }
            expected = snapshot.recordCount
            if (snapshot.records.size != expected) {
                // A manifest that cannot be satisfied must never be written.
                return Result.Incomplete(written, expected, REASON_INTERNAL_FAILURE)
            }
            if (!canContinue()) return Result.Incomplete(written, expected, REASON_GRANT_REVOKED)
            writer.appendLine(manifest(snapshot))
            for (payload in snapshot.records) {
                if (!canContinue()) return Result.Incomplete(written, expected, REASON_GRANT_REVOKED)
                val record = try {
                    source.decryptRecord(payload)
                } catch (_: Exception) {
                    return Result.Incomplete(written, expected, REASON_RECORD_UNAVAILABLE)
                }
                if (record.isBlank()) return Result.Incomplete(written, expected, REASON_RECORD_UNAVAILABLE)
                // Decryption can be slow: a grant revoked while the key was busy must not
                // authorize the write that follows.
                if (!canContinue()) return Result.Incomplete(written, expected, REASON_GRANT_REVOKED)
                writer.appendLine(record)
                written++
            }
            if (!canContinue()) return Result.Incomplete(written, expected, REASON_GRANT_REVOKED)
            writer.flush()
            Result.Complete(written)
        } catch (_: IOException) {
            Result.Incomplete(written, expected, REASON_SINK_FAILURE)
        } catch (_: Exception) {
            Result.Incomplete(written, expected, REASON_INTERNAL_FAILURE)
        }
    }

    /**
     * Owns the whole sink lifecycle for [stream]. Creating, flushing and closing the sink
     * are all contained, and a failed flush or close is reported instead of a completion
     * that the consumer may not have received.
     *
     * When a stream aborts while the sink is still usable (for example because the grant
     * expired), closing the sink flushes the already-authorized prefix so the consumer can
     * see the manifest count mismatch; a broken sink's close failure is contained.
     */
    fun run(
        source: CaptureExportSource,
        openWriter: () -> Writer,
        canContinue: () -> Boolean,
    ): Result {
        val writer = try {
            openWriter()
        } catch (_: Exception) {
            return Result.Incomplete(0, 0, REASON_SINK_UNAVAILABLE)
        }
        var result = try {
            stream(writer, source, canContinue)
        } catch (_: Exception) {
            Result.Incomplete(0, 0, REASON_INTERNAL_FAILURE)
        }
        try {
            writer.close()
        } catch (_: Exception) {
            val complete = result as? Result.Complete
            if (complete != null) {
                result = Result.Incomplete(complete.recordCount, complete.recordCount, REASON_SINK_FAILURE)
            }
        }
        return result
    }

    fun manifest(
        snapshot: CaptureExportSnapshot,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String = JSONObject()
        .put("type", TYPE)
        .put("formatVersion", FORMAT_VERSION)
        .put("exportedAtEpochMs", exportedAtEpochMs)
        .put("recordCount", snapshot.recordCount)
        .put("lifecycleCount", snapshot.lifecycleCount)
        .put("observedRevisionCount", snapshot.observedRevisionCount)
        .toString()
}
