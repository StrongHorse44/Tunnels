package io.github.stronghorse44.tunnels.export

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.Destroyable

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
 *
 * Key material made here (the derived key bytes, the PBEKeySpec's password copy, the SecretKey objects) is zeroed
 * or destroyed after use. The caller owns the password array and zeroes it; a password that passed through a String
 * on its way here (a text field, say) has copies nothing in this object can reach.
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

    /** Seals [plain] under [password]; an empty password is refused with IllegalArgumentException. */
    fun seal(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.isNotEmpty()) { "The password must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        val body = cipher.doFinal(plain)
        return magic + salt + iv + body
    }

    /**
     * Throws [NotASealedFile] when the magic is missing and [WrongPasswordOrCorrupt] when authentication fails.
     * An empty password fails the same way on every provider: nothing was ever sealed with one.
     */
    fun open(sealed: ByteArray, password: CharArray): ByteArray {
        if (!looksSealed(sealed)) throw NotASealedFile()
        if (password.isEmpty()) throw WrongPasswordOrCorrupt()
        var at = magic.size
        val salt = sealed.copyOfRange(at, at + SALT_BYTES).also { at += SALT_BYTES }
        val iv = sealed.copyOfRange(at, at + IV_BYTES).also { at += IV_BYTES }
        val cipher = cipher(Cipher.DECRYPT_MODE, password, salt, iv)
        try {
            return cipher.doFinal(sealed, at, sealed.size - at)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordOrCorrupt(cause = e)
        }
    }

    /** An AES-GCM cipher ready for [mode], with the magic and salt as AAD. Key material is cleared before it returns. */
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        val key = deriveKey(password, salt)
        val aes = SecretKeySpec(key, "AES")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, aes, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(magic + salt)
            return cipher
        } finally {
            destroy(aes)
            key.fill(0)
        }
    }

    /** The caller zeroes the result. The PBEKeySpec's password copy and the factory's SecretKey are cleared here. */
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            try {
                return secret.encoded
            } finally {
                destroy(secret)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** Best effort: many providers' keys inherit [Destroyable] only to throw from destroy(). */
    private fun destroy(key: SecretKey) {
        try {
            key.destroy()
        } catch (_: Exception) {
        }
    }
}
