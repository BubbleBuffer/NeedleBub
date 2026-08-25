package de.x0bubbuff.needlebub.notifications

object NotificationInferencePolicy {
    const val INFER = "infer"
    private val excludedCategories = setOf("transport", "sys", "progress")

    fun shouldInfer(
        body: String,
        category: String?,
        hasMediaSession: Boolean,
        template: String?,
    ): Boolean = decision(body, category, hasMediaSession, template) == INFER

    fun decision(body: String, category: String?, hasMediaSession: Boolean, template: String?): String = when {
        body.isBlank() -> "excluded_blank"
        category in excludedCategories -> "excluded_${category}"
        hasMediaSession -> "excluded_media_session"
        template?.contains("MediaStyle", ignoreCase = true) == true -> "excluded_media_style"
        template?.contains("MediaCustomViewStyle", ignoreCase = true) == true -> "excluded_media_style"
        else -> INFER
    }
}
