package io.github.stronghorse44.tunnels.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * The checked phone's side. Per verifier, one persistent identity key: created and attested the first time (that
 * attestation is what the verifier pins), then used to sign each later audit's freshly attested key. Keys never
 * leave the keystore; only certificates and signatures go into the QR code.
 */
object AuditeeKeys {
    private const val PROVIDER = "AndroidKeyStore"
    private const val PREFIX = "io.github.stronghorse44.tunnels.pairing."
    private const val EPHEMERAL = PREFIX + "audit"

    private fun identityAlias(verifierId: ByteArray) = PREFIX + "identity." + verifierId.joinToString("") { "%02x".format(it) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun isPaired(verifierId: ByteArray): Boolean = runCatching { keyStore().containsAlias(identityAlias(verifierId)) }.getOrDefault(false)

    /** Drops the identity key for one verifier, so the next answer pairs afresh. */
    fun forget(verifierId: ByteArray) {
        runCatching { keyStore().deleteEntry(identityAlias(verifierId)) }
    }

    /** The answer to [challenge]: a pairing the first time, an audit after that. Runs keystore work: call off the main thread. */
    fun respond(challenge: PairingProtocol.Challenge): PairingProtocol.Response {
        val ks = keyStore()
        val identity = identityAlias(challenge.verifierId)
        if (!ks.containsAlias(identity)) {
            val strongBox = generate(identity, challenge.challenge)
            return PairingProtocol.Response(PairingProtocol.Kind.PAIR, challenge.verifierId, strongBox, chainOf(ks, identity))
        }
        try {
            val strongBox = generate(EPHEMERAL, challenge.challenge)
            val chain = chainOf(ks, EPHEMERAL)
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(ks.getKey(identity, null) as PrivateKey)
                update(PairingProtocol.auditSignedData(challenge.challenge, chain.first()))
                sign()
            }
            val identityKey = ks.getCertificate(identity).publicKey.encoded
            return PairingProtocol.Response(PairingProtocol.Kind.AUDIT, challenge.verifierId, strongBox, chain, identityKey, signature)
        } finally {
            runCatching { ks.deleteEntry(EPHEMERAL) }
        }
    }

    /** Leaf first; a self-signed root at the end is left out, since the verifier holds the roots. */
    private fun chainOf(ks: KeyStore, alias: String): List<ByteArray> {
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }.orEmpty()
        check(chain.isNotEmpty()) { "the keystore returned no certificate chain" }
        val trimmed = if (chain.size > 1 && chain.last().subjectX500Principal == chain.last().issuerX500Principal) chain.dropLast(1) else chain
        return trimmed.map { it.encoded }
    }

    /** Generates an attested EC P-256 signing key, StrongBox first; true when StrongBox holds it. */
    private fun generate(alias: String, challenge: ByteArray): Boolean {
        val ks = keyStore()
        runCatching { ks.deleteEntry(alias) }
        return try {
            generate(alias, challenge, strongBox = true)
            true
        } catch (_: ProviderException) {
            runCatching { ks.deleteEntry(alias) }
            generate(alias, challenge, strongBox = false)
            false
        }
    }

    private fun generate(alias: String, challenge: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }
}
