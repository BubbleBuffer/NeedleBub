package de.x0bubbuff.needlebub.notifications

import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.runtime.RuntimeResponse

object NotificationRuntimeOutcome {
    fun failureReason(response: RuntimeResponse): String? {
        if (response.engineSuccess == false) {
            val code = response.engineErrorCode
                ?.uppercase()
                ?.replace(Regex("[^A-Z0-9_]+"), "_")
                ?.trim('_')
                ?.takeIf { it.isNotBlank() }
                ?: "FAILURE"
            return "ENGINE_$code"
        }
        if (
            response.status == "NO_MATCH" &&
            (response.errorCode == null || response.errorCode == ErrorCodes.NO_MATCH)
        ) return null
        if (response.errorCode != null) return response.errorCode
        if (response.status != "OK" && response.status != "NO_MATCH") return "MODEL_RUNTIME_ERROR"
        return null
    }
}
