package io.github.stronghorse44.tunnels.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import io.github.stronghorse44.tunnels.engine.StoreKey
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * The store could not be opened for a reason that may pass (a Keystore error, a damaged wrapped key while the Keystore key
 * is still there). Nothing was deleted; callers fail closed and show [TunnelsStore.UNAVAILABLE_MESSAGE]. [reason] is a class
 * name or a shape, never key material.
 */
class StoreUnavailableException(val reason: String, cause: Throwable? = null) : IllegalStateException(TunnelsStore.UNAVAILABLE_MESSAGE, cause)

/**
 * The SQLCipher passphrase: 32 random bytes, stored only in wrapped form (AES-256-GCM under an
 * Android Keystore key, StrongBox-backed when the hardware has it). Uninstalling deletes both.
 */
internal object DatabaseKey {
    private const val ALIAS = "tunnels-db-wrap"
    private const val FILE = "db.key.wrapped"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG = "TunnelsStore"

    /**
     * Returns the passphrase. Only a definite loss of the old key ([StoreKey.Outcome.Lost]: the wrapped file gone, the
     * Keystore alias gone, or the key permanently invalidated) makes a new one, and [onReset] runs first so the old store
     * can be set aside; it must throw when it cannot. Any other failure throws [StoreUnavailableException] and nothing is
     * deleted (audit M5).
     */
    fun passphrase(context: Context, onReset: () -> Unit): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
        var wrapKey: SecretKey? = null
        val outcome = StoreKey.read(
            fileExists = { fileState(file) },
            // getKey, never containsAlias: see StoreKey.read. Null only for "no such key"; a fault throws.
            aliasPresent = {
                when (val key = keyStore.getKey(ALIAS, null)) {
                    null -> false
                    is SecretKey -> true.also { wrapKey = key }
                    else -> throw IllegalStateException("wrapping key has an unexpected type")
                }
            },
            readFile = { file.readBytes() },
            unwrap = { data -> unwrap(wrapKey ?: throw IllegalStateException("wrapping key unreadable"), data) },
            isInvalidated = { it is KeyPermanentlyInvalidatedException },
        )
        when (outcome) {
            is StoreKey.Outcome.Opened -> return outcome.passphrase
            is StoreKey.Outcome.Unavailable -> throw StoreUnavailableException(outcome.cause)
            is StoreKey.Outcome.Lost -> Log.w(TAG, "store key lost: ${outcome.why}")
        }
        return newKey(context, onReset)
    }

    /**
     * Sets the old store aside ([onReset], which must throw when it cannot) and makes a new key. Runs on a definite loss,
     * or when the user asks for it on the fail-closed message after the failure survived a restart. Never deletes the
     * old store's files.
     */
    fun newKey(context: Context, onReset: () -> Unit): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        return try {
            StoreKey.reset(
                // Loaded before anything on disk changes: if the Keystore cannot be reached, nothing is set aside.
                loadKeystore = { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } },
                setAside = onReset,
                deleteWrapped = { if (fileState(file) != false && !file.delete()) throw IOException("Could not remove the old wrapped key") },
                // generateKey replaces an existing entry anyway.
                deleteEntry = { keyStore -> keyStore.deleteEntry(ALIAS) },
                create = {
                    val key = generateKey()
                    val passphrase = ByteArray(StoreKey.PASSPHRASE_LENGTH).also { SecureRandom().nextBytes(it) }
                    val tmp = File(file.parentFile, "$FILE.tmp")
                    tmp.writeBytes(wrap(key, passphrase))
                    if (!tmp.renameTo(file)) throw IOException("Could not store database key")
                    passphrase
                },
            )
        } catch (e: StoreUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailableException(e.javaClass.simpleName, e)
        }
    }

    /** True or false when the file system is sure, null when it cannot tell (an IO error reads as neither). */
    private fun fileState(file: File): Boolean? {
        val path = file.toPath()
        return when {
            Files.exists(path) -> true
            Files.notExists(path) -> false
            else -> null
        }
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
