package de.x0bubbuff.needlebub.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import de.x0bubbuff.needlebub.R
import java.security.SecureRandom

object OtpResultNotification {
    const val CHANNEL_ID = "otp_results"
    const val NOTIFICATION_ID = 0x4e42
    const val EXTRA_CODE = "de.bubblebuffer.needlebub.extra.OTP_CODE"
    const val EXTRA_DELIVERY_TOKEN = "de.bubblebuffer.needlebub.extra.OTP_DELIVERY_TOKEN"

    /** How long the manual copy action stays valid, matching the notification lifetime. */
    const val DELIVERY_TTL_MS = 120_000L

    private const val PREFERENCES = "automation"
    private const val KEY_DELIVERY = "current_delivery"

    /**
     * Posts the private result notification and returns the delivery token it carries.
     *
     * The token is random and persisted with its expiry, so it survives process recreation
     * and a surviving notification stays copyable. Each delivery also gets an immutable
     * PendingIntent identity derived from its own token: a stale action can only ever carry
     * its own code, never the code of a newer delivery.
     */
    fun show(context: Context, code: String, source: String, now: Long = System.currentTimeMillis()): String {
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
        val token = newToken()
        store(context, token, now + DELIVERY_TTL_MS)
        val copyIntent = Intent(context, AuthenticatedCopyActivity::class.java)
            .setData(Uri.parse("needlebub://otp-copy/$token"))
            .putExtra(EXTRA_CODE, code)
            .putExtra(EXTRA_DELIVERY_TOKEN, token)
        val copy = PendingIntent.getActivity(
            context,
            requestCode(token),
            copyIntent,
            // No FLAG_ONE_SHOT: a clipboard failure leaves the action tappable again.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
            .setTimeoutAfter(DELIVERY_TTL_MS)
            .addAction(0, context.getString(R.string.copy_code), copy)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return token
    }

    /**
     * True while [token] is still the newest, unexpired delivery.
     *
     * Read from persisted state, so it keeps working after the process was recreated.
     */
    fun isCurrentDelivery(context: Context, token: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (token.isNullOrEmpty()) return false
        val stored = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_DELIVERY, null) ?: return false
        val separator = stored.lastIndexOf(':')
        if (separator <= 0) return false
        val current = stored.substring(0, separator)
        val expiresAt = stored.substring(separator + 1).toLongOrNull() ?: return false
        return token == current && now <= expiresAt
    }

    private fun store(context: Context, token: String, expiresAt: Long) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DELIVERY, "$token:$expiresAt")
            .apply()
    }

    /**
     * Unique identity per delivery.
     *
     * The token also travels in the intent data, so identity does not rest on a 32-bit
     * request code that could collide.
     */
    private fun requestCode(token: String): Int =
        NOTIFICATION_ID * 31 + token.hashCode().let { if (it == Int.MIN_VALUE) 0 else it }

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
