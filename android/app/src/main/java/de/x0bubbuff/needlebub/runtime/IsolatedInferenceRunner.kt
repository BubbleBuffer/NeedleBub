package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.json.JSONObject

/**
 * Runs one request against the isolated engine and reports what actually happened.
 *
 * Independent requests are rewound, never reloaded: upstream Needle 3 keeps turn history
 * until the conversation is reset, and a retained conversation can run away on the next
 * independent query. [RuntimeEngine.rewind] keeps the mapped model, the tools and
 * [loadedPack] intact, so the only cost is the rewind itself.
 */
class IsolatedInferenceRunner(
    private val engine: RuntimeEngine,
    private val elapsedRealtime: () -> Long,
    private val pssKb: () -> Long,
    private val logLine: (String) -> Unit,
    private val maxInputBytes: Int = MAX_INPUT_BYTES,
    private val maxNewTokens: Int = MAX_NEW_TOKENS,
    private val outputCapacity: Int = OUTPUT_CAPACITY,
    private val maxReasoningChars: Int = MAX_REASONING_CHARS,
) {
    @Volatile
    private var loadedPack: String? = null

    /** Releases the mapped model; used for teardown and hard recycle, not per request. */
    fun reset() {
        engine.reset()
        loadedPack = null
    }

    @Synchronized
    fun run(request: RuntimeRequest, modelFd: Int, toolsJson: String): RuntimeResponse {
        val started = elapsedRealtime()
        var status = "ERROR"
        var errorCode: String? = ErrorCodes.RUNTIME_CRASH
        var toolName: String? = null
        var resultJson: String? = null
        var coldLoad: Boolean? = null
        var loadState: String = RuntimeLoadState.UNKNOWN
        var modelLoadMs: Long? = null
        var inferenceMs: Long? = null
        var rewound = false
        var envelope = RuntimeEnvelope(
            status = "ERROR",
            toolName = null,
            resultJson = null,
            errorCode = ErrorCodes.RUNTIME_CRASH,
            responseType = null,
            engineSuccess = null,
            engineErrorCode = null,
            reasoning = null,
            callCount = 0,
            truncated = null,
        )
        try {
            if (request.input.toByteArray(Charsets.UTF_8).size > maxInputBytes) {
                errorCode = ErrorCodes.INPUT_TOO_LARGE
            } else {
                val packKey = "${request.packId}@${request.packVersion}"
                val warm = loadedPack == packKey
                coldLoad = request.forceReload || !warm
                if (request.forceReload && loadedPack != null) {
                    engine.reset()
                    loadedPack = null
                }
                if (loadedPack != packKey) {
                    val loadStarted = elapsedRealtime()
                    if (engine.load(modelFd, request.modelSize) != 0) {
                        errorCode = ErrorCodes.PACK_INVALID
                        coldLoad = true
                        loadState = RuntimeLoadState.COLD
                        modelLoadMs = elapsedRealtime() - loadStarted
                        return respond(
                            request, status, toolName, resultJson, errorCode, started, coldLoad, loadState,
                            modelLoadMs, inferenceMs, rewound, envelope,
                        )
                    }
                    val tools = JSONObject(toolsJson).getJSONArray("tools").toString()
                    if (engine.initialize("", tools) < 0) {
                        engine.reset()
                        loadedPack = null
                        errorCode = ErrorCodes.PACK_INVALID
                        coldLoad = true
                        loadState = RuntimeLoadState.COLD
                        modelLoadMs = elapsedRealtime() - loadStarted
                        return respond(
                            request, status, toolName, resultJson, errorCode, started, coldLoad, loadState,
                            modelLoadMs, inferenceMs, rewound, envelope,
                        )
                    }
                    loadedPack = packKey
                    loadState = RuntimeLoadState.COLD
                    modelLoadMs = elapsedRealtime() - loadStarted
                } else {
                    // Same pack, previous conversation may still hold the last notification.
                    engine.rewind()
                    rewound = true
                    loadState = RuntimeLoadState.WARM
                    coldLoad = false
                }

                val query = request.queryTemplate.replace("{{input}}", request.input)
                val inferenceStarted = elapsedRealtime()
                envelope = RuntimeEnvelope.parse(
                    engine.complete(query, maxNewTokens, outputCapacity),
                    maxReasoningChars,
                )
                inferenceMs = elapsedRealtime() - inferenceStarted
                status = envelope.status
                toolName = envelope.toolName
                resultJson = envelope.resultJson
                errorCode = envelope.errorCode
            }
        } catch (_: Throwable) {
            status = "ERROR"
            errorCode = ErrorCodes.RUNTIME_CRASH
            envelope = envelope.copy(status = "ERROR", errorCode = ErrorCodes.RUNTIME_CRASH)
        }
        return respond(
            request, status, toolName, resultJson, errorCode, started, coldLoad, loadState,
            modelLoadMs, inferenceMs, rewound, envelope,
        )
    }

    @Suppress("LongParameterList")
    private fun respond(
        request: RuntimeRequest,
        status: String,
        toolName: String?,
        resultJson: String?,
        errorCode: String?,
        started: Long,
        coldLoad: Boolean?,
        loadState: String,
        modelLoadMs: Long?,
        inferenceMs: Long?,
        rewound: Boolean,
        envelope: RuntimeEnvelope,
    ): RuntimeResponse {
        val duration = elapsedRealtime() - started
        val pss = pssKb()
        // Metadata only: never request or result content.
        logLine(
            "pack=${request.packId}@${request.packVersion} surface=${request.surface} " +
                "load=${if (coldLoad == true) "cold" else if (coldLoad == false) "warm" else "unknown"} " +
                "rewound=$rewound status=$status error=${errorCode ?: "none"} durationMs=$duration pssKb=$pss " +
                "modelLoadMs=${modelLoadMs ?: -1} inferenceMs=${inferenceMs ?: -1} " +
                "truncated=${envelope.truncated ?: "unknown"} requestedAt=${request.requestedAtEpochMs} " +
                "model=${request.modelIdentity}",
        )
        return RuntimeResponse(
            requestId = request.requestId,
            status = status,
            toolName = toolName,
            resultJson = resultJson,
            errorCode = errorCode,
            durationMs = duration,
            coldLoad = coldLoad,
            pssKb = pss,
            responseType = envelope.responseType,
            engineSuccess = envelope.engineSuccess,
            engineErrorCode = envelope.engineErrorCode,
            reasoning = envelope.reasoning,
            callCount = envelope.callCount,
            loadState = loadState,
            modelLoadMs = modelLoadMs,
            inferenceMs = inferenceMs,
            requestedAtEpochMs = request.requestedAtEpochMs,
            appVersionName = request.appVersionName,
            appVersionCode = request.appVersionCode,
            modelIdentity = request.modelIdentity,
            outputTruncated = envelope.truncated,
        )
    }

    companion object {
        const val MAX_INPUT_BYTES = 16 * 1024
        const val MAX_NEW_TOKENS = 256
        const val OUTPUT_CAPACITY = 64 * 1024
        const val MAX_REASONING_CHARS = 4_096
    }
}
