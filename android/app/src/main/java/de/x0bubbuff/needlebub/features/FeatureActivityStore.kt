package de.x0bubbuff.needlebub.features

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class FeatureActivityBucket(
    val epochDay: Long,
    val otp: Int = 0,
    val rejected: Int = 0,
    val errors: Int = 0,
    val suppressed: Int = 0,
    val notRun: Int = 0,
    val completedInferenceCount: Int = 0,
    val durationTotalMs: Long = 0,
    val lastActivityAt: Long? = null,
)

data class FeatureActivitySummary(
    val featureId: String,
    val days: Int,
    val todayOtp: Int,
    val todayRejected: Int,
    val todayErrors: Int,
    val todaySuppressed: Int,
    val todayNotRun: Int,
    val totalOtp: Int,
    val totalRejected: Int,
    val totalErrors: Int,
    val totalSuppressed: Int,
    val totalNotRun: Int,
    val completedInferenceCount: Int,
    val averageDurationMs: Long?,
    val lastActivityAt: Long?,
)

class FeatureActivityLedger(
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    init {
        require(retentionDays > 0)
    }

    fun record(
        buckets: List<FeatureActivityBucket>,
        epochDay: Long,
        occurredAt: Long,
        decision: String,
        durationMs: Long?,
    ): List<FeatureActivityBucket> {
        val referenceDay = maxOf(epochDay, buckets.maxOfOrNull { it.epochDay } ?: epochDay)
        val retained = prune(buckets, referenceDay).toMutableList()
        if (epochDay < referenceDay - retentionDays + 1) return retained
        if (decision !in TRACKED_DECISIONS) return retained
        val index = retained.indexOfFirst { it.epochDay == epochDay }
        val current = retained.getOrNull(index) ?: FeatureActivityBucket(epochDay)
        val completed = durationMs != null && durationMs >= 0
        val updated = current.copy(
            otp = current.otp + if (decision == "OTP") 1 else 0,
            rejected = current.rejected + if (decision == "REJECTED") 1 else 0,
            errors = current.errors + if (decision == "ERROR") 1 else 0,
            suppressed = current.suppressed + if (decision == "SUPPRESSED") 1 else 0,
            notRun = current.notRun + if (decision == "NOT_RUN") 1 else 0,
            completedInferenceCount = current.completedInferenceCount + if (completed) 1 else 0,
            durationTotalMs = current.durationTotalMs + if (completed) durationMs!! else 0,
            lastActivityAt = maxOf(current.lastActivityAt ?: occurredAt, occurredAt),
        )
        if (index >= 0) retained[index] = updated else retained += updated
        return retained.sortedBy { it.epochDay }
    }

    fun summary(
        featureId: String,
        buckets: List<FeatureActivityBucket>,
        todayEpochDay: Long,
        days: Int,
    ): FeatureActivitySummary {
        val boundedDays = days.coerceIn(1, retentionDays)
        val minimumDay = todayEpochDay - boundedDays + 1
        val retained = buckets.filter { it.epochDay in minimumDay..todayEpochDay }
        val today = retained.firstOrNull { it.epochDay == todayEpochDay }
        val completed = retained.sumOf { it.completedInferenceCount }
        val duration = retained.sumOf { it.durationTotalMs }
        return FeatureActivitySummary(
            featureId = featureId,
            days = boundedDays,
            todayOtp = today?.otp ?: 0,
            todayRejected = today?.rejected ?: 0,
            todayErrors = today?.errors ?: 0,
            todaySuppressed = today?.suppressed ?: 0,
            todayNotRun = today?.notRun ?: 0,
            totalOtp = retained.sumOf { it.otp },
            totalRejected = retained.sumOf { it.rejected },
            totalErrors = retained.sumOf { it.errors },
            totalSuppressed = retained.sumOf { it.suppressed },
            totalNotRun = retained.sumOf { it.notRun },
            completedInferenceCount = completed,
            averageDurationMs = if (completed == 0) null else duration / completed,
            lastActivityAt = retained.mapNotNull { it.lastActivityAt }.maxOrNull(),
        )
    }

    fun prune(buckets: List<FeatureActivityBucket>, todayEpochDay: Long): List<FeatureActivityBucket> {
        val minimumDay = todayEpochDay - retentionDays + 1
        return buckets.filter { it.epochDay in minimumDay..todayEpochDay }.sortedBy { it.epochDay }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        private val TRACKED_DECISIONS = setOf("OTP", "REJECTED", "ERROR", "SUPPRESSED", "NOT_RUN")
    }
}

class FeatureActivityStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val ledger = FeatureActivityLedger()

    @Synchronized
    fun record(featureId: String, decision: String, durationMs: Long?) {
        requireFeatureId(featureId)
        val now = nowMillis()
        val epochDay = epochDay(now)
        val updated = ledger.record(load(featureId), epochDay, now, decision, durationMs)
        preferences.edit().putString(key(featureId), encode(updated)).apply()
    }

    @Synchronized
    fun summary(featureId: String, days: Int = FeatureActivityLedger.DEFAULT_RETENTION_DAYS): FeatureActivitySummary {
        requireFeatureId(featureId)
        val today = epochDay(nowMillis())
        val pruned = ledger.prune(load(featureId), today)
        preferences.edit().putString(key(featureId), encode(pruned)).apply()
        return ledger.summary(featureId, pruned, today, days)
    }

    @Synchronized
    fun reset(featureId: String?) {
        if (featureId != null) {
            requireFeatureId(featureId)
            preferences.edit().remove(key(featureId)).apply()
            return
        }
        preferences.edit().clear().apply()
    }

    private fun load(featureId: String): List<FeatureActivityBucket> {
        val raw = preferences.getString(key(featureId), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(FeatureActivityBucket(
                        epochDay = item.getLong("epochDay"),
                        otp = item.optInt("otp"),
                        rejected = item.optInt("rejected"),
                        errors = item.optInt("errors"),
                        suppressed = item.optInt("suppressed"),
                        notRun = item.optInt("notRun"),
                        completedInferenceCount = item.optInt("completedInferenceCount"),
                        durationTotalMs = item.optLong("durationTotalMs"),
                        lastActivityAt = if (item.isNull("lastActivityAt")) null else item.optLong("lastActivityAt"),
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encode(buckets: List<FeatureActivityBucket>): String {
        val array = JSONArray()
        buckets.forEach { bucket ->
            array.put(JSONObject()
                .put("epochDay", bucket.epochDay)
                .put("otp", bucket.otp)
                .put("rejected", bucket.rejected)
                .put("errors", bucket.errors)
                .put("suppressed", bucket.suppressed)
                .put("notRun", bucket.notRun)
                .put("completedInferenceCount", bucket.completedInferenceCount)
                .put("durationTotalMs", bucket.durationTotalMs)
                .put("lastActivityAt", bucket.lastActivityAt ?: JSONObject.NULL))
        }
        return array.toString()
    }

    private fun epochDay(epochMillis: Long): Long = Instant.ofEpochMilli(epochMillis)
        .atZone(zoneId())
        .toLocalDate()
        .toEpochDay()

    private fun key(featureId: String) = "$KEY_PREFIX$featureId"

    private fun requireFeatureId(featureId: String) {
        require(featureId.matches(FEATURE_ID)) { "Feature ID is invalid" }
    }

    private companion object {
        const val PREFERENCES_NAME = "feature_activity"
        const val KEY_PREFIX = "feature."
        val FEATURE_ID = Regex("^[a-z][a-z0-9_-]{0,63}$")
    }
}
