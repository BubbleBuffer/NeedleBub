package de.x0bubbuff.needlebub.updates

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object CatalogueSignature {
    fun verify(catalogue: ByteArray, signatureBase64: String, publicKeyBase64: String): ByteArray {
        if (catalogue.isEmpty() || catalogue.size > MAX_CATALOGUE_BYTES) throw SecurityException("Catalogue size is invalid")
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64.trim())
        } catch (error: IllegalArgumentException) {
            throw SecurityException("Catalogue signature is malformed", error)
        }
        val publicKeyBytes = try {
            Base64.getDecoder().decode(publicKeyBase64.trim())
        } catch (error: IllegalArgumentException) {
            throw SecurityException("Catalogue public key is malformed", error)
        }
        val publicKey = try {
            EdDSAPublicKey(X509EncodedKeySpec(publicKeyBytes))
        } catch (error: Exception) {
            throw SecurityException("Catalogue public key is invalid", error)
        }
        val valid = EdDSAEngine().run {
            initVerify(publicKey)
            update(catalogue)
            verify(signatureBytes)
        }
        if (!valid) throw SecurityException("Catalogue signature is invalid")
        return catalogue
    }

    const val MAX_CATALOGUE_BYTES = 256 * 1024
}
