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
) : Parcelable

@Parcelize
data class RuntimeResponse(
    val requestId: String,
    val status: String,
    val toolName: String?,
    val resultJson: String?,
    val errorCode: String?,
    val durationMs: Long,
) : Parcelable
