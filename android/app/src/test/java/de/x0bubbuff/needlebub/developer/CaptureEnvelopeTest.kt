package de.x0bubbuff.needlebub.developer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class CaptureEnvelopeTest {
    @Test
    fun `round trips an authenticated capture payload`() {
        val payload = "private notification\n".encodeToByteArray()
        val encrypted = CaptureEnvelope.encrypt(payload, "a sufficiently long passphrase".toCharArray(), iterations = 10_000)

        assertArrayEquals(payload, CaptureEnvelope.decrypt(encrypted, "a sufficiently long passphrase".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            CaptureEnvelope.decrypt(encrypted, "the wrong passphrase".toCharArray())
        }
    }

    @Test
    fun `rejects malformed envelopes without returning partial plaintext`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureEnvelope.decrypt("not a capture".encodeToByteArray(), "a sufficiently long passphrase".toCharArray())
        }
    }

    @Test
    fun `decrypts an envelope produced by the Python desktop importer`() {
        val envelope = Base64.getDecoder().decode("TkJDQVAwMDEAACcQOeqUqM3uJM1udZD9oJm9nvhAZ0L0O5cpYFJUFVkPq37uAGZV4aANYT5/MTXCrO1AKD0YttwFBEHNSEAEQSiW8GfQ")

        assertArrayEquals(
            "cross-language fixture".encodeToByteArray(),
            CaptureEnvelope.decrypt(envelope, "a sufficiently long passphrase".toCharArray()),
        )
    }
}
