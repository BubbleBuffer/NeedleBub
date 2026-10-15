package de.x0bubbuff.needlebub.runtime

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import org.json.JSONObject

/**
 * Parsed engine envelope.
 *
 * [truncated] is deliberately tri-state: `true` when the envelope shows the engine stopped
 * at a limit or handed back an incomplete call, `false` when a complete envelope was
 * observed, and `null` when truncation is not observable from the response.
 */
data class RuntimeEnvelope(
    val status: String,
    val toolName: String?,
    val resultJson: String?,
    val errorCode: String?,
    val responseType: String?,
    val engineSuccess: Boolean?,
    val engineErrorCode: String?,
    val reasoning: String?,
    val callCount: Int,
    val truncated: Boolean?,
) {
    companion object {
        private val LIMIT_HINTS = listOf("length", "truncat", "max_token", "max_new", "context", "limit")

        /**
         * Maps an engine envelope to a runtime outcome.
         *
         * The incomplete-call branch is the observable part of token truncation: the engine
         * reported a tool call but the arguments are missing or unparsable, which used to be
         * reported either as `OK` with empty arguments or as an opaque `RUNTIME_CRASH`.
         */
        fun parse(envelope: String?, maxReasoningChars: Int): RuntimeEnvelope {
            if (envelope == null) {
                return RuntimeEnvelope(
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
            }
            val parsed = try {
                JSONObject(envelope)
            } catch (_: Exception) {
                return RuntimeEnvelope(
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
            }
            val responseType = parsed.optString("type").takeIf(String::isNotBlank)
            val engineSuccess = parsed.opt("success") as? Boolean
            val engineErrorCode = parsed.optString("error_code").takeIf(String::isNotBlank)
            val reasoning = parsed.optString("reasoning").takeIf(String::isNotBlank)?.take(maxReasoningChars)
            val calls = parsed.optJSONArray("function_calls")
            val callCount = calls?.length() ?: 0
            val cappedError = engineErrorCode
                ?.lowercase()
                ?.let { code -> LIMIT_HINTS.any { code.contains(it) } }
                ?: false

            if (responseType != "call" || calls == null || callCount != 1) {
                return RuntimeEnvelope(
                    status = "NO_MATCH",
                    toolName = null,
                    resultJson = null,
                    errorCode = ErrorCodes.NO_MATCH,
                    responseType = responseType,
                    engineSuccess = engineSuccess,
                    engineErrorCode = engineErrorCode,
                    reasoning = reasoning,
                    callCount = callCount,
                    truncated = if (cappedError) true else if (engineSuccess == false) null else false,
                )
            }

            val call = calls.optJSONObject(0)
            val name = call?.optString("name")?.takeIf(String::isNotBlank)
            val arguments = call?.optJSONObject("arguments")
            if (call == null || name == null || arguments == null) {
                return RuntimeEnvelope(
                    status = "ERROR",
                    toolName = name,
                    resultJson = null,
                    errorCode = ErrorCodes.OUTPUT_TRUNCATED,
                    responseType = responseType,
                    engineSuccess = engineSuccess,
                    engineErrorCode = engineErrorCode,
                    reasoning = reasoning,
                    callCount = callCount,
                    truncated = true,
                )
            }
            return RuntimeEnvelope(
                status = "OK",
                toolName = name,
                resultJson = arguments.toString(),
                errorCode = null,
                responseType = responseType,
                engineSuccess = engineSuccess,
                engineErrorCode = engineErrorCode,
                reasoning = reasoning,
                callCount = callCount,
                truncated = false,
            )
        }
    }
}
