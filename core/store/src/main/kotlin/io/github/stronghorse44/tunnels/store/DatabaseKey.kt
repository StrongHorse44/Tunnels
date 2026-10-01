package io.github.stronghorse44.tunnels.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The SQLCipher passphrase: 32 random bytes, stored only in wrapped form (AES-256-GCM under an
 * Android Keystore key, StrongBox-backed when the hardware has it). Uninstalling deletes both.
 */
internal object DatabaseKey {
    private const val ALIAS = "tunnels-db-wrap"
    private const val FILE = "db.key.wrapped"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** Returns the passphrase. Calls [onReset] first when a new one had to be made (old DB is unreadable). */
    fun passphrase(context: Context, onReset: () -> Unit): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.exists()) {
            val key = keyStore.getKey(ALIAS, null) as? SecretKey
            if (key != null) {
                runCatching { return unwrap(key, file.readBytes()) }
            }
        }
        onReset()
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        val key = generateKey()
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(wrap(key, passphrase))
        if (!tmp.renameTo(file)) error("Could not store database key")
        return passphrase
    }

    /** "StrongBox", "TEE", or "software", for display. */
    fun securityLevel(): String = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            else -> "software"
        }
    }.getOrDefault("unknown")

    private fun generateKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        } catch (_: ProviderException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun wrap(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun unwrap(key: SecretKey, data: ByteArray): ByteArray {
        val ivLen = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }
}
