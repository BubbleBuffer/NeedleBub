package de.x0bubbuff.needlebub.developer

import android.app.Notification
import android.app.Person
import android.content.ContentValues
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.x0bubbuff.needlebub.gateway.ErrorCodes
import de.x0bubbuff.needlebub.packs.InstalledPack
import de.x0bubbuff.needlebub.packs.PackManifest
import de.x0bubbuff.needlebub.runtime.AndroidRuntimeHost
import de.x0bubbuff.needlebub.runtime.RuntimeBroker
import de.x0bubbuff.needlebub.runtime.RuntimeAdmission
import de.x0bubbuff.needlebub.notifications.AndroidNotificationSnapshotFactory
import de.x0bubbuff.needlebub.notifications.NotificationExtractionKind
import de.x0bubbuff.needlebub.notifications.NotificationLifecycleAnalyzer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DeveloperDataStoreInstrumentedTest {
    private lateinit var store: DeveloperDataStore

    @Before
    fun setUp() {
        store = DeveloperDataStore(ApplicationProvider.getApplicationContext())
        store.clearCaptures()
    }

    @After
    fun tearDown() {
        store.clearCaptures()
        store.close()
    }

    @Test
    fun insertsAndUpdatesAnEncryptedCaptureWithAndroidKeystore() {
        val id = store.insertCapture(JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", "com.example.sender")
            .put("notificationKeyHash", "fixture-key")
            .put("title", "Security code")
            .put("body", "Your code is 123456"))

        assertNotNull(id)
        store.attachRuntime(requireNotNull(id), JSONObject()
            .put("status", "OK")
            .put("matched", false)
            .put("durationMs", 42))
        assertEquals(1, store.summary().count)
    }

    @Test
    fun compactsEquivalentObservationsWithoutReplacingTheExistingOutcome() {
        val now = System.currentTimeMillis()
        val first = store.upsertCapture(JSONObject()
            .put("schema", "needlebub.capture.record.v3")
            .put("capturedAtEpochMs", now)
            .put("lifecycleKey", "lifecycle")
            .put("itemKey", "logical-item")
            .put("packageName", "com.example.sender")
            .put("title", "Protection")
            .put("body", "12 blocked")
            .put("extractionKind", "policy"))
        store.attachOutcome(first.id, JSONObject()
            .put("decision", "SKIPPED")
            .put("reasonCode", "POLICY_ONGOING_SERVICE"))

        val revision = store.upsertCapture(JSONObject()
            .put("capturedAtEpochMs", now + 100)
            .put("lifecycleKey", "lifecycle")
            .put("itemKey", "logical-item")
            .put("packageName", "com.example.sender")
            .put("title", "Protection")
            .put("body", "13 blocked")
            .put("extractionKind", "policy"))
        val record = requireNotNull(store.capture(first.id))
        val summary = store.summary()

        assertTrue(first.isNew)
        assertFalse(revision.isNew)
        assertEquals(first.id, revision.id)
        assertEquals(1, summary.count)
        assertEquals(1, summary.lifecycleCount)
        assertEquals(2L, summary.observedRevisionCount)
        assertEquals(2, record.getInt("revisionCount"))
        assertEquals("13 blocked", record.getString("body"))
        assertEquals("SKIPPED", record.getJSONObject("outcome").getString("decision"))
    }

    @Test
    fun filteredPaginationFindsLaterMatchesAndNeverRepeatsEqualTimestampRows() {
        val timestamp = System.currentTimeMillis()
        repeat(7) { index ->
            val capture = store.upsertCapture(JSONObject()
                .put("capturedAtEpochMs", timestamp)
                .put("lifecycleKey", "lifecycle-$index")
                .put("itemKey", "item-$index")
                .put("packageName", "com.example.sender")
                .put("title", "Record $index")
                .put("body", "Body $index"))
            store.attachOutcome(capture.id, JSONObject()
                .put("decision", if (index % 2 == 0) "REJECTED" else "OTP")
                .put("reasonCode", if (index % 2 == 0) "MODEL_NO_MATCH" else "OTP_ACCEPTED"))
        }

        val first = store.captureSummaries(2, null, "REJECTED")
        val second = store.captureSummaries(2, first.second, "REJECTED")

        assertEquals(2, first.first.size)
        assertNotNull(first.second)
        assertEquals(2, second.first.size)
        assertEquals(null, second.second)
        assertEquals(4, (first.first + second.first).map { it.getString("id") }.distinct().size)
    }

    @Test
    fun upgradesTheVersionOneSchemaWithoutDeletingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        requireNotNull(store.insertCapture(JSONObject()
            .put("schema", "needlebub.capture.record.v2")
            .put("id", "legacy")
            .put("capturedAtEpochMs", now)
            .put("packageName", "com.example.legacy")
            .put("notificationKeyHash", "legacy-key")
            .put("title", "Legacy title")
            .put("body", "Legacy body")))
        val legacyPayload = context.openOrCreateDatabase(DATABASE_NAME, 0, null).use { database ->
            database.rawQuery("SELECT payload FROM captures WHERE id = ?", arrayOf("legacy")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getBlob(0)
            }
        }
        store.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, 0, null).use { database ->
            database.execSQL("CREATE TABLE captures (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, content_hash TEXT NOT NULL UNIQUE, payload BLOB NOT NULL)")
            database.execSQL("CREATE INDEX captures_created ON captures(created_at)")
            database.execSQL("CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, package_name TEXT, category TEXT, stage TEXT NOT NULL, pack TEXT, status TEXT NOT NULL, error_code TEXT, duration_ms INTEGER, pss_kb INTEGER, cold_load INTEGER)")
            database.execSQL("CREATE INDEX diagnostics_created ON diagnostics(created_at)")
            database.insert("captures", null, ContentValues().apply {
                put("id", "legacy")
                put("created_at", now)
                put("content_hash", "legacy-hash")
                put("payload", legacyPayload)
            })
            database.version = 1
        }

        store = DeveloperDataStore(context)
        val summary = store.summary()
        val legacy = requireNotNull(store.capture("legacy"))
        val export = StringWriter().also { writer ->
            store.writePlaintextJsonl(writer) { true }
        }.toString().lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()

        assertEquals(1, summary.count)
        assertEquals(1, summary.lifecycleCount)
        assertEquals(1L, summary.observedRevisionCount)
        assertEquals("Legacy body", legacy.getString("body"))
        assertEquals(3, export.first().getInt("formatVersion"))
        assertEquals(1, export.first().getInt("recordCount"))
        assertEquals("legacy", export.last().getString("id"))
    }

    @Test
    fun extractsOnlyCurrentMessagingStyleMessagesFromAndroidBundles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val user = Person.Builder().setName("Me").setKey("me").build()
        val sender = Person.Builder().setName("Needle Bank").setKey("bank").build()
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Security")
            .setStyle(Notification.MessagingStyle(user)
                .addHistoricMessage(Notification.MessagingStyle.Message("Historic", 1, sender))
                .addMessage(Notification.MessagingStyle.Message("Your code is 739241", 2, sender)))
            .build()
        val sbn = StatusBarNotification(
            "com.example.sender",
            "com.example.sender",
            1,
            "tag",
            Process.myUid(),
            0,
            0,
            notification,
            Process.myUserHandle(),
            2,
        )

        val snapshot = AndroidNotificationSnapshotFactory.create(
            sbn,
            "lifecycle",
            "Needle Bank",
            512,
            8_192,
        )

        assertEquals(1, snapshot.messages.size)
        assertEquals("Your code is 739241", snapshot.messages.single().text)
        assertEquals("Needle Bank", snapshot.messages.single().sender)
    }

    @Test
    fun classifiesRealAndroidNotificationStylesOnTheEmulator() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val analyzer = NotificationLifecycleAnalyzer()
        val sender = Person.Builder().setName("Needle Bank").setKey("bank").build()
        val user = Person.Builder().setName("Me").setKey("me").build()

        val ordinary = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "ordinary",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Store")
                    .setContentText("Order ready")
                    .build(),
            ),
            "ordinary",
            "Fixture",
            512,
            8_192,
        ))
        assertTrue(ordinary.shouldInfer)
        assertEquals(NotificationExtractionKind.ORDINARY, ordinary.candidate.extractionKind)

        val messaging = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "messaging",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(Notification.MessagingStyle(user)
                        .addMessage(Notification.MessagingStyle.Message("Older", 1, sender))
                        .addMessage(Notification.MessagingStyle.Message("Code 739241", 2, sender)))
                    .build(),
            ),
            "messaging",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals(NotificationExtractionKind.MESSAGING, messaging.candidate.extractionKind)
        assertEquals("Code 739241", messaging.candidate.body)

        analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "inbox",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(Notification.InboxStyle().addLine("First").addLine("Second"))
                    .build(),
            ),
            "inbox",
            "Fixture",
            512,
            8_192,
        ))
        val inboxUpdate = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "inbox",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(Notification.InboxStyle()
                        .addLine("First")
                        .addLine("Second")
                        .addLine("Third"))
                    .build(),
                postTime = 3,
            ),
            "inbox",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals(NotificationExtractionKind.INBOX, inboxUpdate.candidate.extractionKind)
        assertEquals("Third", inboxUpdate.candidate.body)

        val navigation = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "navigation",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setContentText("Turn left")
                    .build(),
            ),
            "navigation",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals("POLICY_NAVIGATION", navigation.reasonCode)

        val progress = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "progress",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentText("Downloading")
                    .setProgress(100, 50, false)
                    .build(),
            ),
            "progress",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals("POLICY_PROGRESS", progress.reasonCode)

        val media = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification(
                "media",
                Notification.Builder(context, "test")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentText("Playing")
                    .setStyle(Notification.MediaStyle())
                    .build(),
            ),
            "media",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals("POLICY_MEDIA", media.reasonCode)

        val foregroundNotification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText("Protection active")
            .setOngoing(true)
            .build()
            .also { it.flags = it.flags or Notification.FLAG_FOREGROUND_SERVICE }
        val foreground = analyzer.analyze(AndroidNotificationSnapshotFactory.create(
            statusBarNotification("foreground", foregroundNotification),
            "foreground",
            "Fixture",
            512,
            8_192,
        ))
        assertEquals("POLICY_ONGOING_SERVICE", foreground.reasonCode)
    }

    @Test
    fun attachesAnAsynchronousRuntimeFailureWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = "malformed-model".encodeToByteArray()
        val directory = File(context.cacheDir, "runtime-callback-pack").apply {
            deleteRecursively()
            mkdirs()
        }
        File(directory, "model.cact").writeBytes(model)
        File(directory, "tools.json").writeText(TOOLS)
        val manifest = PackManifest.parse("""
            {
              "formatVersion":1,
              "id":"de.x0bubbuff.needlebub.test",
              "version":"1.0.0",
              "name":"Runtime callback test",
              "author":"BubbleBuffer",
              "description":"Exercises a failed isolated runtime callback.",
              "license":"MIT",
              "engine":{"abi":"${PackManifest.ENGINE_ABI}"},
              "model":{"path":"model.cact","size":${model.size},"sha256":"${model.sha256()}"},
              "queryTemplate":"{{input}}",
              "surfaces":["external"],
              "outputs":{}
            }
        """.trimIndent())
        val id = requireNotNull(store.insertCapture(JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", "com.example.sender")
            .put("notificationKeyHash", "runtime-fixture")
            .put("title", "Runtime fixture")
            .put("body", "No one-time code here")))
        val completed = CountDownLatch(1)
        val pack = InstalledPack(manifest, directory, true)
        val broker = RuntimeBroker(AndroidRuntimeHost(context))

        val accepted = broker.infer(
            "instrumented-runtime-failure",
            pack,
            "Sender: Fixture\nMessage: No one-time code here",
            5_000,
            surface = "notification",
        ) { response ->
            assertEquals(ErrorCodes.PACK_INVALID, response.errorCode)
            store.attachRuntime(id, JSONObject()
                .put("status", response.status)
                .put("errorCode", response.errorCode)
                .put("durationMs", response.durationMs))
            completed.countDown()
        }

        assertEquals(RuntimeAdmission.ACCEPTED, accepted)
        org.junit.Assert.assertTrue(completed.await(10, TimeUnit.SECONDS))
        assertEquals(1, store.summary().count)
        directory.deleteRecursively()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private fun statusBarNotification(
        tag: String,
        notification: Notification,
        postTime: Long = 2,
    ) = StatusBarNotification(
        "com.example.sender",
        "com.example.sender",
        tag.hashCode(),
        tag,
        Process.myUid(),
        0,
        0,
        notification,
        Process.myUserHandle(),
        postTime,
    )

    private companion object {
        const val DATABASE_NAME = "developer_data.db"
        const val TOOLS = """{"formatVersion":1,"tools":[{"name":"extract_otp","description":"Extract an OTP.","parameters":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}}]}"""
    }
}
