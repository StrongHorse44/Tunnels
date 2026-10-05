package io.github.stronghorse44.tunnels.export

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes the old TSNAPE1 format, for tests only: production code no longer seals anything this way (export
 * container spec, section 11), but an export made by an earlier Tunnels must still import. Written from the
 * documented layout rather than copied from the reader, so a mistake in either shows up.
 */
object LegacySeal {
    fun seal(plain: ByteArray, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.isNotEmpty())
        val magic = "TSNAPE1".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 310_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic + salt)
        return magic + salt + iv + cipher.doFinal(plain)
    }
}
