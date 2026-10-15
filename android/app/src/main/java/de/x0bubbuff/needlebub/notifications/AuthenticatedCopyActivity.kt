package de.x0bubbuff.needlebub.notifications

import android.app.NotificationManager
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * Manual "Copy code" action of the result notification.
 *
 * Ordinary Android keyguard only: the activity is never shown over the lockscreen (the
 * manifest does not set `showWhenLocked`, so the platform requires an unlock first) and it
 * refuses to copy while the device is still locked. No biometric prompt is raised for a
 * manual copy; developer capture keeps its own authentication.
 *
 * The action is retryable: a locked device or a failing clipboard leaves the notification
 * in place, and the delivery token is validated against persisted state so an action left
 * over from an older result cannot copy after a newer one arrived.
 */
class AuthenticatedCopyActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val value = intent.getStringExtra(OtpResultNotification.EXTRA_CODE)
            ?.takeIf { CODE_PATTERN.matches(it) }
        val token = intent.getStringExtra(OtpResultNotification.EXTRA_DELIVERY_TOKEN)
        if (value == null || !OtpResultNotification.isCurrentDelivery(this, token)) return finish()
        // Locked: leave the notification in place instead of copying anything.
        if (!ClipboardHelper.isUnlocked(this)) return finish()
        // A failing clipboard leaves the notification so the user can try again.
        if (!ClipboardHelper.copySensitive(this, value)) return finish()
        getSystemService(NotificationManager::class.java).cancel(OtpResultNotification.NOTIFICATION_ID)
        finish()
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Za-z0-9]{4,8}$")
    }
}
