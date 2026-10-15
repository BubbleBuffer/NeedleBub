package de.x0bubbuff.needlebub.notifications

import android.app.Notification

object NotificationInferencePolicy {
    const val INFER = "infer"
    const val EXCLUDED_BLANK = "excluded_blank"
    const val EXCLUDED_NAVIGATION = "excluded_navigation"
    const val EXCLUDED_MEDIA = "excluded_media"
    const val EXCLUDED_PROGRESS = "excluded_progress"
    const val EXCLUDED_ONGOING_SERVICE = "excluded_ongoing_service"

    fun shouldInfer(
        body: String,
        category: String?,
        hasMediaSession: Boolean,
        template: String?,
        flags: Int = 0,
        progressMax: Int = 0,
        progressIndeterminate: Boolean = false,
    ): Boolean = decision(
        body,
        category,
        hasMediaSession,
        template,
        flags,
        progressMax,
        progressIndeterminate,
    ) == INFER

    fun decision(
        body: String,
        category: String?,
        hasMediaSession: Boolean,
        template: String?,
        flags: Int = 0,
        progressMax: Int = 0,
        progressIndeterminate: Boolean = false,
    ): String = when {
        body.isBlank() -> EXCLUDED_BLANK
        category == Notification.CATEGORY_NAVIGATION -> EXCLUDED_NAVIGATION
        hasMediaSession ||
            category == Notification.CATEGORY_TRANSPORT ||
            template?.endsWith("\$MediaStyle") == true -> EXCLUDED_MEDIA
        category == Notification.CATEGORY_PROGRESS ||
            template?.endsWith("\$ProgressStyle") == true ||
            progressMax > 0 ||
            progressIndeterminate -> EXCLUDED_PROGRESS
        flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
            category == Notification.CATEGORY_SERVICE &&
            flags and Notification.FLAG_ONGOING_EVENT != 0 -> EXCLUDED_ONGOING_SERVICE
        else -> INFER
    }
}
