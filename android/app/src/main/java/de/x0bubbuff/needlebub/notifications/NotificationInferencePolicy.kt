package de.x0bubbuff.needlebub.notifications

object NotificationInferencePolicy {
    private val excludedCategories = setOf("transport", "sys", "progress")

    fun shouldInfer(
        body: String,
        category: String?,
        hasMediaSession: Boolean,
        template: String?,
    ): Boolean {
        if (body.isBlank() || category in excludedCategories || hasMediaSession) return false
        return template?.contains("MediaStyle", ignoreCase = true) != true &&
            template?.contains("MediaCustomViewStyle", ignoreCase = true) != true
    }
}
