package de.x0bubbuff.needlebub.gateway

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import de.x0bubbuff.needlebub.NeedleBubApplication
import de.x0bubbuff.needlebub.packs.PackStore
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import java.util.concurrent.ConcurrentHashMap

class InferenceGatewayService : Service() {
    private lateinit var packStore: PackStore
    private lateinit var broker: RuntimeBroker
    private val limiter = UidRateLimiter()
    private val requestOwners = ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        val app = application as NeedleBubApplication
        packStore = app.packStore
        broker = app.runtime
    }

    private val binder = object : IInferenceGateway.Stub() {
        override fun listCapabilities(): MutableList<CapabilityInfo> {
            val identity = Binder.clearCallingIdentity()
            return try {
                packStore.list().filter { "external" in it.manifest.surfaces }.map { pack ->
                    CapabilityInfo(
                        pack.manifest.id,
                        pack.manifest.name,
                        pack.manifest.description,
                        pack.manifest.version,
                        pack.verified,
                        pack.manifest.outputs.keys.toList(),
                    )
                }.toMutableList()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun infer(request: InferenceRequest, callback: IInferenceCallback) {
            val uid = Binder.getCallingUid()
            val decision = limiter.acquire(uid)
            if (decision != RateDecision.ALLOWED) {
                deliver(callback, errorResponse(request, if (decision == RateDecision.BUSY) ErrorCodes.BUSY else ErrorCodes.RATE_LIMITED))
                return
            }
            if (request.requestId.isBlank() || request.requestId.length > 64 || request.input.toByteArray().size > MAX_INPUT_BYTES) {
                limiter.finish(uid)
                deliver(callback, errorResponse(request, ErrorCodes.INPUT_TOO_LARGE))
                return
            }

            val identity = Binder.clearCallingIdentity()
            try {
                val pack = packStore.findExternal(request.capabilityId)
                if (pack == null) {
                    limiter.finish(uid)
                    deliver(callback, errorResponse(request, ErrorCodes.PACK_NOT_FOUND))
                    return
                }
                val internalId = "$uid:${request.requestId}"
                requestOwners[internalId] = uid
                val accepted = broker.infer(internalId, pack, request.input, request.timeoutMs) { runtime ->
                    requestOwners.remove(internalId)
                    limiter.finish(uid)
                    val matched = runtime.status == "OK" && runtime.resultJson != null
                    val outputs = OutputMapper.map(pack.manifest, matched, runtime.toolName, runtime.resultJson, runtime.errorCode)
                    val response = InferenceResponse(
                        request.requestId,
                        runtime.status,
                        matched,
                        runtime.toolName,
                        runtime.resultJson,
                        outputs,
                        runtime.errorCode,
                        pack.manifest.id,
                        pack.manifest.version,
                        runtime.durationMs,
                    )
                    Log.i(TAG, "pack=${pack.manifest.id}@${pack.manifest.version} status=${response.status} error=${response.errorCode ?: "none"} durationMs=${response.durationMs}")
                    deliver(callback, response)
                }
                if (!accepted) {
                    requestOwners.remove(internalId)
                    limiter.finish(uid)
                    deliver(callback, errorResponse(request, ErrorCodes.BUSY))
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun cancel(requestId: String) {
            val uid = Binder.getCallingUid()
            val internalId = "$uid:$requestId"
            if (requestOwners[internalId] == uid) broker.cancel(internalId)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun deliver(callback: IInferenceCallback, response: InferenceResponse) {
        try { callback.onResult(response) } catch (_: Exception) { }
    }

    private fun errorResponse(request: InferenceRequest, code: String) = InferenceResponse(
        request.requestId,
        if (code == ErrorCodes.NO_MATCH) "NO_MATCH" else "ERROR",
        false,
        null,
        null,
        mapOf(
            "nb_matched" to "false",
            "nb_tool" to "",
            "nb_result_json" to "",
            "nb_error_code" to code,
        ),
        code,
        request.capabilityId,
        null,
        0L,
    )

    private companion object {
        const val TAG = "NeedleGateway"
        const val MAX_INPUT_BYTES = 16 * 1024
    }
}
