package de.x0bubbuff.needlebub.notifications

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.x0bubbuff.needlebub.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User-facing copy boundaries on a real device: the setting survives a new settings
 * instance, an unlocked manual copy needs no biometric, a stale or expired delivery token
 * copies nothing, and newer clipboard content is never erased.
 *
 * Synthetic codes only; no notification body and no private data is used.
 */
@RunWith(AndroidJUnit4::class)
class AutoCopyInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var clipboard: ClipboardManager

    @Test
    fun aMissedCodeDoesNotBecomeEligibleAgainWhenTimePasses() {
        var now = 1_000_000L
        val memory = OtpDeliveryMemory(context, clock = { now })
        val pair = memory.record("ordinary-lifecycle", "123456")
        memory.recordDelivered(pair)
        now += 3_600_000L
        assertTrue("unlocking after the notification expires must not replay it", memory.recentDelivered().contains(pair))
        now = 500_000L
        assertTrue("a wall clock change must not replay it", memory.recentDelivered().contains(pair))
    }

    @Test
    fun aClipboardFailureIsContainedAndLeavesTheResultNotification() {
        OtpResultNotification.show(context, "123456", "Synthetic")
        val unavailable = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.CLIPBOARD_SERVICE) throw SecurityException("synthetic unavailable clipboard")
                return super.getSystemService(name)
            }
        }
        assertFalse(ClipboardHelper.copySensitive(unavailable, "123456"))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == OtpResultNotification.NOTIFICATION_ID })
    }

    @Before
    fun setUp() {
        clipboard = context.getSystemService(ClipboardManager::class.java)
        preferences().edit().clear().commit()
        clipboard.clearPrimaryClip()
    }

    @After
    fun tearDown() {
        clipboard.clearPrimaryClip()
        context.getSystemService(NotificationManager::class.java).cancel(OtpResultNotification.NOTIFICATION_ID)
    }

    private fun preferences() = context.getSharedPreferences("automation", Context.MODE_PRIVATE)

    private fun clipboardText(): String? = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

    /**
     * Clipboard reads are refused unless the app owns window focus, so the observations run
     * with the app's own activity resumed in the foreground. The fixture stays open across
     * the transient copy activity, which finishes by itself.
     */
    private fun <T> withForegroundApp(block: () -> T): T =
        ActivityScenario.launch(MainActivity::class.java).use { block() }

    /** Waits for a self-finishing activity; it never reaches a state we can poke. */
    private fun awaitDestroyed(scenario: ActivityScenario<*>): androidx.lifecycle.Lifecycle.State {
        val deadline = System.currentTimeMillis() + DESTROY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            Thread.sleep(20)
        }
        return scenario.state
    }

    /** Polls the clipboard while the platform hands window focus back to the app. */
    private fun awaitClipboard(expected: String): String? {
        val deadline = System.currentTimeMillis() + CLIPBOARD_TIMEOUT_MS
        var text = clipboardText()
        while (text != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            text = clipboardText()
        }
        return text
    }

    // Launching the copy scenario clears the earlier test task. Reopen the app
    // before observing the clipboard; background clipboard reads are denied.
    private fun observeClipboard(expected: String): String? =
        ActivityScenario.launch(MainActivity::class.java).use { awaitClipboard(expected) }

    @Test
    fun autoCopyIsOffByDefaultAndPersistsThroughTheSettingsStore() {
        assertFalse("auto copy must default to off", AutomationSettings(context).autoCopyEnabled)

        AutomationSettings(context).autoCopyEnabled = true
        assertTrue("the setting must survive a new instance", AutomationSettings(context).autoCopyEnabled)

        AutomationSettings(context).autoCopyEnabled = false
        assertFalse(AutomationSettings(context).autoCopyEnabled)
    }

    @Test
    fun theAutomationToggleIsIndependentOfAutoCopy() {
        val settings = AutomationSettings(context)
        settings.enabled = true
        settings.autoCopyEnabled = true
        settings.enabled = false

        val reread = AutomationSettings(context)
        assertFalse("pausing automation must not clear the copy preference", reread.enabled)
        assertTrue("the copy preference stays as the user left it", reread.autoCopyEnabled)
    }

    @Test
    fun aSensitiveCopyIsMarkedAndNewerClipboardContentIsNeverErased() = withForegroundApp {
        assertTrue(ClipboardHelper.copySensitive(context, "123456", "NeedleBub code"))
        assertEquals("123456", awaitClipboard("123456"))
        val extras = clipboard.primaryClip?.description?.extras
        assertNotNull("the clip must carry the sensitivity flag", extras)
        assertTrue(extras!!.getBoolean(ClipboardHelper.SENSITIVE_EXTRA))

        // The user copies something else afterwards: nothing in the app clears it, even
        // when the text happens to be identical to a code we copied earlier.
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("other", "user-copy"))
        Thread.sleep(150)
        assertEquals("user-copy", clipboardText())
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("other", "123456"))
        Thread.sleep(150)
        assertEquals("123456", clipboardText())
    }

    @Test
    fun theUnlockGateIsOpenOnAnUnlockedDevice() {
        assertTrue("the emulator must be unlocked for this suite", ClipboardHelper.isUnlocked(context))
    }

    @Test
    fun aManualCopyWhileUnlockedCopiesWithoutAnyBiometricPrompt() = withForegroundApp {
        val token = OtpResultNotification.show(context, "246813", "Synthetic sender")

        // No biometric prompt: the activity copies and destroys itself without any user
        // interaction, which is what the timeout below would otherwise expose.
        ActivityScenario.launch<AuthenticatedCopyActivity>(
            Intent(context, AuthenticatedCopyActivity::class.java)
                .putExtra(OtpResultNotification.EXTRA_CODE, "246813")
                .putExtra(OtpResultNotification.EXTRA_DELIVERY_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            assertEquals(
                "a manual copy must finish on its own",
                androidx.lifecycle.Lifecycle.State.DESTROYED,
                awaitDestroyed(scenario),
            )
        }

        assertEquals(
            "an unlocked manual copy must reach the clipboard",
            "246813",
            observeClipboard("246813"),
        )
    }

    @Test
    fun aStaleDeliveryTokenCopiesNothing() = withForegroundApp {
        val staleToken = OtpResultNotification.show(context, "111111", "Synthetic sender")
        OtpResultNotification.show(context, "222222", "Synthetic sender")
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("other", "user-copy"))

        ActivityScenario.launch<AuthenticatedCopyActivity>(
            Intent(context, AuthenticatedCopyActivity::class.java)
                .putExtra(OtpResultNotification.EXTRA_CODE, "111111")
                .putExtra(OtpResultNotification.EXTRA_DELIVERY_TOKEN, staleToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            assertEquals(
                "a stale action must finish without copying",
                androidx.lifecycle.Lifecycle.State.DESTROYED,
                awaitDestroyed(scenario),
            )
        }

        // The activity is already destroyed, so any copy it would have made has happened.
        assertEquals("a stale action must not copy", "user-copy", observeClipboard("user-copy"))
    }

    @Test
    fun anExpiredDeliveryTokenIsRefusedAndASurvivingNotificationStaysCopyable() {
        val token = OtpResultNotification.show(context, "333333", "Synthetic sender")
        val now = System.currentTimeMillis()

        assertTrue(OtpResultNotification.isCurrentDelivery(context, token, now))
        assertFalse(
            "an expired token must be refused",
            OtpResultNotification.isCurrentDelivery(context, token, now + OtpResultNotification.DELIVERY_TTL_MS + 1),
        )
        assertFalse(OtpResultNotification.isCurrentDelivery(context, null, now))
        assertFalse(OtpResultNotification.isCurrentDelivery(context, "", now))
        assertFalse(OtpResultNotification.isCurrentDelivery(context, "not-a-token", now))

        // Process recreation: the token lives in persisted state, so a fresh read of the
        // stored value still validates even though no in-memory id survived.
        val persisted = preferences().getString("current_delivery", null)
        assertNotNull(persisted)
        val persistedToken = persisted!!.substringBeforeLast(':')
        assertTrue(
            "a surviving notification must stay copyable after process recreation",
            OtpResultNotification.isCurrentDelivery(context, persistedToken, now),
        )
    }

    @Test
    fun deliveryMemoryDedupesInterleavedRepeatsAndPrunesByWindowAndSize() {
        val memory = OtpDeliveryMemory(context)
        val itemA = memory.record("synthetic-item-a", "111111")
        val itemB = memory.record("synthetic-item-b", "222222")

        memory.recordCopied(itemA)
        memory.recordCopied(itemB)
        memory.recordCopied(itemA)
        assertEquals(listOf(itemA, itemB, itemA), memory.recentCopied())

        // The policy is what refuses the repeat, not the store.
        assertEquals(
            OtpCopyDecision.SKIP_DUPLICATE,
            OtpCopyPolicy.decide(
                accepted = true,
                fresh = true,
                automationEnabled = true,
                autoCopyEnabled = true,
                unlocked = true,
                candidate = itemA,
                alreadyHandled = memory.recentCopied(),
            ),
        )

        // A pair delivered while locked is handled too, so a later revision cannot copy it.
        assertEquals(
            OtpCopyDecision.SKIP_DUPLICATE,
            OtpCopyPolicy.decide(
                accepted = true,
                fresh = true,
                automationEnabled = true,
                autoCopyEnabled = true,
                unlocked = true,
                candidate = itemA,
                alreadyHandled = memory.recentDelivered() + memory.recentCopied(),
            ),
        )

        var now = 1_000_000L
        val windowed = OtpDeliveryMemory(context, windowMs = 120_000L, clock = { now })
        windowed.recordCopied(itemA)
        assertEquals(1, windowed.recentCopied().size)
        now += 120_001L
        assertTrue("an aged pair leaves the window", windowed.recentCopied().isEmpty())

        val capped = OtpDeliveryMemory(context, maxEntries = 2, clock = { now })
        val first = capped.record("synthetic-item-1", "111111")
        val second = capped.record("synthetic-item-2", "222222")
        val third = capped.record("synthetic-item-3", "333333")
        capped.recordCopied(first)
        capped.recordCopied(second)
        capped.recordCopied(third)
        assertEquals(listOf(second, third), capped.recentCopied())
    }

    @Test
    fun theStoredDeliveryIndexHoldsHashesOnly() {
        val memory = OtpDeliveryMemory(context)
        val record = memory.record("synthetic-item", "998877")
        memory.recordCopied(record)

        val stored = preferences().getString("recent_copied_pairs", "").orEmpty()
        assertFalse("the code must never be stored in the clear", stored.contains("998877"))
        assertFalse("the item key must never be stored in the clear", stored.contains("synthetic-item"))
        assertTrue(stored.contains(record.codeHash))
    }

    private companion object {
        const val DESTROY_TIMEOUT_MS = 5_000L
        const val CLIPBOARD_TIMEOUT_MS = 3_000L
    }
}
