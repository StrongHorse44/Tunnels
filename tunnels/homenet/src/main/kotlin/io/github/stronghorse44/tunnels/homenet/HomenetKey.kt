package io.github.stronghorse44.tunnels.homenet

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.stronghorse44.tunnels.lan.RouterTokens
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * The keyed hash behind Home network's confirmed networks (audit L8 and its HMAC item): HMAC-SHA256 under an Android
 * Keystore key that is created once, can only sign and never leaves the keystore. Off this phone (an export, a copied
 * database) nobody can compute a token, so the stored tokens cannot be matched to a network or a router, or reversed.
 * The plain-Kotlin side ([RouterTokens]) takes [mac] and does the truncation and the domain separation.
 *
 * The key is created only when the alias does not exist. Any other failure throws and is never answered by deleting or
 * recreating the key (the M5 lesson): a recreated key would silently orphan every confirmation. Nothing here logs an
 * input, a tag or the key.
 */
class HomenetKey {
    /** HMAC-SHA256 of [input] under the key, 64 lowercase hex. */
    fun mac(input: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(key()) }
        return mac.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** The key's id: 8 hex of the tag of a fixed text (see [RouterTokens.keyId]). */
    fun keyId(): String = RouterTokens(::mac).keyId()

    /** The key itself, for the device test that checks it is not exportable. */
    internal fun key(): SecretKey = synchronized(LOCK) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
            return@synchronized generator.generateKey()
        }
        ks.getKey(ALIAS, null) as? SecretKey ?: throw IllegalStateException("The homenet key is not a secret key.")
    }

    companion object {
        const val ALIAS = "tunnels.homenet.network"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
        private val LOCK = Any()
    }
}
