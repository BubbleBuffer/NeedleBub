package de.x0bubbuff.needlebub.otp

import org.json.JSONArray
import org.json.JSONException

data class OtpResult(val code: String, val source: String?)

enum class SourceDisposition { GROUNDED, ABSENT, DROPPED_UNGROUNDED }

enum class OtpReason {
    OTP_ACCEPTED,
    MODEL_NO_MATCH,
    MODEL_WRONG_TOOL,
    MODEL_MULTIPLE_CALLS,
    INVALID_ARGUMENTS,
    INVALID_CODE_FORMAT,
    CODE_NOT_GROUNDED,
    PROMOTIONAL_CONTEXT,
    TRACKING_CONTEXT,
}

sealed interface OtpOutcome {
    data class Accepted(val result: OtpResult, val sourceDisposition: SourceDisposition) : OtpOutcome
    data class Rejected(val reason: OtpReason) : OtpOutcome
}

object OtpPostprocessor {
    private val codePattern = Regex("^[A-Za-z0-9]{4,8}$")
    private val rejectedContexts = listOf(
        Regex("\\bpromo(?:tional)?\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\btracking\\s+(?:reference|number|code)\\b", RegexOption.IGNORE_CASE),
    )

    fun formatQuery(sender: String, message: String): String =
        if (sender.isNotEmpty()) "Sender: $sender\nMessage: $message" else "Message: $message"

    private fun messageBody(query: String): String {
        val directPrefix = "Message: "
        if (query.startsWith(directPrefix)) return query.removePrefix(directPrefix)
        val marker = "\nMessage: "
        val markerIndex = query.indexOf(marker)
        return if (markerIndex >= 0) query.substring(markerIndex + marker.length) else query
    }

    fun process(query: String, rawCalls: String): OtpOutcome {
        if (rejectedContexts[0].containsMatchIn(query)) return OtpOutcome.Rejected(OtpReason.PROMOTIONAL_CONTEXT)
        if (rejectedContexts[1].containsMatchIn(query)) return OtpOutcome.Rejected(OtpReason.TRACKING_CONTEXT)
        return try {
            val calls = JSONArray(rawCalls)
            if (calls.length() != 1) return OtpOutcome.Rejected(OtpReason.MODEL_MULTIPLE_CALLS)
            val call = calls.optJSONObject(0) ?: return OtpOutcome.Rejected(OtpReason.INVALID_ARGUMENTS)
            if (call.optString("name") != "extract_otp") return OtpOutcome.Rejected(OtpReason.MODEL_WRONG_TOOL)
            val arguments = call.optJSONObject("arguments") ?: return OtpOutcome.Rejected(OtpReason.INVALID_ARGUMENTS)
            val code = arguments.opt("code") as? String ?: return OtpOutcome.Rejected(OtpReason.INVALID_ARGUMENTS)
            if (!codePattern.matches(code)) return OtpOutcome.Rejected(OtpReason.INVALID_CODE_FORMAT)
            if (!messageBody(query).contains(code)) return OtpOutcome.Rejected(OtpReason.CODE_NOT_GROUNDED)
            val rawSource = arguments.opt("source") as? String
            val source = rawSource?.takeIf { it.isNotEmpty() && query.contains(it) }
            val disposition = when {
                rawSource.isNullOrEmpty() -> SourceDisposition.ABSENT
                source != null -> SourceDisposition.GROUNDED
                else -> SourceDisposition.DROPPED_UNGROUNDED
            }
            OtpOutcome.Accepted(OtpResult(code, source), disposition)
        } catch (_: JSONException) {
            OtpOutcome.Rejected(OtpReason.INVALID_ARGUMENTS)
        }
    }
}
