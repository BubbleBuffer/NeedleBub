package de.x0bubbuff.needlebub.notifications

import android.app.Notification
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.LinkedHashSet

data class NotificationMessageSnapshot(
    val text: String,
    val sender: String?,
    val senderKey: String?,
    val timestamp: Long,
)

data class NotificationSnapshot(
    val lifecycleKey: String,
    val title: String,
    val appLabel: String,
    val body: String,
    val textLines: List<String>,
    val messages: List<NotificationMessageSnapshot>,
    val category: String?,
    val hasMediaSession: Boolean,
    val template: String?,
    val flags: Int,
    val progressMax: Int,
    val progressIndeterminate: Boolean,
    val postedAt: Long,
)

enum class NotificationExtractionKind(val wireName: String) {
    ORDINARY("ordinary"),
    MESSAGING("messaging"),
    INBOX("inbox"),
    POLICY("policy"),
}

enum class NotificationObservationAction {
    CREATE,
    TOUCH,
}

data class NotificationCandidate(
    val lifecycleKey: String,
    val itemKey: String,
    val sender: String,
    val title: String,
    val body: String,
    val extractionKind: NotificationExtractionKind,
    val policyDecision: String,
    val observedAt: Long,
)

data class NotificationAnalysis(
    val candidate: NotificationCandidate,
    val action: NotificationObservationAction,
    val shouldInfer: Boolean,
    val reasonCode: String?,
    val policyTransition: Boolean = false,
)

class NotificationLifecycleAnalyzer(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxLifecycles: Int = DEFAULT_MAX_LIFECYCLES,
    private val maxMessageFingerprints: Int = DEFAULT_MAX_MESSAGE_FINGERPRINTS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private data class LifecycleState(
        var lastObservedAt: Long,
        val seenMessages: LinkedHashSet<String> = linkedSetOf(),
        val seenLines: LinkedHashSet<String> = linkedSetOf(),
        var lastCandidate: NotificationCandidate? = null,
        var policyCandidate: NotificationCandidate? = null,
        var lastOrdinaryBody: String? = null,
    )

    private val lifecycles = LinkedHashMap<String, LifecycleState>(16, 0.75f, true)

    init {
        require(ttlMs > 0)
        require(maxLifecycles > 0)
        require(maxMessageFingerprints > 0)
    }

    @Synchronized
    fun analyze(snapshot: NotificationSnapshot): NotificationAnalysis {
        val now = clockMs()
        prune(now)
        val state = lifecycles.getOrPut(snapshot.lifecycleKey) { LifecycleState(now) }
        state.lastObservedAt = now

        val provisionalBody = snapshot.messages.maxByOrNull { it.timestamp }?.text
            ?: snapshot.textLines.joinToString("\n").ifBlank { snapshot.body }
        val decision = NotificationInferencePolicy.decision(
            provisionalBody,
            snapshot.category,
            snapshot.hasMediaSession,
            snapshot.template,
            snapshot.flags,
            snapshot.progressMax,
            snapshot.progressIndeterminate,
        )
        val analysis = if (decision != NotificationInferencePolicy.INFER) {
            analyzePolicy(snapshot, state, provisionalBody, decision)
        } else if (snapshot.messages.isNotEmpty()) {
            analyzeMessaging(snapshot, state)
        } else if (snapshot.textLines.isNotEmpty() || snapshot.template?.endsWith("\$InboxStyle") == true) {
            analyzeInbox(snapshot, state)
        } else {
            analyzeOrdinary(snapshot, state)
        }
        trimToCapacity()
        return analysis
    }

    @Synchronized
    fun remove(lifecycleKey: String) {
        lifecycles.remove(lifecycleKey)
    }

    private fun analyzePolicy(
        snapshot: NotificationSnapshot,
        state: LifecycleState,
        body: String,
        decision: String,
    ): NotificationAnalysis {
        val previous = state.lastCandidate
        val priorPolicy = state.policyCandidate
        val itemKey = hash("${snapshot.lifecycleKey}\u001fpolicy")
        val candidate = candidate(
            snapshot,
            itemKey,
            body,
            NotificationExtractionKind.POLICY,
            decision,
        )
        state.lastCandidate = candidate
        state.policyCandidate = candidate
        state.lastOrdinaryBody = body
        return NotificationAnalysis(
            candidate,
            if (priorPolicy == null) NotificationObservationAction.CREATE else NotificationObservationAction.TOUCH,
            shouldInfer = false,
            reasonCode = policyReason(decision),
            policyTransition = previous?.extractionKind != NotificationExtractionKind.POLICY ||
                previous.policyDecision != decision,
        )
    }

    private fun analyzeMessaging(
        snapshot: NotificationSnapshot,
        state: LifecycleState,
    ): NotificationAnalysis {
        val indexed = snapshot.messages.mapIndexed { index, message ->
            Triple(index, message, messageFingerprint(message))
        }
        val tracked = indexed
            .sortedWith(compareBy({ it.second.timestamp }, { it.first }))
            .takeLast(maxMessageFingerprints)
        val unseen = tracked.filter { it.third !in state.seenMessages }
        tracked.forEach { state.seenMessages += it.third }
        trimSet(state.seenMessages, maxMessageFingerprints)
        val newest = unseen.maxWithOrNull(compareBy<Triple<Int, NotificationMessageSnapshot, String>>(
            { it.second.timestamp },
            { it.first },
        ))
        if (newest == null) return touchLast(snapshot, state, "NO_NEW_MESSAGE")

        val message = newest.second
        val itemKey = hash("${snapshot.lifecycleKey}\u001fmessaging\u001f${newest.third}")
        val candidate = candidate(
            snapshot,
            itemKey,
            message.text,
            NotificationExtractionKind.MESSAGING,
            NotificationInferencePolicy.INFER,
            message.sender?.takeIf { it.isNotBlank() },
        )
        state.lastCandidate = candidate
        state.lastOrdinaryBody = null
        return NotificationAnalysis(candidate, NotificationObservationAction.CREATE, true, null)
    }

    private fun analyzeInbox(
        snapshot: NotificationSnapshot,
        state: LifecycleState,
    ): NotificationAnalysis {
        val lines = snapshot.textLines.map(String::trim).filter(String::isNotBlank)
        val tracked = lines.takeLast(maxMessageFingerprints)
        val unseen = tracked.filter { hash(it) !in state.seenLines }
        tracked.forEach { state.seenLines += hash(it) }
        trimSet(state.seenLines, maxMessageFingerprints)
        if (unseen.isEmpty()) return touchLast(snapshot, state, "NO_NEW_LINE")

        val body = unseen.joinToString("\n")
        val itemKey = hash("${snapshot.lifecycleKey}\u001finbox\u001f${unseen.joinToString("\u001f")}")
        val candidate = candidate(
            snapshot,
            itemKey,
            body,
            NotificationExtractionKind.INBOX,
            NotificationInferencePolicy.INFER,
        )
        state.lastCandidate = candidate
        state.lastOrdinaryBody = null
        return NotificationAnalysis(candidate, NotificationObservationAction.CREATE, true, null)
    }

    private fun analyzeOrdinary(
        snapshot: NotificationSnapshot,
        state: LifecycleState,
    ): NotificationAnalysis {
        val previous = state.lastCandidate
        val previousBody = state.lastOrdinaryBody
        val ongoing = snapshot.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (
            previous != null &&
            previous.extractionKind == NotificationExtractionKind.ORDINARY &&
            (previousBody == snapshot.body || isVolatileRevision(previousBody.orEmpty(), snapshot.body, ongoing))
        ) {
            state.lastOrdinaryBody = snapshot.body
            val touched = previous.copy(
                title = snapshot.title,
                body = snapshot.body,
                observedAt = snapshot.postedAt,
            )
            state.lastCandidate = touched
            return NotificationAnalysis(touched, NotificationObservationAction.TOUCH, false, "DUPLICATE_REVISION")
        }

        val itemKey = hash("${snapshot.lifecycleKey}\u001fordinary\u001f${snapshot.title}\u001f${snapshot.body}")
        val candidate = candidate(
            snapshot,
            itemKey,
            snapshot.body,
            NotificationExtractionKind.ORDINARY,
            NotificationInferencePolicy.INFER,
        )
        state.lastCandidate = candidate
        state.lastOrdinaryBody = snapshot.body
        return NotificationAnalysis(candidate, NotificationObservationAction.CREATE, true, null)
    }

    private fun touchLast(
        snapshot: NotificationSnapshot,
        state: LifecycleState,
        reason: String,
    ): NotificationAnalysis {
        val previous = state.lastCandidate ?: candidate(
            snapshot,
            hash("${snapshot.lifecycleKey}\u001faggregate"),
            snapshot.body,
            NotificationExtractionKind.ORDINARY,
            NotificationInferencePolicy.INFER,
        )
        val touched = previous.copy(observedAt = snapshot.postedAt)
        state.lastCandidate = touched
        return NotificationAnalysis(touched, NotificationObservationAction.TOUCH, false, reason)
    }

    private fun candidate(
        snapshot: NotificationSnapshot,
        itemKey: String,
        body: String,
        kind: NotificationExtractionKind,
        policyDecision: String,
        sender: String? = null,
    ) = NotificationCandidate(
        lifecycleKey = snapshot.lifecycleKey,
        itemKey = itemKey,
        sender = sender ?: snapshot.title.ifBlank { snapshot.appLabel },
        title = snapshot.title,
        body = body,
        extractionKind = kind,
        policyDecision = policyDecision,
        observedAt = snapshot.postedAt,
    )

    private fun isVolatileRevision(previous: String, current: String, ongoing: Boolean): Boolean {
        if (previous == current) return false
        val previousOtpTokens = OTP_LIKE.findAll(previous)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length in 4..8 }
            .toSet()
        val currentOtpTokens = OTP_LIKE.findAll(current)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length in 4..8 }
            .toSet()
        if (previousOtpTokens != currentOtpTokens) return false
        if (normalizeCounters(previous) == normalizeCounters(current)) return true
        return ongoing && normalizeVolatile(previous) == normalizeVolatile(current)
    }

    private fun normalizeCounters(value: String): String = SHORT_NUMBER.replace(
        FRACTION.replace(
            PERCENT.replace(
                value,
                "#percent",
            ),
            "#fraction",
        ),
        "#count",
    )

    private fun normalizeVolatile(value: String): String =
        normalizeCounters(CLOCK.replace(value, "#time"))

    private fun policyReason(decision: String): String = when (decision) {
        NotificationInferencePolicy.EXCLUDED_BLANK -> "BLANK_NOTIFICATION"
        NotificationInferencePolicy.EXCLUDED_NAVIGATION -> "POLICY_NAVIGATION"
        NotificationInferencePolicy.EXCLUDED_MEDIA -> "POLICY_MEDIA"
        NotificationInferencePolicy.EXCLUDED_PROGRESS -> "POLICY_PROGRESS"
        NotificationInferencePolicy.EXCLUDED_ONGOING_SERVICE -> "POLICY_ONGOING_SERVICE"
        else -> "POLICY_EXCLUDED"
    }

    private fun prune(now: Long) {
        lifecycles.entries.removeIf { it.value.lastObservedAt <= now - ttlMs }
    }

    private fun trimToCapacity() {
        while (lifecycles.size > maxLifecycles) {
            val eldest = lifecycles.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    private fun trimSet(values: LinkedHashSet<String>, capacity: Int) {
        while (values.size > capacity) {
            val oldest = values.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun messageFingerprint(message: NotificationMessageSnapshot): String = hash(
        "${message.timestamp}\u001f${message.senderKey.orEmpty()}\u001f${message.sender.orEmpty()}\u001f${message.text}",
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1_000L
        const val DEFAULT_MAX_LIFECYCLES = 512
        const val DEFAULT_MAX_MESSAGE_FINGERPRINTS = 64
        val CLOCK = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
        val PERCENT = Regex("""\b\d{1,3}%""")
        val FRACTION = Regex("""\b\d{1,3}\s*/\s*\d{1,3}\b""")
        val SHORT_NUMBER = Regex("""(?<![\d:])\d{1,3}(?![\d:])""")
        val OTP_LIKE = Regex("""\b\d(?:[\s-]?\d){3,7}\b""")
    }
}
