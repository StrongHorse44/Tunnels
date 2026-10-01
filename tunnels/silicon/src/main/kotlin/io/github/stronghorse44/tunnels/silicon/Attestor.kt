package io.github.stronghorse44.tunnels.silicon

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/** One attestation round: the certificate chain (leaf first), whether StrongBox produced it, and the challenge sent. */
class AttestationResult(val chain: List<X509Certificate>, val strongBox: Boolean, val challenge: ByteArray)

/**
 * Generates a throwaway EC P-256 signing key in the Android Keystore with a fresh 32-byte attestation challenge,
 * StrongBox first and the TEE as fallback, reads its certificate chain and deletes the key again. Nothing about
 * the key persists; only the chain is returned.
 */
object Attestor {
    private const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "io.github.stronghorse44.tunnels.silicon.attest"
    private const val CHALLENGE_BYTES = 32

    fun attest(): AttestationResult {
        val challenge = ByteArray(CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        deleteQuietly(keyStore)
        try {
            val strongBox = try {
                generate(challenge, strongBox = true)
                true
            } catch (_: ProviderException) {
                // StrongBoxUnavailableException is a ProviderException; so is a StrongBox that refuses attestation.
                deleteQuietly(keyStore)
                generate(challenge, strongBox = false)
                false
            }
            val chain = keyStore.getCertificateChain(ALIAS) ?: throw IllegalStateException("the keystore returned no certificate chain")
            if (chain.isEmpty()) throw IllegalStateException("the keystore returned an empty certificate chain")
            return AttestationResult(chain.map { it as X509Certificate }, strongBox, challenge)
        } finally {
            deleteQuietly(keyStore)
        }
    }

    private fun generate(challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    /** True when no key with [ALIAS] remains, for the smoke test. */
    fun keyAbsent(): Boolean = runCatching {
        !KeyStore.getInstance(PROVIDER).apply { load(null) }.containsAlias(ALIAS)
    }.getOrDefault(true)

    private fun deleteQuietly(keyStore: KeyStore) {
        try {
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (_: Exception) {
            // Nothing to do: the next attempt deletes it first.
        }
    }
}
