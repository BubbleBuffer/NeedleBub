package de.x0bubbuff.needlebub.notifications

/**
 * Freshness of the revision that produced a result.
 *
 * The same notification key can carry different content after an edit, so a match needs the
 * post time as well. Unknown freshness (the platform refused the query) fails closed: the
 * copy is skipped, which only ever means the user keeps the manual action.
 */
object NotificationFreshness {
    fun isRecent(postTime: Long, now: Long): Boolean = now >= postTime && now - postTime <= 120_000L

    /** @param posted key and post time of every currently posted notification, or null when unknown. */
    fun isCurrentRevision(posted: List<Pair<String, Long>>?, key: String, postTime: Long): Boolean =
        posted != null && posted.any { it.first == key && it.second == postTime }
}
