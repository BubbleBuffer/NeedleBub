package de.x0bubbuff.needlebub.notifications

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class NotificationDeduplicator(
    private val ttlMs: Long = 90_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun shouldProcess(packageName: String, notificationKey: String, content: String): Boolean {
        val now = clockMs()
        seen.entries.removeIf { it.value <= now - ttlMs }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return seen.putIfAbsent("$packageName\u0000$notificationKey\u0000$digest", now) == null
    }
}
