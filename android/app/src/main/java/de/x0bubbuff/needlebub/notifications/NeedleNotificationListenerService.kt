package de.x0bubbuff.needlebub.notifications

import android.Manifest
import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import de.x0bubbuff.needlebub.NeedleBubApplication
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.developer.DiagnosticEntry
import de.x0bubbuff.needlebub.otp.OtpPostprocessor
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

class NeedleNotificationListenerService : NotificationListenerService() {
    private val deduplicator = NotificationDeduplicator()
    private val settings by lazy { AutomationSettings(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as NeedleBubApplication
        if (sbn.packageName == packageName) return
        val captureEnabled = app.developerDataSettings.captureEnabled
        val mayInspect = AutomaticOtpState.mayInspectNotification(
            settings.enabled,
            false,
            settings.accepts(sbn.packageName),
        )
        if (!captureEnabled && !mayInspect) return

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty().take(MAX_TITLE_CHARS)
        val body = extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getCharSequenceArray("android.textLines")?.joinToString("\n")
            ?: extras.getCharSequence("android.text")?.toString().orEmpty()
        val boundedBody = body.take(MAX_BODY_CHARS)
        val policyDecision = NotificationInferencePolicy.decision(
                body = boundedBody,
                category = notification.category,
                hasMediaSession = extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
                template = extras.getString(Notification.EXTRA_TEMPLATE),
            )
        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { sbn.packageName }
        val captureId = if (captureEnabled) app.developerDataStore.insertCapture(JSONObject()
            .put("schema", "needlebub.capture.record.v1")
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", sbn.packageName)
            .put("appLabel", appLabel.take(MAX_TITLE_CHARS))
            .put("notificationKeyHash", sha256(sbn.key))
            .put("category", notification.category)
            .put("templateClass", extras.getString(Notification.EXTRA_TEMPLATE))
            .put("title", title)
            .put("body", boundedBody)
            .put("policyDecision", policyDecision)
            .put("automaticOtpEnabled", settings.enabled)) else null
        if (captureEnabled) app.developerDataStore.addDiagnostic(DiagnosticEntry(
            id = 0, createdAt = System.currentTimeMillis(), packageName = sbn.packageName,
            category = notification.category, stage = "capture", pack = null,
            status = policyDecision, errorCode = null, durationMs = null, pssKb = null, coldLoad = null,
        ))
        if (!mayInspect || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pack = app.packStore.officialOtp() ?: return
        if (policyDecision != NotificationInferencePolicy.INFER) return
        val sender = title.ifBlank { appLabel }
        val query = OtpPostprocessor.formatQuery(sender, boundedBody)
        if (!deduplicator.shouldProcess(sbn.packageName, sbn.key, query)) return

        val requestId = "notification-${UUID.randomUUID()}"
        app.runtime.infer(requestId, pack, query, NOTIFICATION_TIMEOUT_MS, surface = "notification") { response ->
            val matched = response.status == "OK" && response.toolName == "extract_otp" && response.resultJson != null && response.errorCode == null
            captureId?.let { id ->
                app.developerDataStore.attachRuntime(id, JSONObject()
                    .put("packId", pack.manifest.id)
                    .put("packVersion", pack.manifest.version)
                    .put("status", response.status)
                    .put("matched", matched)
                    .put("toolName", response.toolName)
                    .put("resultJson", response.resultJson)
                    .put("errorCode", response.errorCode)
                    .put("durationMs", response.durationMs)
                    .put("coldLoad", response.coldLoad)
                    .put("pssKb", response.pssKb))
            }
            app.developerDataStore.addDiagnostic(DiagnosticEntry(
                id = 0, createdAt = System.currentTimeMillis(), packageName = sbn.packageName,
                category = notification.category, stage = "inference",
                pack = "${pack.manifest.id}@${pack.manifest.version}", status = response.status,
                errorCode = response.errorCode, durationMs = response.durationMs,
                pssKb = response.pssKb, coldLoad = response.coldLoad,
            ))
            if (!AutomaticOtpState.mayPublishResult(settings.enabled)) return@infer
            if (response.status != "OK" || response.toolName != "extract_otp" || response.resultJson == null || response.errorCode != null) return@infer
            val calls = JSONArray().put(JSONObject().put("name", response.toolName).put("arguments", JSONObject(response.resultJson)))
            val accepted = OtpPostprocessor.process(query, calls.toString()) ?: return@infer
            OtpResultNotification.show(this, accepted.code, accepted.source ?: sender)
        }
    }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MS = 5_000L
        const val MAX_TITLE_CHARS = 512
        const val MAX_BODY_CHARS = 8_192

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
