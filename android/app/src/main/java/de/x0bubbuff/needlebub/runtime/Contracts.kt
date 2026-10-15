package de.x0bubbuff.needlebub.runtime

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RuntimeRequest(
    val requestId: String,
    val packId: String,
    val packVersion: String,
    val input: String,
    val queryTemplate: String,
    val modelSize: Long,
    val timeoutMs: Long,
    val surface: String,
    val forceReload: Boolean,
    /** Wall clock at admission, for trace correlation with the caller's own log line. */
    val requestedAtEpochMs: Long = 0L,
    /** App build identity of the caller, so traces survive version skew. */
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    /** Model identity: pack, engine ABI and model size, never model content. */
    val modelIdentity: String = "",
    /** Engine ABI this request targets, so a trace identifies the runtime it ran on. */
    val engineAbi: String = "",
) : Parcelable

/**
 * Runtime outcome plus the trace metadata that explains how long each phase took.
 *
 * [coldLoad] is nullable on purpose: `null` means the load state is unknown, which is the
 * honest answer for a request that never reached the runtime. Never report `false`
 * (warm) for a request that was not executed.
 */
@Parcelize
data class RuntimeResponse(
    val requestId: String,
    val status: String,
    val toolName: String?,
    val resultJson: String?,
    val errorCode: String?,
    val durationMs: Long,
    val coldLoad: Boolean? = null,
    val pssKb: Long,
    val responseType: String? = null,
    val engineSuccess: Boolean? = null,
    val engineErrorCode: String? = null,
    val reasoning: String? = null,
    val callCount: Int = 0,
    /** `cold`, `warm` or `unknown`; always present for broker generated responses. */
    val loadState: String? = null,
    /** [RuntimeTermination] name, so callers can distinguish failure modes. */
    val termination: String? = null,
    val queueWaitMs: Long? = null,
    val bindWaitMs: Long? = null,
    val modelLoadMs: Long? = null,
    val inferenceMs: Long? = null,
    val requestedAtEpochMs: Long? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val modelIdentity: String? = null,
    /** Engine ABI and revision the runtime reported, so a trace survives runtime skew. */
    val engineAbi: String? = null,
    /** Engine hit a generation limit or returned an incomplete call envelope. */
    val outputTruncated: Boolean? = null,
) : Parcelable

/** Explicit load states; `UNKNOWN` must never be rendered as warm. */
object RuntimeLoadState {
    const val COLD = "cold"
    const val WARM = "warm"
    const val UNKNOWN = "unknown"
}
