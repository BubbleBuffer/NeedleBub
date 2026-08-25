package de.x0bubbuff.needlebub.developer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CaptureEnvelope {
    private val magic = "NBCAP001".encodeToByteArray()
    private const val DEFAULT_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(payload: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(passphrase.size >= 12) { "Passphrase must contain at least 12 characters" }
        require(iterations >= 10_000) { "PBKDF2 iteration count is too low" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val key = derive(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(magic)
            val ciphertext = cipher.doFinal(payload)
            return ByteBuffer.allocate(magic.size + 4 + salt.size + nonce.size + ciphertext.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(magic)
                .putInt(iterations)
                .put(salt)
                .put(nonce)
                .put(ciphertext)
                .array()
        } finally {
            key.encoded?.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        try {
            require(envelope.size >= magic.size + 4 + SALT_BYTES + NONCE_BYTES + 16) { "Capture envelope is truncated" }
            val buffer = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN)
            val actualMagic = ByteArray(magic.size).also(buffer::get)
            require(actualMagic.contentEquals(magic)) { "Capture envelope has an unsupported format" }
            val iterations = buffer.int
            require(iterations in 10_000..2_000_000) { "Capture envelope has invalid key settings" }
            val salt = ByteArray(SALT_BYTES).also(buffer::get)
            val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val key = derive(passphrase, salt, iterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(magic)
                return cipher.doFinal(ciphertext)
            } finally {
                key.encoded?.fill(0)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Capture password is wrong or the file is damaged")
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("Capture envelope could not be authenticated")
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
