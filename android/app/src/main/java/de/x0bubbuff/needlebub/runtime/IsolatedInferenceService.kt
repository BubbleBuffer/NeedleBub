package de.x0bubbuff.needlebub.runtime

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class IsolatedInferenceService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loadedPack: String? = null
    @Volatile private var activeRequestId: String? = null

    private val binder = object : IIsolatedRuntime.Stub() {
        override fun infer(
            request: RuntimeRequest,
            model: ParcelFileDescriptor,
            toolsJson: String,
            callback: IRuntimeCallback,
        ) {
            executor.execute {
                model.use { runInference(request, it, toolsJson, callback) }
            }
        }

        override fun cancel(requestId: String) {
            if (activeRequestId == requestId) {
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            cancelled += requestId
        }

        override fun reset() {
            executor.execute {
                NeedleNative.reset()
                loadedPack = null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.execute { NeedleNative.reset() }
        executor.shutdown()
        super.onDestroy()
    }

    private fun runInference(
        request: RuntimeRequest,
        model: ParcelFileDescriptor,
        toolsJson: String,
        callback: IRuntimeCallback,
    ) {
        val started = SystemClock.elapsedRealtime()
        activeRequestId = request.requestId
        var status = "ERROR"
        var errorCode: String? = ErrorCodes.RUNTIME_CRASH
        var toolName: String? = null
        var resultJson: String? = null
        var coldLoad = request.forceReload
        try {
            if (request.input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
                errorCode = ErrorCodes.INPUT_TOO_LARGE
            } else if (cancelled.remove(request.requestId)) {
                errorCode = ErrorCodes.TIMEOUT
            } else {
                val packKey = "${request.packId}@${request.packVersion}"
                coldLoad = request.forceReload || loadedPack != packKey
                if (request.forceReload && loadedPack != null) {
                    NeedleNative.reset()
                    loadedPack = null
                }
                if (loadedPack != packKey) {
                    if (NeedleNative.load(model.fd, request.modelSize) != 0) {
                        errorCode = ErrorCodes.PACK_INVALID
                        respond(callback, request, status, toolName, resultJson, errorCode, started, coldLoad)
                        activeRequestId = null
                        return
                    }
                    val tools = JSONObject(toolsJson).getJSONArray("tools").toString()
                    if (NeedleNative.initialize("", tools) < 0) {
                        NeedleNative.reset()
                        errorCode = ErrorCodes.PACK_INVALID
                        respond(callback, request, status, toolName, resultJson, errorCode, started, coldLoad)
                        activeRequestId = null
                        return
                    }
                    loadedPack = packKey
                }

                val query = request.queryTemplate.replace("{{input}}", request.input)
                val envelope = NeedleNative.complete(query, MAX_NEW_TOKENS, OUTPUT_CAPACITY)
                if (envelope == null) {
                    errorCode = ErrorCodes.RUNTIME_CRASH
                } else {
                    val parsed = JSONObject(envelope)
                    val calls = parsed.optJSONArray("function_calls")
                    if (parsed.optString("type") != "call" || calls == null || calls.length() != 1) {
                        status = "NO_MATCH"
                        errorCode = ErrorCodes.NO_MATCH
                    } else {
                        val call = calls.getJSONObject(0)
                        toolName = call.getString("name")
                        resultJson = call.optJSONObject("arguments")?.toString() ?: "{}"
                        status = "OK"
                        errorCode = null
                    }
                }
            }
        } catch (_: Throwable) {
            status = "ERROR"
            errorCode = ErrorCodes.RUNTIME_CRASH
        }
        if (!cancelled.remove(request.requestId)) {
            respond(callback, request, status, toolName, resultJson, errorCode, started, coldLoad)
        }
        activeRequestId = null
    }

    private fun respond(
        callback: IRuntimeCallback,
        request: RuntimeRequest,
        status: String,
        toolName: String?,
        resultJson: String?,
        errorCode: String?,
        started: Long,
        coldLoad: Boolean,
    ) {
        val duration = SystemClock.elapsedRealtime() - started
        val pssKb = Debug.getPss().toLong()
        Log.i(TAG, "pack=${request.packId}@${request.packVersion} surface=${request.surface} load=${if (coldLoad) "cold" else "warm"} status=$status error=${errorCode ?: "none"} durationMs=$duration pssKb=$pssKb")
        try {
            callback.onResult(RuntimeResponse(request.requestId, status, toolName, resultJson, errorCode, duration, coldLoad, pssKb))
        } catch (_: Exception) {
            // The caller may have died; never log request or result content.
        }
    }

    private companion object {
        const val TAG = "NeedleRuntime"
        const val MAX_INPUT_BYTES = 16 * 1024
        const val MAX_NEW_TOKENS = 256
        const val OUTPUT_CAPACITY = 64 * 1024
    }
}
