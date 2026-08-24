package de.x0bubbuff.needlebub.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import de.x0bubbuff.needlebub.NeedleBubApplication
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.otp.OtpPostprocessor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NeedleNotificationListenerService : NotificationListenerService() {
    private val deduplicator = NotificationDeduplicator()
    private val settings by lazy { AutomationSettings(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !settings.accepts(sbn.packageName)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val app = application as NeedleBubApplication
        val pack = app.packStore.officialOtp() ?: return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val body = extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getCharSequenceArray("android.textLines")?.joinToString("\n")
            ?: extras.getCharSequence("android.text")?.toString().orEmpty()
        if (!OtpPostprocessor.hasPlausibleCandidate(body)) return
        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { sbn.packageName }
        val sender = title.ifBlank { appLabel }
        val query = OtpPostprocessor.formatQuery(sender, body)
        if (!deduplicator.shouldProcess(sbn.packageName, sbn.key, query)) return

        val requestId = "notification-${UUID.randomUUID()}"
        app.runtime.infer(requestId, pack, query, NOTIFICATION_TIMEOUT_MS) { response ->
            if (response.status != "OK" || response.toolName != "extract_otp" || response.resultJson == null || response.errorCode != null) return@infer
            val calls = JSONArray().put(JSONObject().put("name", response.toolName).put("arguments", JSONObject(response.resultJson)))
            val accepted = OtpPostprocessor.process(query, calls.toString()) ?: return@infer
            OtpResultNotification.show(this, accepted.code, accepted.source ?: sender)
        }
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 5_000L
    }
}
