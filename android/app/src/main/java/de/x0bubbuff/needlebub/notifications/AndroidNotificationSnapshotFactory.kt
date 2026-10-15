package de.x0bubbuff.needlebub.notifications

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

object AndroidNotificationSnapshotFactory {
    fun create(
        sbn: StatusBarNotification,
        lifecycleKey: String,
        appLabel: String,
        maxTitleChars: Int,
        maxBodyChars: Int,
    ): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
            .take(maxTitleChars)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().take(maxBodyChars) }
            .orEmpty()
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: textLines.joinToString("\n")
            ).orEmpty().take(maxBodyChars)
        val messageBundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messageBundles)
            .orEmpty().mapNotNull { message ->
            val text = message.text?.toString()?.take(maxBodyChars).orEmpty()
            if (text.isBlank()) null else NotificationMessageSnapshot(
                text = text,
                sender = message.senderPerson?.name?.toString()?.take(maxTitleChars),
                senderKey = message.senderPerson?.key?.take(maxTitleChars),
                timestamp = message.timestamp,
            )
        }
        return NotificationSnapshot(
            lifecycleKey = lifecycleKey,
            title = title,
            appLabel = appLabel.take(maxTitleChars),
            body = body,
            textLines = textLines,
            messages = messages,
            category = notification.category,
            hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
            template = extras.getString(Notification.EXTRA_TEMPLATE),
            flags = notification.flags,
            progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            progressIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
            postedAt = sbn.postTime,
        )
    }
}
