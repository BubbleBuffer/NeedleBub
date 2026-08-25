package de.x0bubbuff.needlebub.otp

import org.json.JSONArray
import org.json.JSONException

data class OtpResult(val code: String, val source: String?)

object OtpPostprocessor {
    private val codePattern = Regex("^[A-Za-z0-9]{4,8}$")
    private val rejectedContexts = listOf(
        Regex("\\bpromo(?:tional)?\\s+code\\b", RegexOption.IGNORE_CASE),
        Regex("\\btracking\\s+(?:reference|number|code)\\b", RegexOption.IGNORE_CASE),
    )

    fun formatQuery(sender: String, message: String): String =
        if (sender.isNotEmpty()) "Sender: $sender\nMessage: $message" else "Message: $message"

    fun process(query: String, rawCalls: String): OtpResult? {
        if (rejectedContexts.any { it.containsMatchIn(query) }) return null
        return try {
            val calls = JSONArray(rawCalls)
            if (calls.length() != 1) return null
            val call = calls.optJSONObject(0) ?: return null
            if (call.optString("name") != "extract_otp") return null
            val arguments = call.optJSONObject("arguments") ?: return null
            val code = arguments.opt("code") as? String ?: return null
            if (!codePattern.matches(code) || !query.contains(code)) return null
            val rawSource = arguments.opt("source") as? String
            OtpResult(code, rawSource?.takeIf { it.isNotEmpty() && query.contains(it) })
        } catch (_: JSONException) {
            null
        }
    }
}
