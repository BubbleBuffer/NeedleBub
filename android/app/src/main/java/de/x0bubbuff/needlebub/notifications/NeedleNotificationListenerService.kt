package de.x0bubbuff.needlebub.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.x0bubbuff.needlebub.NeedleBubApplication
import de.x0bubbuff.needlebub.developer.DiagnosticEntry
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.otp.OtpOutcome
import de.x0bubbuff.needlebub.otp.OtpPostprocessor
import de.x0bubbuff.needlebub.runtime.RuntimeResponse
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class NeedleNotificationListenerService : NotificationListenerService() {
    private val analyzer = NotificationLifecycleAnalyzer()
    private val coordinator = NotificationInferenceCoordinator<RuntimeResponse>()
    private val settings by lazy { AutomationSettings(this) }
    private val deliveryMemory by lazy { OtpDeliveryMemory(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            processNotification(sbn)
        } catch (error: Exception) {
            Log.e(TAG, "surface=notification status=failed error=LISTENER_FAILURE type=${error.javaClass.simpleName}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        analyzer.remove(sha256(sbn.key))
    }

    override fun onDestroy() {
        coordinator.shutdown()
        super.onDestroy()
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val app = application as NeedleBubApplication
        if (sbn.packageName == packageName) return
        // An explicit exclusion is a privacy boundary, including Developer capture.
        // In allowlist mode exclusions are dormant and selected sources still work.
        if (settings.allApps && sbn.packageName in settings.excludedPackages) return
        val captureEnabled = app.developerDataSettings.captureEnabled
        val sourceAccepted = settings.accepts(sbn.packageName)
        val mayInspect = AutomaticOtpState.mayInspectNotification(
            settings.enabled,
            false,
            sourceAccepted,
        )
        if (!captureEnabled && !mayInspect) return

        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val lifecycleKey = sha256(sbn.key)
        val snapshot = AndroidNotificationSnapshotFactory.create(
            sbn,
            lifecycleKey,
            appLabel,
            MAX_TITLE_CHARS,
            MAX_BODY_CHARS,
        )
        val analysis = analyzer.analyze(snapshot)
        val candidate = analysis.candidate
        val capture = if (captureEnabled) {
            app.developerDataStore.upsertCapture(JSONObject()
                .put("schema", "needlebub.capture.record.v3")
                .put("capturedAtEpochMs", candidate.observedAt)
                .put("firstSeenAtEpochMs", candidate.observedAt)
                .put("lastSeenAtEpochMs", candidate.observedAt)
                .put("packageName", sbn.packageName)
                .put("appLabel", appLabel.take(MAX_TITLE_CHARS))
                .put("notificationKeyHash", lifecycleKey)
                .put("lifecycleKey", lifecycleKey)
                .put("itemKey", candidate.itemKey)
                .put("category", snapshot.category)
                .put("templateClass", snapshot.template)
                .put("title", candidate.title)
                .put("body", candidate.body)
                .put("extractionKind", candidate.extractionKind.wireName)
                .put("policyDecision", candidate.policyDecision)
                .put("automaticOtpEnabled", settings.enabled)
                .put("outcome", outcome("PENDING", "INTERRUPTED")))
        } else {
            null
        }
        val captureId = capture?.id
        if (analysis.action == NotificationObservationAction.TOUCH) {
            if (analysis.policyTransition) {
                addDiagnostic(
                    app,
                    captureEnabled,
                    sbn,
                    "policy",
                    "skipped",
                    analysis.reasonCode,
                )
            }
            if (capture?.isNew == true || analysis.policyTransition) {
                finish(
                    app,
                    captureId,
                    outcome("SKIPPED", analysis.reasonCode ?: "DUPLICATE_REVISION"),
                    recordActivity = false,
                )
            }
            return
        }

        if (!settings.enabled) {
            finish(app, captureId, outcome("NOT_RUN", "AUTOMATION_PAUSED"))
            return
        }
        if (!sourceAccepted) {
            finish(app, captureId, outcome("NOT_RUN", "SOURCE_NOT_SELECTED"))
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            finish(app, captureId, outcome("NOT_RUN", "NOTIFICATION_PERMISSION_MISSING"))
            return
        }
        if (!analysis.shouldInfer) {
            val reason = analysis.reasonCode ?: "POLICY_EXCLUDED"
            addDiagnostic(app, captureEnabled, sbn, "policy", "skipped", reason)
            finish(app, captureId, outcome("SKIPPED", reason))
            return
        }
        val pack = app.packStore.officialOtp()
        if (pack == null) {
            finish(app, captureId, outcome("NOT_RUN", "PACK_NOT_INSTALLED"))
            return
        }

        val query = OtpPostprocessor.formatQuery(candidate.sender, candidate.body)
        val requestId = "notification-${UUID.randomUUID()}"
        coordinator.submit(NotificationInferenceWork(
            notificationKey = lifecycleKey,
            dispatch = { callback ->
                app.runtime.infer(
                    requestId,
                    pack,
                    query,
                    NOTIFICATION_TIMEOUT_MS,
                    surface = "notification",
                    callback = callback,
                )
            },
            onResult = { response ->
                handleRuntimeResult(
                    app,
                    sbn,
                    // Copy dedupe identity: stable for ordinary notifications, per message for
                    // messaging and inbox. Capture provenance keeps using candidate.itemKey.
                    OtpCopyPolicy.copyIdentity(
                        lifecycleKey,
                        candidate.itemKey,
                        candidate.extractionKind,
                    ),
                    captureEnabled,
                    captureId,
                    pack.manifest.id,
                    pack.manifest.version,
                    candidate.sender,
                    query,
                    response,
                )
            },
            onSuperseded = {
                captureId?.let(app.developerDataStore::markSuperseded)
                finish(
                    app,
                    captureId,
                    outcome("SKIPPED", "SUPERSEDED_REVISION"),
                    recordActivity = false,
                )
            },
            onBackpressure = {
                addDiagnostic(app, captureEnabled, sbn, "admission", "rejected", ErrorCodes.BUSY)
                finish(app, captureId, outcome("BACKPRESSURE", "QUEUE_FULL"))
            },
            onExpired = {
                addDiagnostic(app, captureEnabled, sbn, "admission", "rejected", ErrorCodes.QUEUE_EXPIRED)
                finish(app, captureId, outcome("BACKPRESSURE", "QUEUE_EXPIRED"))
            },
            // A notification removed or edited while its revision waited must not be
            // inferred later; an unreadable state fails closed the same way.
            isFresh = { settings.accepts(sbn.packageName) && isCurrentRevision(sbn) },
        ))
    }

    private fun handleRuntimeResult(
        app: NeedleBubApplication,
        sbn: StatusBarNotification,
        /** Stable identity used to deduplicate the copy, not the content-derived item key. */
        copyIdentity: String,
        captureEnabled: Boolean,
        captureId: String?,
        packId: String,
        packVersion: String,
        sender: String,
        query: String,
        response: RuntimeResponse,
    ) {
        val failureReason = NotificationRuntimeOutcome.failureReason(response)
        val matched = failureReason == null &&
            response.status == "OK" &&
            response.toolName == "extract_otp" &&
            response.resultJson != null
        val persistenceStartedAt = SystemClock.elapsedRealtime()
        captureId?.let { id ->
            app.developerDataStore.attachRuntime(id, JSONObject()
                .put("packId", packId)
                .put("packVersion", packVersion)
                .put("status", response.status)
                .put("matched", matched)
                .put("toolName", response.toolName)
                .put("resultJson", response.resultJson)
                .put("errorCode", response.errorCode)
                .put("responseType", response.responseType)
                .put("engineSuccess", response.engineSuccess)
                .put("engineErrorCode", response.engineErrorCode)
                .put("reasoning", response.reasoning)
                .put("callCount", response.callCount)
                .put("durationMs", response.durationMs)
                // Unknown values are written as JSON null: put(key, null) would delete the
                // key and hide that the value was never observable.
                .putNullable("coldLoad", response.coldLoad)
                .put("loadState", response.loadState)
                .put("termination", response.termination)
                .putNullable("queueWaitMs", response.queueWaitMs)
                .putNullable("bindWaitMs", response.bindWaitMs)
                .putNullable("modelLoadMs", response.modelLoadMs)
                .putNullable("inferenceMs", response.inferenceMs)
                .putNullable("requestedAtEpochMs", response.requestedAtEpochMs)
                .put("appVersionName", response.appVersionName)
                .put("appVersionCode", response.appVersionCode)
                .put("modelIdentity", response.modelIdentity)
                .put("engineAbi", response.engineAbi)
                .putNullable("outputTruncated", response.outputTruncated)
                .put("pssKb", response.pssKb))
        }
        val persistenceMs = SystemClock.elapsedRealtime() - persistenceStartedAt
        // Result persistence is measured separately from inference. The number travels with
        // the outcome, because attachRuntime replaces the runtime object wholesale.
        fun finishResult(finalOutcome: JSONObject, durationMs: Long? = response.durationMs) {
            finish(
                app,
                captureId,
                finalOutcome,
                durationMs,
                extra = JSONObject().put("persistenceMs", persistenceMs),
            )
        }
        if (captureEnabled) {
            app.developerDataStore.addDiagnostic(DiagnosticEntry(
                id = 0,
                createdAt = System.currentTimeMillis(),
                packageName = sbn.packageName,
                category = sbn.notification.category,
                stage = "inference",
                pack = "$packId@$packVersion",
                status = response.status,
                errorCode = failureReason,
                durationMs = response.durationMs,
                pssKb = response.pssKb,
                coldLoad = response.coldLoad,
            ))
        }

        if (failureReason != null) {
            finishResult(outcome("ERROR", failureReason))
            if (response.errorCode == ErrorCodes.PACK_INVALID) app.packStore.rollbackOfficial()
            return
        }
        if (response.status == "NO_MATCH") {
            finishResult(outcome("REJECTED", "MODEL_NO_MATCH"))
            return
        }
        if (response.callCount != 1) {
            finishResult(outcome("REJECTED", "MODEL_MULTIPLE_CALLS"))
            return
        }
        if (response.toolName != "extract_otp") {
            finishResult(outcome("REJECTED", "MODEL_WRONG_TOOL"))
            return
        }
        val arguments = try {
            response.resultJson?.let(::JSONObject)
        } catch (_: Exception) {
            null
        }
        if (arguments == null) {
            finishResult(outcome("REJECTED", "INVALID_ARGUMENTS"))
            return
        }
        val calls = JSONArray().put(JSONObject()
            .put("name", response.toolName)
            .put("arguments", arguments))
        when (val processed = OtpPostprocessor.process(query, calls.toString())) {
            is OtpOutcome.Accepted -> {
                if (!AutomaticOtpState.mayPublishResult(settings.enabled)) {
                    finishResult(outcome("SUPPRESSED", "RESULT_SUPPRESSED_PAUSED"))
                    return
                }
                if (!settings.accepts(sbn.packageName)) {
                    finishResult(outcome("SUPPRESSED", "RESULT_SUPPRESSED_SOURCE_CHANGED"))
                    return
                }
                // Dedupe on the copy identity and code: stable across text revisions of an
                // ordinary notification, per message for messaging and inbox.
                val pair = deliveryMemory.record(copyIdentity, processed.result.code)
                // Delivery-time gating: pause, the auto-copy setting, freshness and the
                // lock state are all rechecked now. Nothing is queued for a later unlock.
                val decision = OtpCopyPolicy.decide(
                    accepted = true,
                    fresh = isCurrentRevision(sbn) &&
                        NotificationFreshness.isRecent(sbn.postTime, System.currentTimeMillis()),
                    automationEnabled = settings.enabled,
                    autoCopyEnabled = settings.autoCopyEnabled,
                    unlocked = ClipboardHelper.isUnlocked(this),
                    candidate = pair,
                    // A pair that was already delivered counts as handled: a code missed
                    // while locked is not copied later by a revision of the same message.
                    alreadyHandled = deliveryMemory.recentCopied() + deliveryMemory.recentDelivered(),
                )
                if (decision.copies && ClipboardHelper.copySensitive(this, processed.result.code)) {
                    deliveryMemory.recordCopied(pair)
                }
                if (OtpCopyPolicy.shouldPublish(pair, deliveryMemory.recentDelivered())) {
                    OtpResultNotification.show(
                        this,
                        processed.result.code,
                        processed.result.source ?: sender,
                    )
                    deliveryMemory.recordDelivered(pair)
                }
                finishResult(
                    outcome("OTP", "OTP_ACCEPTED")
                        .put("code", processed.result.code)
                        .put("source", processed.result.source ?: JSONObject.NULL)
                        .put("sourceDisposition", processed.sourceDisposition.name.lowercase())
                        .put("copy", decision.name.lowercase()),
                )
            }
            is OtpOutcome.Rejected -> {
                finishResult(outcome("REJECTED", processed.reason.name))
            }
        }
    }

    /**
     * True only while the exact revision that produced this result is still posted.
     *
     * The same notification key can carry different content after an edit, so the key
     * alone is not enough: the post time must match too. An unreadable or unknown state
     * fails closed for the copy, which only ever means the user keeps the manual action.
     */
    private fun isCurrentRevision(sbn: StatusBarNotification): Boolean {
        val posted = try {
            activeNotifications.map { it.key to it.postTime }
        } catch (_: Exception) {
            null
        }
        return NotificationFreshness.isCurrentRevision(posted, sbn.key, sbn.postTime)
    }

    private fun addDiagnostic(
        app: NeedleBubApplication,
        captureEnabled: Boolean,
        sbn: StatusBarNotification,
        stage: String,
        status: String,
        errorCode: String?,
    ) {
        if (!captureEnabled) return
        app.developerDataStore.addDiagnostic(DiagnosticEntry(
            id = 0,
            createdAt = System.currentTimeMillis(),
            packageName = sbn.packageName,
            category = sbn.notification.category,
            stage = stage,
            pack = null,
            status = status,
            errorCode = errorCode,
            durationMs = null,
            pssKb = null,
            coldLoad = null,
        ))
    }

    private fun finish(
        app: NeedleBubApplication,
        captureId: String?,
        finalOutcome: JSONObject,
        durationMs: Long? = null,
        recordActivity: Boolean = true,
        extra: JSONObject? = null,
    ) {
        extra?.keys()?.forEach { key -> finalOutcome.put(key, extra.get(key)) }
        captureId?.let { app.developerDataStore.attachOutcome(it, finalOutcome) }
        if (recordActivity) {
            app.featureActivity.record(
                featureId = OTP_FEATURE_ID,
                decision = finalOutcome.getString("decision"),
                durationMs = durationMs,
            )
        }
    }

    private fun outcome(decision: String, reasonCode: String) = JSONObject()
        .put("decision", decision)
        .put("reasonCode", reasonCode)

    /**
     * Writes an explicit JSON null for an unknown value.
     *
     * `JSONObject.put(key, null)` removes the key, which would hide that a value was never
     * observable instead of recording it as unknown.
     */
    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private companion object {
        const val TAG = "NeedleCapture"
        const val NOTIFICATION_TIMEOUT_MS = 5_000L
        const val MAX_TITLE_CHARS = 512
        const val MAX_BODY_CHARS = 8_192
        const val OTP_FEATURE_ID = "otp"

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
