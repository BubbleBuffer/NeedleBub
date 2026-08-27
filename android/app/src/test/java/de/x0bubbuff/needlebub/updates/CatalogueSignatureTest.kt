package de.x0bubbuff.needlebub.updates

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CatalogueSignatureTest {
    @Test
    fun `accepts exact signed bytes and rejects tampering`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val catalogue = """{"formatVersion":1,"entries":[]}""".encodeToByteArray()
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(catalogue)
            sign()
        }

        assertArrayEquals(
            catalogue,
            CatalogueSignature.verify(
                catalogue,
                Base64.getEncoder().encodeToString(signature),
                Base64.getEncoder().encodeToString(pair.public.encoded),
            ),
        )
        assertThrows(SecurityException::class.java) {
            CatalogueSignature.verify(
                catalogue + '\n'.code.toByte(),
                Base64.getEncoder().encodeToString(signature),
                Base64.getEncoder().encodeToString(pair.public.encoded),
            )
        }
    }
}
