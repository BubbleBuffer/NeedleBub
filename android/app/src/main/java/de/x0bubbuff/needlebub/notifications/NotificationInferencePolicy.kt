package de.x0bubbuff.needlebub.notifications

object NotificationInferencePolicy {
    const val INFER = "infer"

    @Suppress("UNUSED_PARAMETER")
    fun shouldInfer(
        body: String,
        category: String?,
        hasMediaSession: Boolean,
        template: String?,
    ): Boolean = decision(body, category, hasMediaSession, template) == INFER

    @Suppress("UNUSED_PARAMETER")
    fun decision(body: String, category: String?, hasMediaSession: Boolean, template: String?): String =
        if (body.isBlank()) "excluded_blank" else INFER
}
