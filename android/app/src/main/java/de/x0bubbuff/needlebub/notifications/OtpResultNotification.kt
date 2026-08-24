package de.x0bubbuff.needlebub.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.x0bubbuff.needlebub.R

object OtpResultNotification {
    const val CHANNEL_ID = "otp_results"
    const val NOTIFICATION_ID = 0x4e42
    const val EXTRA_CODE = "de.x0bubbuff.needlebub.extra.OTP_CODE"

    fun show(context: Context, code: String, source: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.otp_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.otp_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            setShowBadge(false)
        })
        val copyIntent = Intent(context, AuthenticatedCopyActivity::class.java).putExtra(EXTRA_CODE, code)
        val copy = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_needle_notification)
            .setContentTitle(context.getString(R.string.otp_ready_title))
            .setContentText(context.getString(R.string.unlock_to_copy))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_needle_notification)
            .setContentTitle(context.getString(R.string.otp_from_source, source))
            .setContentText(code)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000L)
            .addAction(0, context.getString(R.string.copy_code), copy)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
