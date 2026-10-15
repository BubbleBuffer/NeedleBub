package de.x0bubbuff.needlebub.runtime

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Isolated process that owns the mapped model.
 *
 * All engine work happens on one executor thread. Cancellation of an in-flight request
 * kills this process, which is the only way to abort a native call that is already
 * running; the broker invalidates the binder before it tells the caller, so the death
 * that follows can never invalidate a newer generation.
 *
 * Admission and cancellation share [InferenceAdmissionGate], so a cancel can never be
 * recorded without either suppressing the start or terminating the run.
 */
class IsolatedInferenceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = InferenceAdmissionGate()

    private val runner = IsolatedInferenceRunner(
        engine = NeedleNativeEngine,
        elapsedRealtime = SystemClock::elapsedRealtime,
        pssKb = { Debug.getPss().toLong() },
        logLine = { Log.i(TAG, it) },
    )

    private val binder = object : IIsolatedRuntime.Stub() {
        override fun infer(
            request: RuntimeRequest,
            model: ParcelFileDescriptor,
            toolsJson: String,
            callback: IRuntimeCallback,
        ) {
            executor.execute {
                try {
                    model.use {
                        if (!gate.admit(request.requestId)) {
                            // Cancelled before it started: the broker already settled it.
                            return@use
                        }
                        val response = runner.run(request, it.fd, toolsJson)
                        if (gate.finish(request.requestId)) {
                            try {
                                callback.onResult(response)
                            } catch (_: Exception) {
                                // The caller may have died; never log request or result content.
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Never leave the request marked active: the broker's own deadline
                    // settles the caller, and the next request must still be admissible.
                    gate.finish(request.requestId)
                }
            }
        }

        override fun cancel(requestId: String) {
            if (gate.cancel(requestId)) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        override fun reset() {
            executor.execute { runner.reset() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.execute { runner.reset() }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "NeedleRuntime"
    }
}
