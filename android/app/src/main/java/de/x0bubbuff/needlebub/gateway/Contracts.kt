package de.x0bubbuff.needlebub.gateway

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object ErrorCodes {
    const val NO_MATCH = "NO_MATCH"
    const val PACK_NOT_FOUND = "PACK_NOT_FOUND"
    const val PACK_INVALID = "PACK_INVALID"
    const val ENGINE_INCOMPATIBLE = "ENGINE_INCOMPATIBLE"
    const val INPUT_TOO_LARGE = "INPUT_TOO_LARGE"
    const val BUSY = "BUSY"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val TIMEOUT = "TIMEOUT"
    const val RUNTIME_CRASH = "RUNTIME_CRASH"

    /** The caller cancelled the request before a result was delivered. */
    const val CANCELLED = "CANCELLED"

    /** The request waited longer than its own budget for a runtime slot. */
    const val QUEUE_EXPIRED = "QUEUE_EXPIRED"

    /** The isolated service could not be bound within the bind deadline. */
    const val BIND_FAILED = "BIND_FAILED"

    /** The engine returned a limit capped or structurally incomplete call envelope. */
    const val OUTPUT_TRUNCATED = "OUTPUT_TRUNCATED"
}

@Parcelize
data class CapabilityInfo(
    val capabilityId: String,
    val name: String,
    val description: String,
    val packVersion: String,
    val verified: Boolean,
    val outputNames: List<String>,
) : Parcelable

@Parcelize
data class InferenceRequest(
    val requestId: String,
    val capabilityId: String,
    val input: String,
    val timeoutMs: Long,
) : Parcelable

@Parcelize
data class InferenceResponse(
    val requestId: String,
    val status: String,
    val matched: Boolean,
    val toolName: String?,
    val resultJson: String?,
    val outputs: Map<String, String>,
    val errorCode: String?,
    val packId: String?,
    val packVersion: String?,
    val durationMs: Long,
) : Parcelable
