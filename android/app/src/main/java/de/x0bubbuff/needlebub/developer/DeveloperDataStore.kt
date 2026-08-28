package de.x0bubbuff.needlebub.developer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeveloperDataSummary(
    val count: Int,
    val storedBytes: Long,
    val oldestAt: Long?,
)

data class DiagnosticEntry(
    val id: Long,
    val createdAt: Long,
    val packageName: String?,
    val category: String?,
    val stage: String,
    val pack: String?,
    val status: String,
    val errorCode: String?,
    val durationMs: Long?,
    val pssKb: Long?,
    val coldLoad: Boolean?,
)

class DeveloperDataStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "developer_data.db",
    null,
    1,
) {
    private val crypto = RecordCrypto()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE captures (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, content_hash TEXT NOT NULL UNIQUE, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX captures_created ON captures(created_at)")
        db.execSQL("CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, package_name TEXT, category TEXT, stage TEXT NOT NULL, pack TEXT, status TEXT NOT NULL, error_code TEXT, duration_ms INTEGER, pss_kb INTEGER, cold_load INTEGER)")
        db.execSQL("CREATE INDEX diagnostics_created ON diagnostics(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insertCapture(record: JSONObject): String? {
        val id = record.optString("id").ifBlank { UUID.randomUUID().toString() }
        record.put("id", id)
        val createdAt = record.optLong("capturedAtEpochMs", System.currentTimeMillis())
        val contentHash = sha256(listOf(
            record.optString("packageName"),
            record.optString("notificationKeyHash"),
            record.optString("title"),
            record.optString("body"),
        ).joinToString("\u001f"))
        val values = ContentValues().apply {
            put("id", id)
            put("created_at", createdAt)
            put("content_hash", contentHash)
            put("payload", crypto.encrypt(record.toString().encodeToByteArray()))
        }
        val inserted = writableDatabase.insertWithOnConflict("captures", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        prune()
        return id.takeIf { inserted != -1L }
    }

    @Synchronized
    fun attachRuntime(id: String, runtime: JSONObject) {
        updateCapture(id) { it.put("runtime", runtime) }
    }

    @Synchronized
    fun attachOutcome(id: String, outcome: JSONObject) {
        updateCapture(id) { it.put("outcome", outcome) }
    }

    private fun updateCapture(id: String, update: (JSONObject) -> JSONObject) {
        val db = writableDatabase
        db.rawQuery("SELECT payload FROM captures WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return
            val record = update(JSONObject(crypto.decrypt(cursor.getBlob(0)).decodeToString()))
            val values = ContentValues().apply { put("payload", crypto.encrypt(record.toString().encodeToByteArray())) }
            db.update("captures", values, "id = ?", arrayOf(id))
        }
    }

    @Synchronized
    fun captureSummaries(limit: Int, before: Long?, filter: String?): Pair<List<JSONObject>, Long?> {
        val requested = limit.coerceIn(1, 100)
        val selection = if (before == null) null else "created_at < ?"
        val args = before?.let { arrayOf(it.toString()) }
        val output = mutableListOf<JSONObject>()
        var nextCursor: Long? = null
        readableDatabase.query(
            "captures", arrayOf("created_at", "payload"), selection, args,
            null, null, "created_at DESC", (requested * 4 + 1).toString(),
        ).use { cursor ->
            while (cursor.moveToNext() && output.size < requested) {
                val record = JSONObject(crypto.decrypt(cursor.getBlob(1)).decodeToString())
                val outcome = record.optJSONObject("outcome")
                val decision = outcome?.optString("decision").takeUnless { it.isNullOrBlank() } ?: "PENDING"
                if (!filter.isNullOrBlank() && filter != "ALL" && decision != filter) continue
                val createdAt = cursor.getLong(0)
                output += JSONObject()
                    .put("id", record.getString("id"))
                    .put("capturedAt", createdAt)
                    .put("appLabel", record.optString("appLabel", record.optString("packageName")))
                    .put("title", record.optString("title"))
                    .put("decision", decision)
                    .put("reasonCode", outcome?.optString("reasonCode") ?: "INTERRUPTED")
                nextCursor = createdAt
            }
        }
        return output to nextCursor.takeIf { output.size == requested }
    }

    @Synchronized
    fun capture(id: String): JSONObject? {
        readableDatabase.rawQuery("SELECT payload FROM captures WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return JSONObject(crypto.decrypt(cursor.getBlob(0)).decodeToString())
        }
    }

    @Synchronized
    fun addDiagnostic(entry: DiagnosticEntry) {
        val values = ContentValues().apply {
            put("created_at", entry.createdAt)
            put("package_name", entry.packageName)
            put("category", entry.category)
            put("stage", entry.stage)
            put("pack", entry.pack)
            put("status", entry.status)
            put("error_code", entry.errorCode)
            entry.durationMs?.let { put("duration_ms", it) }
            entry.pssKb?.let { put("pss_kb", it) }
            entry.coldLoad?.let { put("cold_load", if (it) 1 else 0) }
        }
        writableDatabase.insert("diagnostics", null, values)
        prune()
    }

    @Synchronized
    fun summary(): DeveloperDataSummary {
        readableDatabase.rawQuery("SELECT COUNT(*), COALESCE(SUM(LENGTH(payload)), 0), MIN(created_at) FROM captures", null).use { cursor ->
            cursor.moveToFirst()
            return DeveloperDataSummary(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2).takeIf { !cursor.isNull(2) })
        }
    }

    @Synchronized
    fun diagnostics(limit: Int = 100): List<DiagnosticEntry> {
        val output = mutableListOf<DiagnosticEntry>()
        readableDatabase.rawQuery(
            "SELECT id, created_at, package_name, category, stage, pack, status, error_code, duration_ms, pss_kb, cold_load FROM diagnostics ORDER BY id DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) output += DiagnosticEntry(
                id = cursor.getLong(0), createdAt = cursor.getLong(1), packageName = cursor.getString(2),
                category = cursor.getString(3), stage = cursor.getString(4), pack = cursor.getString(5),
                status = cursor.getString(6), errorCode = cursor.getString(7),
                durationMs = cursor.getLong(8).takeIf { !cursor.isNull(8) },
                pssKb = cursor.getLong(9).takeIf { !cursor.isNull(9) },
                coldLoad = (cursor.getInt(10) != 0).takeIf { !cursor.isNull(10) },
            )
        }
        return output
    }

    @Synchronized
    fun encryptedExport(passphrase: CharArray): Pair<ByteArray, Int> {
        val summary = summary()
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(JSONObject()
                .put("type", "needlebub.capture.manifest")
                .put("formatVersion", 1)
                .put("exportedAtEpochMs", System.currentTimeMillis())
                .put("recordCount", summary.count)
                .toString())
            readableDatabase.rawQuery("SELECT payload FROM captures ORDER BY created_at, id", null).use { cursor ->
                while (cursor.moveToNext()) writer.appendLine(crypto.decrypt(cursor.getBlob(0)).decodeToString())
            }
        }
        return CaptureEnvelope.encrypt(compressed.toByteArray(), passphrase) to summary.count
    }

    @Synchronized
    fun writePlaintextJsonl(writer: Writer, canContinue: () -> Boolean) {
        check(canContinue()) { "ADB capture access expired" }
        val count = summary().count
        writer.appendLine(JSONObject()
            .put("type", "needlebub.capture.adb")
            .put("formatVersion", 2)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .put("recordCount", count)
            .toString())
        readableDatabase.rawQuery("SELECT payload FROM captures ORDER BY created_at, id", null).use { cursor ->
            while (cursor.moveToNext()) {
                check(canContinue()) { "ADB capture access expired" }
                writer.appendLine(crypto.decrypt(cursor.getBlob(0)).decodeToString())
            }
        }
        writer.flush()
    }

    @Synchronized
    fun clearCaptures(): Int = writableDatabase.delete("captures", null, null)

    @Synchronized
    fun clearDiagnostics(): Int = writableDatabase.delete("diagnostics", null, null)

    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cutoff = now - CaptureRetentionPolicy.MAX_AGE_MS
        db.delete("captures", "created_at < ?", arrayOf(cutoff.toString()))
        db.delete("diagnostics", "created_at < ?", arrayOf(cutoff.toString()))
        pruneOldest(db, "captures", "created_at")
        pruneOldest(db, "diagnostics", "id")
    }

    private fun pruneOldest(db: SQLiteDatabase, table: String, orderColumn: String) {
        val count = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val excess = CaptureRetentionPolicy.excessCount(count)
        if (excess > 0) {
            db.delete(
                table,
                "id IN (SELECT id FROM $table ORDER BY $orderColumn ASC LIMIT ?)",
                arrayOf(excess.toString()),
            )
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private class RecordCrypto {
        private val key: SecretKey by lazy {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            ).apply {
                init(KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            check(nonce.size == 12) { "Android Keystore returned an invalid GCM nonce" }
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(nonce.size + ciphertext.size).put(nonce).put(ciphertext).array()
        }

        fun decrypt(payload: ByteArray): ByteArray {
            require(payload.size >= 28) { "Encrypted capture record is truncated" }
            val nonce = payload.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return cipher.doFinal(payload, 12, payload.size - 12)
        }

        private companion object { const val KEY_ALIAS = "needlebub-developer-capture-v1" }
    }
}
