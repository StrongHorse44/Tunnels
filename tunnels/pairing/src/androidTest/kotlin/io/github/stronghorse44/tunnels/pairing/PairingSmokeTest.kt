package io.github.stronghorse44.tunnels.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.ProviderException
import java.security.SecureRandom
import java.time.Instant

/**
 * Both sides of an exchange on one device: this phone's keystore answers its own challenges, through the real
 * encoding, parts and verifier. An emulator's attestation does not chain to Google, so the verdict fails there; what
 * is checked is that every step runs and that the identity key really signs the audit.
 */
@RunWith(AndroidJUnit4::class)
class PairingSmokeTest {
    private fun challenge(verifierId: ByteArray) =
        PairingProtocol.Challenge(ByteArray(PairingProtocol.CHALLENGE_BYTES).also { SecureRandom().nextBytes(it) }, verifierId)

    private fun roundTrip(c: PairingProtocol.Challenge, response: PairingProtocol.Response): PairingProtocol.Response {
        val collector = Parts.Collector(c.tag)
        val parts = Parts.split(response.encode(), c.tag)
        val payload = parts.reversed().firstNotNullOfOrNull { collector.add(it) }
        return PairingProtocol.Response.decode(payload!!)!!
    }

    @Test
    fun pairsThenAuditsWithTheSameIdentityKey() {
        val verifierId = ByteArray(PairingProtocol.VERIFIER_ID_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val first = challenge(verifierId)
            val pairing = try {
                AuditeeKeys.respond(first)
            } catch (e: ProviderException) {
                assumeTrue("no key attestation on this device: ${e.message}", false)
                return
            }
            assertEquals(PairingProtocol.Kind.PAIR, pairing.kind)
            assertTrue(AuditeeKeys.isPaired(verifierId))
            val decoded = roundTrip(first, pairing)
            val chain = PairingVerifier.readChain(decoded.chain)
            assertNotNull("the leaf carries an attestation record", chain.attestation)
            assertArrayEquals(first.challenge, chain.attestation!!.attestationChallenge)
            val verdict = PairingVerifier.verify(decoded, first, emptyList(), Instant.now())
            assertTrue(verdict.checks.toString(), verdict.checks.any { it.label == "Fresh" && it.status == Verdict.Status.PASS })

            val second = challenge(verifierId)
            val audit = roundTrip(second, AuditeeKeys.respond(second))
            assertEquals(PairingProtocol.Kind.AUDIT, audit.kind)
            assertArrayEquals("the identity key is the one attested at pairing", chain.leafKey, audit.identityKey)
            assertTrue(PairingVerifier.signatureValid(audit.identityKey!!, PairingProtocol.auditSignedData(second.challenge, audit.chain.first()), audit.signature!!))
        } finally {
            AuditeeKeys.forget(verifierId)
        }
        assertTrue(!AuditeeKeys.isPaired(verifierId))
    }
}
