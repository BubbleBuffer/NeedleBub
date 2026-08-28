package de.x0bubbuff.needlebub.updates

enum class UpdateAction { UP_TO_DATE, WAIT_FOR_WIFI, DOWNLOAD }

object PackUpdatePolicy {
    fun decide(
        currentVersion: String?,
        availableVersion: String,
        metered: Boolean,
        allowMetered: Boolean,
    ): UpdateAction {
        if (currentVersion != null &&
            SemanticVersion.parse(availableVersion) <= SemanticVersion.parse(currentVersion)
        ) {
            return UpdateAction.UP_TO_DATE
        }
        return if (metered && !allowMetered) UpdateAction.WAIT_FOR_WIFI else UpdateAction.DOWNLOAD
    }
}
