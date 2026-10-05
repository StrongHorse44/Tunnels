package io.github.stronghorse44.tunnels.surroundings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The keyed hash behind the phone's places and the cell logbook: HMAC-SHA256 under an Android Keystore key that is
 * created on first use, can only sign, and never leaves the keystore. A token is the first 16 bytes of the result as
 * 32 lowercase hex characters. Off this phone (an export, a copied database) nobody can compute it, so the hashes
 * cannot be turned back into places or cells.
 */
object SurroundingsKey {
    private const val KEY_ALIAS = "tunnels.surroundings.place"
    private const val KEY_ID_INPUT = "kid:v1"

    /** Tokens of [inputs], in order, from one Keystore lookup. */
    fun hmacAll(inputs: List<String>): List<String> {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key()) }
        return inputs.map { hex(mac.doFinal(it.toByteArray(Charsets.UTF_8)), 16) }
    }

    /** The token of one [input]. */
    fun hmac(input: String): String = hmacAll(listOf(input)).single()

    /** First 4 bytes (8 hex) of the key's tag of a fixed text: changes when the key is replaced, tells nothing about the key. */
    fun keyId(): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key()) }
        return hex(mac.doFinal(KEY_ID_INPUT.toByteArray(Charsets.UTF_8)), 4)
    }

    private fun hex(bytes: ByteArray, take: Int): String = bytes.take(take).joinToString("") { "%02x".format(it) }

    /** The HMAC key, created on first use. It is not exportable: hashes can only be made, and so matched, on this phone. */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }
}
