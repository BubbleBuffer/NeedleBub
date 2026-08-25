package de.x0bubbuff.needlebub.developer

object CaptureRetentionPolicy {
    const val MAX_RECORDS = 10_000
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000

    fun expired(createdAt: Long, now: Long): Boolean = createdAt < now - MAX_AGE_MS
    fun excessCount(count: Int): Int = (count - MAX_RECORDS).coerceAtLeast(0)
}
