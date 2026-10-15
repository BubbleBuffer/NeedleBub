package de.x0bubbuff.needlebub.notifications

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLifecycleAnalyzerTest {
    @Test
    fun `messaging style processes only the newest unseen message`() {
        val analyzer = NotificationLifecycleAnalyzer()
        val first = analyzer.analyze(snapshot(
            messages = listOf(
                message("Older message", "Alice", 10),
                message("Newest code 739241", "Bob", 20),
            ),
        ))

        assertEquals(NotificationObservationAction.CREATE, first.action)
        assertEquals(NotificationExtractionKind.MESSAGING, first.candidate.extractionKind)
        assertEquals("Newest code 739241", first.candidate.body)
        assertEquals("Bob", first.candidate.sender)

        val repeated = analyzer.analyze(snapshot(
            postedAt = 30,
            messages = listOf(
                message("Older message", "Alice", 10),
                message("Newest code 739241", "Bob", 20),
            ),
        ))
        assertEquals(NotificationObservationAction.TOUCH, repeated.action)
        assertEquals(first.candidate.itemKey, repeated.candidate.itemKey)

        val newer = analyzer.analyze(snapshot(
            postedAt = 40,
            messages = listOf(
                message("Older message", "Alice", 10),
                message("Newest code 739241", "Bob", 20),
                message("Actually use 852963", "Alice", 40),
            ),
        ))
        assertEquals(NotificationObservationAction.CREATE, newer.action)
        assertEquals("Actually use 852963", newer.candidate.body)
        assertNotEquals(first.candidate.itemKey, newer.candidate.itemKey)
    }

    @Test
    fun `messaging fingerprint cap does not replay older aggregate messages`() {
        val analyzer = NotificationLifecycleAnalyzer(maxMessageFingerprints = 2)
        val aggregate = listOf(
            message("Oldest", "Alice", 10),
            message("Middle", "Bob", 20),
            message("Newest", "Cara", 30),
        )

        val first = analyzer.analyze(snapshot(messages = aggregate))
        val repeated = analyzer.analyze(snapshot(postedAt = 40, messages = aggregate))

        assertEquals("Newest", first.candidate.body)
        assertEquals(NotificationObservationAction.TOUCH, repeated.action)
        assertEquals(first.candidate.itemKey, repeated.candidate.itemKey)
    }

    @Test
    fun `inbox style sends only newly added lines`() {
        val analyzer = NotificationLifecycleAnalyzer()
        val first = analyzer.analyze(snapshot(
            textLines = listOf("First", "Second"),
            template = "android.app.Notification\$InboxStyle",
        ))
        assertEquals(NotificationExtractionKind.INBOX, first.candidate.extractionKind)
        assertEquals("First\nSecond", first.candidate.body)

        val second = analyzer.analyze(snapshot(
            postedAt = 20,
            textLines = listOf("First", "Second", "Third"),
            template = "android.app.Notification\$InboxStyle",
        ))
        assertEquals(NotificationObservationAction.CREATE, second.action)
        assertEquals("Third", second.candidate.body)

        val repeated = analyzer.analyze(snapshot(
            postedAt = 30,
            textLines = listOf("First", "Second", "Third"),
            template = "android.app.Notification\$InboxStyle",
        ))
        assertEquals(NotificationObservationAction.TOUCH, repeated.action)
        assertEquals(second.candidate.itemKey, repeated.candidate.itemKey)
    }

    @Test
    fun `inbox fingerprint cap does not replay older aggregate lines`() {
        val analyzer = NotificationLifecycleAnalyzer(maxMessageFingerprints = 2)
        val aggregate = listOf("Oldest", "Middle", "Newest")

        val first = analyzer.analyze(snapshot(
            textLines = aggregate,
            template = "android.app.Notification\$InboxStyle",
        ))
        val repeated = analyzer.analyze(snapshot(
            postedAt = 20,
            textLines = aggregate,
            template = "android.app.Notification\$InboxStyle",
        ))

        assertEquals("Middle\nNewest", first.candidate.body)
        assertEquals(NotificationObservationAction.TOUCH, repeated.action)
        assertEquals(first.candidate.itemKey, repeated.candidate.itemKey)
    }

    @Test
    fun `ongoing timers collapse but changed otp tokens remain distinct`() {
        val analyzer = NotificationLifecycleAnalyzer()
        val timerOne = analyzer.analyze(snapshot(
            body = "Call duration 00:01",
            flags = Notification.FLAG_ONGOING_EVENT,
        ))
        val timerTwo = analyzer.analyze(snapshot(
            postedAt = 20,
            body = "Call duration 00:02",
            flags = Notification.FLAG_ONGOING_EVENT,
        ))
        assertEquals(NotificationObservationAction.TOUCH, timerTwo.action)
        assertEquals(timerOne.candidate.itemKey, timerTwo.candidate.itemKey)

        val codeOne = analyzer.analyze(snapshot(postedAt = 30, body = "Your code is 1234"))
        val codeTwo = analyzer.analyze(snapshot(postedAt = 40, body = "Your code is 5678"))
        assertEquals(NotificationObservationAction.CREATE, codeOne.action)
        assertEquals(NotificationObservationAction.CREATE, codeTwo.action)
        assertNotEquals(codeOne.candidate.itemKey, codeTwo.candidate.itemKey)

        val splitCodeOne = analyzer.analyze(snapshot(
            postedAt = 50,
            body = "Your code is 123-456",
            flags = Notification.FLAG_ONGOING_EVENT,
        ))
        val splitCodeTwo = analyzer.analyze(snapshot(
            postedAt = 60,
            body = "Your code is 654-321",
            flags = Notification.FLAG_ONGOING_EVENT,
        ))
        assertEquals(NotificationObservationAction.CREATE, splitCodeTwo.action)
        assertNotEquals(splitCodeOne.candidate.itemKey, splitCodeTwo.candidate.itemKey)
    }

    @Test
    fun `exact and volatile ordinary revisions collapse without hiding meaningful text changes`() {
        fun revisionPair(
            previous: String,
            current: String,
            ongoing: Boolean = true,
        ): NotificationAnalysis {
            val analyzer = NotificationLifecycleAnalyzer()
            analyzer.analyze(snapshot(
                body = previous,
                flags = if (ongoing) Notification.FLAG_ONGOING_EVENT else 0,
            ))
            return analyzer.analyze(snapshot(
                postedAt = 20,
                body = current,
                flags = if (ongoing) Notification.FLAG_ONGOING_EVENT else 0,
            ))
        }

        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Protection active", "Protection active").action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Blocked 42%", "Blocked 43%").action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Scanned 3 / 10", "Scanned 4 / 10").action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Blocked 3 requests", "Blocked 4 requests").action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Upload 41%", "Upload 42%", ongoing = false).action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Scanned 3 / 10", "Scanned 4 / 10", ongoing = false).action,
        )
        assertEquals(
            NotificationObservationAction.TOUCH,
            revisionPair("Blocked 3 requests", "Blocked 4 requests", ongoing = false).action,
        )
        assertEquals(
            NotificationObservationAction.CREATE,
            revisionPair("Meeting at 12:30", "Meeting at 12:45", ongoing = false).action,
        )
        assertEquals(
            NotificationObservationAction.CREATE,
            revisionPair("Blocked 3 requests", "Allowed 4 requests").action,
        )
    }

    @Test
    fun `policy skipped lifecycle creates once and then compacts revisions`() {
        val analyzer = NotificationLifecycleAnalyzer()
        val first = analyzer.analyze(snapshot(
            category = Notification.CATEGORY_SERVICE,
            body = "12 blocked",
            flags = Notification.FLAG_ONGOING_EVENT,
        ))
        val revision = analyzer.analyze(snapshot(
            category = Notification.CATEGORY_SERVICE,
            body = "13 blocked",
            flags = Notification.FLAG_ONGOING_EVENT,
            postedAt = 20,
        ))

        assertFalse(first.shouldInfer)
        assertEquals("POLICY_ONGOING_SERVICE", first.reasonCode)
        assertEquals(NotificationExtractionKind.POLICY, first.candidate.extractionKind)
        assertEquals(NotificationObservationAction.TOUCH, revision.action)
        assertEquals(first.candidate.itemKey, revision.candidate.itemKey)
        assertTrue(first.policyTransition)
        assertFalse(revision.policyTransition)

        val transitioned = analyzer.analyze(snapshot(
            category = Notification.CATEGORY_TRANSPORT,
            body = "Playing",
            postedAt = 30,
        ))
        assertEquals(NotificationObservationAction.TOUCH, transitioned.action)
        assertEquals(first.candidate.itemKey, transitioned.candidate.itemKey)
        assertTrue(transitioned.policyTransition)
        assertEquals("POLICY_MEDIA", transitioned.reasonCode)
    }

    @Test
    fun `removed or expired lifecycles forget their seen messages`() {
        var now = 0L
        val analyzer = NotificationLifecycleAnalyzer(
            ttlMs = 100,
            maxLifecycles = 2,
            clockMs = { now },
        )
        val message = listOf(message("Code 739241", "Bank", 1))
        val first = analyzer.analyze(snapshot(messages = message))
        analyzer.remove("lifecycle")
        val afterRemoval = analyzer.analyze(snapshot(messages = message, postedAt = 2))
        assertEquals(NotificationObservationAction.CREATE, afterRemoval.action)
        assertEquals(first.candidate.itemKey, afterRemoval.candidate.itemKey)

        now = 200
        val afterExpiry = analyzer.analyze(snapshot(messages = message, postedAt = 3))
        assertTrue(afterExpiry.shouldInfer)
        assertEquals(NotificationObservationAction.CREATE, afterExpiry.action)
    }

    @Test
    fun `lifecycle capacity evicts the least recently observed key`() {
        val analyzer = NotificationLifecycleAnalyzer(maxLifecycles = 2)
        val message = listOf(message("Code 739241", "Bank", 1))

        analyzer.analyze(snapshot(lifecycleKey = "a", messages = message))
        analyzer.analyze(snapshot(lifecycleKey = "b", messages = message))
        analyzer.analyze(snapshot(lifecycleKey = "c", messages = message))
        val evicted = analyzer.analyze(snapshot(
            lifecycleKey = "a",
            postedAt = 20,
            messages = message,
        ))

        assertEquals(NotificationObservationAction.CREATE, evicted.action)
        assertTrue(evicted.shouldInfer)
    }

    private fun snapshot(
        lifecycleKey: String = "lifecycle",
        body: String = "Ordinary notification",
        textLines: List<String> = emptyList(),
        messages: List<NotificationMessageSnapshot> = emptyList(),
        category: String? = Notification.CATEGORY_MESSAGE,
        template: String? = null,
        flags: Int = 0,
        postedAt: Long = 10,
    ) = NotificationSnapshot(
        lifecycleKey = lifecycleKey,
        title = "Conversation",
        appLabel = "Example",
        body = body,
        textLines = textLines,
        messages = messages,
        category = category,
        hasMediaSession = false,
        template = template,
        flags = flags,
        progressMax = 0,
        progressIndeterminate = false,
        postedAt = postedAt,
    )

    private fun message(text: String, sender: String, timestamp: Long) =
        NotificationMessageSnapshot(text, sender, sender.lowercase(), timestamp)
}
