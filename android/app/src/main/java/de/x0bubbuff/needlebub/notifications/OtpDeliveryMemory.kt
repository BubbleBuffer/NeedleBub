package de.x0bubbuff.needlebub.notifications

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** A notification item and code pair, carried as salted hashes. */
data class OtpDeliveryRecord(val itemHash: String, val codeHash: String)

/**
 * Bounded, persisted memory of recently delivered and recently copied (item, code) pairs.
 *
 * It is a dedupe index, not a secret store: entries are salted hashes kept in app-private
 * preferences and capped in size. Remembered pairs do not become eligible again merely
 * because time passes or the device unlocks. A short code hash is still guessable by
 * anyone who can read the file, so nothing here is treated as a security boundary.
 */
class OtpDeliveryMemory(
    context: Context,
    private val maxEntries: Int = MAX_ENTRIES,
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences("automation", Context.MODE_PRIVATE)

    init {
        require(maxEntries > 0)
        require(windowMs > 0)
    }

    @Synchronized
    fun record(itemKey: String, code: String): OtpDeliveryRecord {
        val salt = salt()
        return OtpDeliveryRecord(hash(salt, itemKey), hash(salt, code))
    }

    @Synchronized
    fun recentDelivered(): List<OtpDeliveryRecord> = read(KEY_DELIVERED)

    @Synchronized
    fun recentCopied(): List<OtpDeliveryRecord> = read(KEY_COPIED)

    @Synchronized
    fun recordDelivered(record: OtpDeliveryRecord) {
        write(KEY_DELIVERED, record)
    }

    @Synchronized
    fun recordCopied(record: OtpDeliveryRecord) {
        write(KEY_COPIED, record)
    }

    private fun read(key: String): List<OtpDeliveryRecord> {
        val now = clock()
        val stored = preferences.getString(key, null) ?: return emptyList()
        val entries = stored.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 3) return@mapNotNull null
            val recordedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (windowMs != Long.MAX_VALUE && (now - recordedAt > windowMs || now < recordedAt)) return@mapNotNull null
            OtpDeliveryRecord(parts[0], parts[1]) to recordedAt
        }
        if (entries.size != stored.split(';').size) {
            persist(key, entries)
        }
        return entries.map { it.first }
    }

    private fun write(key: String, record: OtpDeliveryRecord) {
        val now = clock()
        val entries = read(key).map { it to now } + (record to now)
        persist(key, entries.takeLast(maxEntries))
    }

    private fun persist(key: String, entries: List<Pair<OtpDeliveryRecord, Long>>) {
        val encoded = entries.joinToString(";") { (record, recordedAt) ->
            "${record.itemHash}:${record.codeHash}:$recordedAt"
        }
        preferences.edit().putString(key, encoded).apply()
    }

    private fun salt(): String {
        preferences.getString(KEY_SALT, null)?.let { return it }
        val generated = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        preferences.edit().putString(KEY_SALT, generated).apply()
        return generated
    }

    private fun hash(salt: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$salt|$value".encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        /** Never replay a remembered code on a timer; capacity bounds storage instead. */
        const val WINDOW_MS = Long.MAX_VALUE
        const val MAX_ENTRIES = 128
        private const val KEY_DELIVERED = "recent_delivered_pairs"
        private const val KEY_COPIED = "recent_copied_pairs"
        private const val KEY_SALT = "delivery_hash_salt"
    }
}
