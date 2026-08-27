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
) : Parcelable

@Parcelize
data class RuntimeResponse(
    val requestId: String,
    val status: String,
    val toolName: String?,
    val resultJson: String?,
    val errorCode: String?,
    val durationMs: Long,
    val coldLoad: Boolean,
    val pssKb: Long,
    val responseType: String? = null,
    val engineSuccess: Boolean? = null,
    val engineErrorCode: String? = null,
    val reasoning: String? = null,
    val callCount: Int = 0,
) : Parcelable
