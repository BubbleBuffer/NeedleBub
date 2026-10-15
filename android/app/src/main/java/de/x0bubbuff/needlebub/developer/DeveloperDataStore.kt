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
    val lifecycleCount: Int,
    val observedRevisionCount: Long,
)

data class CaptureWriteResult(
    val id: String,
    val isNew: Boolean,
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

class DeveloperDataStore(
    context: Context,
    private val maxSnapshotBytes: Long = CaptureStream.MAX_SNAPSHOT_BYTES,
) : SQLiteOpenHelper(
    context.applicationContext,
    "developer_data.db",
    null,
    2,
), CaptureExportSource {
    private val crypto = RecordCrypto()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE captures (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, lifecycle_key TEXT, item_key TEXT, content_hash TEXT NOT NULL UNIQUE, revision_count INTEGER NOT NULL DEFAULT 1, superseded_count INTEGER NOT NULL DEFAULT 0, payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX captures_created ON captures(created_at)")
        db.execSQL("CREATE INDEX captures_updated ON captures(updated_at)")
        db.execSQL("CREATE INDEX captures_lifecycle ON captures(lifecycle_key)")
        db.execSQL("CREATE UNIQUE INDEX captures_item ON captures(item_key) WHERE item_key IS NOT NULL")
        db.execSQL("CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, package_name TEXT, category TEXT, stage TEXT NOT NULL, pack TEXT, status TEXT NOT NULL, error_code TEXT, duration_ms INTEGER, pss_kb INTEGER, cold_load INTEGER)")
        db.execSQL("CREATE INDEX diagnostics_created ON diagnostics(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE captures ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE captures ADD COLUMN lifecycle_key TEXT")
            db.execSQL("ALTER TABLE captures ADD COLUMN item_key TEXT")
            db.execSQL("ALTER TABLE captures ADD COLUMN revision_count INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE captures ADD COLUMN superseded_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE captures SET updated_at = created_at WHERE updated_at = 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS captures_updated ON captures(updated_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS captures_lifecycle ON captures(lifecycle_key)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS captures_item ON captures(item_key) WHERE item_key IS NOT NULL")
        }
    }

    @Synchronized
    fun insertCapture(record: JSONObject): String? {
        val result = upsertCapture(record)
        return result.id.takeIf { result.isNew }
    }

    @Synchronized
    fun upsertCapture(record: JSONObject): CaptureWriteResult {
        val id = record.optString("id").ifBlank { UUID.randomUUID().toString() }
        val createdAt = record.optLong("capturedAtEpochMs", System.currentTimeMillis())
        val lifecycleKey = record.optString("lifecycleKey")
            .ifBlank { record.optString("notificationKeyHash") }
            .takeIf { it.isNotBlank() }
        val legacyContentHash = sha256(listOf(
            record.optString("packageName"),
            lifecycleKey.orEmpty(),
            record.optString("title"),
            record.optString("body"),
        ).joinToString("\u001f"))
        val itemKey = record.optString("itemKey").ifBlank { legacyContentHash }
        val contentHash = sha256("logical\u001f$itemKey")
        val db = writableDatabase
        val updatedAt = nextUpdatedAt(db)
        db.rawQuery(
            "SELECT id, revision_count, superseded_count, payload FROM captures WHERE item_key = ? OR (item_key IS NULL AND content_hash = ?) LIMIT 1",
            arrayOf(itemKey, legacyContentHash),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val existingId = cursor.getString(0)
                val revisionCount = cursor.getInt(1) + 1
                val supersededCount = cursor.getInt(2)
                val current = JSONObject(crypto.decrypt(cursor.getBlob(3)).decodeToString())
                mergeObservation(current, record, revisionCount, supersededCount)
                val values = ContentValues().apply {
                    put("updated_at", updatedAt)
                    put("lifecycle_key", lifecycleKey)
                    put("item_key", itemKey)
                    put("content_hash", contentHash)
                    put("revision_count", revisionCount)
                    put("payload", crypto.encrypt(current.toString().encodeToByteArray()))
                }
                db.update("captures", values, "id = ?", arrayOf(existingId))
                prune()
                return CaptureWriteResult(existingId, false)
            }
        }
        record.put("id", id)
            .put("firstSeenAtEpochMs", record.optLong("firstSeenAtEpochMs", createdAt))
            .put("lastSeenAtEpochMs", record.optLong("lastSeenAtEpochMs", createdAt))
            .put("revisionCount", 1)
            .put("supersededCount", record.optInt("supersededCount", 0))
        val values = ContentValues().apply {
            put("id", id)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
            put("lifecycle_key", lifecycleKey)
            put("item_key", itemKey)
            put("content_hash", contentHash)
            put("revision_count", 1)
            put("superseded_count", record.optInt("supersededCount", 0))
            put("payload", crypto.encrypt(record.toString().encodeToByteArray()))
        }
        val inserted = db.insertWithOnConflict("captures", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        prune()
        check(inserted != -1L) { "Could not persist the notification capture" }
        return CaptureWriteResult(id, true)
    }

    @Synchronized
    fun attachRuntime(id: String, runtime: JSONObject) {
        updateCapture(id) { it.put("runtime", runtime) }
    }

    @Synchronized
    fun attachOutcome(id: String, outcome: JSONObject) {
        updateCapture(id) { it.put("outcome", outcome) }
    }

    @Synchronized
    fun markSuperseded(id: String) {
        val db = writableDatabase
        db.rawQuery("SELECT superseded_count, payload FROM captures WHERE id = ?", arrayOf(id)).use { cursor ->
            if (!cursor.moveToFirst()) return
            val count = cursor.getInt(0) + 1
            val record = JSONObject(crypto.decrypt(cursor.getBlob(1)).decodeToString())
                .put("supersededCount", count)
            val values = ContentValues().apply {
                put("superseded_count", count)
                put("payload", crypto.encrypt(record.toString().encodeToByteArray()))
            }
            db.update("captures", values, "id = ?", arrayOf(id))
        }
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
        val selection = if (before == null) null else "updated_at < ?"
        val args = before?.let { arrayOf(it.toString()) }
        val output = mutableListOf<JSONObject>()
        var lastReturnedAt: Long? = null
        var hasMore = false
        readableDatabase.query(
            "captures", arrayOf("updated_at", "payload"), selection, args,
            null, null, "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val record = JSONObject(crypto.decrypt(cursor.getBlob(1)).decodeToString())
                val outcome = record.optJSONObject("outcome")
                val decision = outcome?.optString("decision").takeUnless { it.isNullOrBlank() } ?: "PENDING"
                if (!filter.isNullOrBlank() && filter != "ALL" && decision != filter) continue
                if (output.size == requested) {
                    hasMore = true
                    break
                }
                val createdAt = cursor.getLong(0)
                output += JSONObject()
                    .put("id", record.getString("id"))
                    .put("capturedAt", createdAt)
                    .put("appLabel", record.optString("appLabel", record.optString("packageName")))
                    .put("title", record.optString("title"))
                    .put("decision", decision)
                    .put("reasonCode", outcome?.optString("reasonCode") ?: "INTERRUPTED")
                    .put("firstSeenAt", record.optLong("firstSeenAtEpochMs", createdAt))
                    .put("lastSeenAt", record.optLong("lastSeenAtEpochMs", createdAt))
                    .put("revisionCount", record.optInt("revisionCount", 1))
                    .put("supersededCount", record.optInt("supersededCount"))
                    .put("extractionKind", record.optString("extractionKind", "legacy"))
                lastReturnedAt = createdAt
            }
        }
        return output to lastReturnedAt.takeIf { hasMore }
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
        readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*),
                COALESCE(SUM(LENGTH(payload)), 0),
                MIN(created_at),
                COUNT(DISTINCT lifecycle_key) + SUM(CASE WHEN lifecycle_key IS NULL THEN 1 ELSE 0 END),
                COALESCE(SUM(revision_count), 0)
            FROM captures
            """.trimIndent(),
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            return DeveloperDataSummary(
                cursor.getInt(0),
                cursor.getLong(1),
                cursor.getLong(2).takeIf { !cursor.isNull(2) },
                cursor.getInt(3),
                cursor.getLong(4),
            )
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

    /**
     * Encrypted capture archive for the supported `.nbcapture` export.
     *
     * The consistent snapshot is taken under the store lock; the per-record Keystore
     * decryptions, the gzip pass and the key derivation all run outside it, so an export
     * can no longer stall live notification capture. The manifest stays
     * `needlebub.capture.manifest` formatVersion 2.
     *
     * @throws CaptureSnapshotUnavailable when the store exceeds [maxSnapshotBytes]; the
     * export fails loudly rather than omitting records.
     */
    fun encryptedExport(passphrase: CharArray): Pair<ByteArray, Int> {
        val snapshot = exportSnapshot()
        val compressed = compressSnapshot(snapshot)
        return CaptureEnvelope.encrypt(compressed, passphrase) to snapshot.recordCount
    }

    private fun compressSnapshot(snapshot: CaptureExportSnapshot): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine(JSONObject()
                .put("type", "needlebub.capture.manifest")
                .put("formatVersion", 2)
                .put("exportedAtEpochMs", System.currentTimeMillis())
                .put("recordCount", snapshot.recordCount)
                .put("lifecycleCount", snapshot.lifecycleCount)
                .put("observedRevisionCount", snapshot.observedRevisionCount)
                .toString())
            for (payload in snapshot.records) {
                writer.appendLine(crypto.decrypt(payload).decodeToString())
            }
        }
        return compressed.toByteArray()
    }

    /**
     * Copies every encrypted payload at one instant, so a concurrent upsert can no longer
     * change what a started export contains. The store lock is held for this pass only,
     * never across a caller's blocking output write or a Keystore decryption.
     *
     * @throws CaptureSnapshotUnavailable when the copies would exceed [maxSnapshotBytes];
     * failing safely is preferred over risking a kill that would also stop normal OTP
     * delivery, and the export never silently drops records.
     */
    @Synchronized
    override fun exportSnapshot(): CaptureExportSnapshot {
        val summary = summary()
        val payloads = ArrayList<ByteArray>(summary.count)
        var copiedBytes = 0L
        readableDatabase.rawQuery("SELECT payload FROM captures ORDER BY created_at, id", null).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = cursor.getBlob(0)
                copiedBytes += payload.size
                if (copiedBytes > maxSnapshotBytes) {
                    throw CaptureSnapshotUnavailable(
                        CaptureStream.REASON_SNAPSHOT_TOO_LARGE,
                        "Capture export needs more than ${maxSnapshotBytes / (1024 * 1024)} MiB of " +
                            "snapshot memory; nothing was exported. Delete old captures and retry.",
                    )
                }
                payloads += payload
            }
        }
        return CaptureExportSnapshot(
            records = payloads,
            recordCount = summary.count,
            lifecycleCount = summary.lifecycleCount,
            observedRevisionCount = summary.observedRevisionCount,
        )
    }

    /**
     * Decrypts one snapshot payload. Deliberately not synchronized: this runs on the
     * streaming thread so a slow Keystore call cannot stall live notification capture.
     */
    override fun decryptRecord(payload: ByteArray): String = crypto.decrypt(payload).decodeToString()

    /**
     * Streams a consistent snapshot as plaintext JSONL without changing the format.
     *
     * Never throws for a failing consumer, an I/O error or an expired/revoked ADB grant:
     * the result reports how much of the manifest record count was handed to the sink. An
     * aborted stream is normally a truncated prefix of that count; when only the final
     * flush or close fails, every record was written and the result is still
     * [CaptureStream.Result.Incomplete].
     */
    fun writePlaintextJsonl(writer: Writer, canContinue: () -> Boolean): CaptureStream.Result =
        CaptureStream.stream(writer, this, canContinue)

    @Synchronized
    fun clearCaptures(): Int = writableDatabase.delete("captures", null, null)

    @Synchronized
    fun clearDiagnostics(): Int = writableDatabase.delete("diagnostics", null, null)

    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cutoff = now - CaptureRetentionPolicy.MAX_AGE_MS
        db.delete("captures", "updated_at < ?", arrayOf(cutoff.toString()))
        db.delete("diagnostics", "created_at < ?", arrayOf(cutoff.toString()))
        pruneOldest(db, "captures", "updated_at")
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

    private fun nextUpdatedAt(db: SQLiteDatabase): Long {
        val previous = db.rawQuery("SELECT COALESCE(MAX(updated_at), 0) FROM captures", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        return maxOf(System.currentTimeMillis(), previous + 1)
    }

    private fun mergeObservation(
        current: JSONObject,
        incoming: JSONObject,
        revisionCount: Int,
        supersededCount: Int,
    ) {
        val observedAt = incoming.optLong(
            "lastSeenAtEpochMs",
            incoming.optLong("capturedAtEpochMs", System.currentTimeMillis()),
        )
        listOf(
            "category",
            "templateClass",
            "title",
            "body",
            "policyDecision",
            "automaticOtpEnabled",
            "extractionKind",
            "lifecycleKey",
            "itemKey",
        ).forEach { key ->
            if (incoming.has(key)) current.put(key, incoming.opt(key))
        }
        current.put("lastSeenAtEpochMs", observedAt)
            .put("revisionCount", revisionCount)
            .put("supersededCount", supersededCount)
    }

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
