package io.github.stronghorse44.tunnels.export

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The file did not decrypt: the password is wrong or the bytes were altered. The two are indistinguishable by design. */
open class WrongPasswordOrCorrupt(message: String = "Wrong password, or the file is damaged.", cause: Throwable? = null) :
    Exception(message, cause)

/** The bytes do not start with the TSNAPE1 magic, so this is not a Tunnels export at all. */
class NotASealedFile : WrongPasswordOrCorrupt("Not a Tunnels snapshot export.")

/**
 * TSNAPE1: password-sealed bytes.
 *
 * ```
 * "TSNAPE1" (7 bytes ASCII) | salt (16 bytes) | IV (12 bytes) | AES-GCM ciphertext + 16-byte tag
 * ```
 *
 * The key is PBKDF2WithHmacSHA256(password, salt, 310 000 iterations) → 256 bits. Magic and salt are bound into the
 * ciphertext as GCM additional authenticated data, so a change to either fails authentication as well.
 */
object EncryptedFile {
    const val MAGIC = "TSNAPE1"
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val ITERATIONS = 310_000
    const val KEY_BITS = 256
    const val TAG_BITS = 128

    private val magic: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)
    private val minLength = magic.size + SALT_BYTES + IV_BYTES + TAG_BITS / 8

    /** True when [bytes] carry the TSNAPE1 magic and are long enough to hold a sealed payload. */
    fun looksSealed(bytes: ByteArray): Boolean =
        bytes.size >= minLength && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun seal(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(magic + salt)
            val body = cipher.doFinal(plain)
            return magic + salt + iv + body
        } finally {
            key.fill(0)
        }
    }

    /** Throws [NotASealedFile] when the magic is missing and [WrongPasswordOrCorrupt] when authentication fails. */
    fun open(sealed: ByteArray, password: CharArray): ByteArray {
        if (!looksSealed(sealed)) throw NotASealedFile()
        var at = magic.size
        val salt = sealed.copyOfRange(at, at + SALT_BYTES).also { at += SALT_BYTES }
        val iv = sealed.copyOfRange(at, at + IV_BYTES).also { at += IV_BYTES }
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(magic + salt)
            return cipher.doFinal(sealed, at, sealed.size - at)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrCorrupt(cause = e)
        } finally {
            key.fill(0)
        }
    }

    /** The caller zeroes the result. The PBEKeySpec's own copy of the password is cleared here. */
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
