package de.x0bubbuff.needlebub.notifications

object AutomaticOtpState {
    fun configured(
        packInstalled: Boolean,
        notificationAccess: Boolean,
        notificationPermission: Boolean,
        hasSources: Boolean,
    ): Boolean = packInstalled && notificationAccess && notificationPermission && hasSources

    fun mayInspectNotification(enabled: Boolean, ownPackage: Boolean, acceptedSource: Boolean): Boolean =
        enabled && !ownPackage && acceptedSource

    fun mayPublishResult(enabled: Boolean): Boolean = enabled
}
