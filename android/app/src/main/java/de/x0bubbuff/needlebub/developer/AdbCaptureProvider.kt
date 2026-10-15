package de.x0bubbuff.needlebub.developer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.util.Log
import de.x0bubbuff.needlebub.NeedleBubApplication
import java.io.FileNotFoundException
import kotlin.concurrent.thread

class AdbCaptureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (uri.path == CAPTURE_PATH) MIME_TYPE else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != CAPTURE_PATH || mode != "r") {
            throw FileNotFoundException("Unsupported NeedleBub developer capture request")
        }
        val app = context?.applicationContext as? NeedleBubApplication
            ?: throw FileNotFoundException("NeedleBub is unavailable")
        val callerUid = Binder.getCallingUid()
        if (!app.adbCaptureAccess.canRead(
                callerUid,
                app.developerDataSettings.unlocked,
                app.developerDataSettings.labAuthenticated,
            )
        ) {
            throw FileNotFoundException("Enable the temporary ADB pull grant in the authenticated Notification Lab")
        }

        val pipe = ParcelFileDescriptor.createPipe()
        try {
            thread(name = WORKER_NAME, isDaemon = true) {
                val result = try {
                    streamCaptures(app.developerDataStore, pipe[1]) {
                        // Re-checked at every step: revoking the grant or closing the Lab
                        // stops the stream instead of authorizing a later write.
                        app.adbCaptureAccess.canRead(
                            callerUid,
                            app.developerDataSettings.unlocked,
                            app.developerDataSettings.labAuthenticated,
                        )
                    }
                } catch (_: Throwable) {
                    // Last line of defence for this worker thread only: a developer export
                    // must never reach the default handler and take the whole process down.
                    closeQuietly(pipe[1])
                    Log.w(TAG, "surface=adb-capture status=incomplete reason=THREAD_FAILURE")
                    return@thread
                }
                report(result)
            }
        } catch (_: Throwable) {
            closeQuietly(pipe[0])
            closeQuietly(pipe[1])
            throw FileNotFoundException("NeedleBub could not start the developer capture stream")
        }
        return pipe[0]
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("NeedleBub developer captures are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("NeedleBub developer captures are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("NeedleBub developer captures are read-only")

    companion object {
        internal const val WORKER_NAME = "NeedleBubAdbCapture"
        private const val TAG = "NeedleAdbCapture"
        private const val CAPTURE_PATH = "/captures"
        private const val MIME_TYPE = "application/vnd.needlebub.capture+jsonl"

        /**
         * Runs the capture export worker over [writeEnd] and returns its outcome instead of
         * throwing.
         *
         * The worker owns [writeEnd]: the sink closes it on every outcome, and a backstop
         * close covers a failure before the sink took ownership. The write end is closed
         * only after the outcome is known, so an export that stopped early reaches the
         * consumer as a truncated stream whose manifest count exposes it, while a failure
         * at the final flush or close is still reported as incomplete.
         */
        internal fun streamCaptures(
            source: CaptureExportSource,
            writeEnd: ParcelFileDescriptor,
            canContinue: () -> Boolean,
        ): CaptureStream.Result {
            val result = try {
                CaptureStream.run(
                    source,
                    { ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).bufferedWriter(Charsets.UTF_8) },
                    canContinue,
                )
            } catch (_: Exception) {
                CaptureStream.Result.Incomplete(0, 0, CaptureStream.REASON_INTERNAL_FAILURE)
            } finally {
                // Idempotent backstop for a failure before the sink took ownership.
                closeQuietly(writeEnd)
            }
            return result
        }

        /**
         * Records a content-free outcome. The reason code and counts are enough to tell a
         * consumer disconnect from a revoked grant without logging any capture content.
         */
        internal fun report(result: CaptureStream.Result) {
            if (result is CaptureStream.Result.Incomplete) {
                Log.w(
                    TAG,
                    "surface=adb-capture status=incomplete reason=${result.reason} " +
                        "records=${result.written} expected=${result.recordCount}",
                )
            }
        }

        private fun closeQuietly(descriptor: ParcelFileDescriptor) {
            try {
                descriptor.close()
            } catch (_: Exception) {
                // Nothing left to release or report.
            }
        }
    }
}
