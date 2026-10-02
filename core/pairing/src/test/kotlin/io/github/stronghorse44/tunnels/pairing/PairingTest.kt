package io.github.stronghorse44.tunnels.pairing

import io.github.stronghorse44.tunnels.attestation.AuthorizationList
import io.github.stronghorse44.tunnels.attestation.ChainVerification
import io.github.stronghorse44.tunnels.attestation.KeyAttestation
import io.github.stronghorse44.tunnels.attestation.RootCertificate
import io.github.stronghorse44.tunnels.attestation.RootOfTrust
import io.github.stronghorse44.tunnels.attestation.SecurityLevel
import io.github.stronghorse44.tunnels.attestation.VerifiedBootState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.random.Random

class PairingTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val pixel10GrapheneOs = hex("3F7415EA26F5DF5B14EA6D153256071A7A1AF9CE7B0970B7311CC463C7EA02C7")
    private val challenge = PairingProtocol.Challenge(Random(1).nextBytes(32), Random(2).nextBytes(16))

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ecKeys() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun attestation(
        challenge: ByteArray,
        bootKey: ByteArray = pixel10GrapheneOs,
        locked: Boolean = true,
        state: VerifiedBootState = VerifiedBootState.SELF_SIGNED,
        patch: Int = 202609,
        level: SecurityLevel = SecurityLevel.STRONG_BOX,
    ) = KeyAttestation(
        attestationVersion = 300, attestationSecurityLevel = level, attestationSecurityLevelCode = level.code,
        keymasterVersion = 300, keymasterSecurityLevel = level, keymasterSecurityLevelCode = level.code,
        attestationChallenge = challenge,
        softwareEnforced = AuthorizationList(),
        teeEnforced = AuthorizationList(
            rootOfTrust = RootOfTrust(bootKey, locked, state, state.code, null),
            osVersion = 160000, osPatchLevel = patch,
        ),
    )

    private fun reader(att: KeyAttestation?, leafKey: ByteArray, verified: Boolean = true): (List<ByteArray>) -> ReadChain = { chain ->
        ReadChain(
            if (verified) ChainVerification.Verified(RootCertificate("Google test root", "", "")) else ChainVerification.Failed("not Google"),
            leafKey, chain.first(), att,
        )
    }

    @Test
    fun base45RoundTripsAndRejectsBadText() {
        for (n in 0..40) {
            val bytes = Random(n).nextBytes(n)
            assertArrayEquals(bytes, Base45.decode(Base45.encode(bytes)))
        }
        assertEquals("BB8", Base45.encode("AB".toByteArray())) // RFC 9285 example
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertTrue(runCatching { Base45.decode("A") }.isFailure)
        assertTrue(runCatching { Base45.decode("ab") }.isFailure)
        assertTrue(runCatching { Base45.decode("GGW") }.isFailure) // 65535 + 1 overflows
    }

    @Test
    fun challengesAndResponsesRoundTripAndRefuseStrangers() {
        val text = challenge.encode()
        assertTrue(text.startsWith("TNLS-PAIR1:"))
        assertTrue(text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
        val back = PairingProtocol.Challenge.decode(text)!!
        assertArrayEquals(challenge.challenge, back.challenge)
        assertArrayEquals(challenge.verifierId, back.verifierId)
        assertNull(PairingProtocol.Challenge.decode("https://example.com"))
        assertNull(PairingProtocol.Challenge.decode("TNLS-PAIR1:AB"))

        val r = PairingProtocol.Response(PairingProtocol.Kind.AUDIT, challenge.verifierId, true, listOf(Random(3).nextBytes(700), Random(4).nextBytes(500)), Random(5).nextBytes(91), Random(6).nextBytes(71))
        val decoded = PairingProtocol.Response.decode(r.encode())!!
        assertEquals(PairingProtocol.Kind.AUDIT, decoded.kind)
        assertTrue(decoded.strongBox)
        assertEquals(2, decoded.chain.size)
        assertArrayEquals(r.chain[1], decoded.chain[1])
        assertArrayEquals(r.signature, decoded.signature)
        assertNull(PairingProtocol.Response.decode(Random(7).nextBytes(100)))
        assertNull(PairingProtocol.Response.decode(r.encode().copyOf(20)))
    }

    @Test
    fun partsReassembleInAnyOrderAndIgnoreOtherExchanges() {
        val payload = Random(8).nextBytes(2000)
        val parts = Parts.split(payload, challenge.tag)
        assertEquals(3, parts.size)
        val collector = Parts.Collector(challenge.tag)
        assertNull(collector.add(parts[2]))
        assertNull(collector.add(Parts.split(payload, "FFFFFF")[0]))
        assertNull(collector.add(parts[2]))
        assertNull(collector.add(parts[0]))
        assertEquals(2, collector.received)
        assertArrayEquals(payload, collector.add(parts[1]))
    }

    @Test
    fun aGenuinePhoneIsPairedThenAuditedAsTheSamePhone() {
        val identity = ecKeys()
        val leafDer = Random(9).nextBytes(600)
        val pairing = PairingProtocol.Response(PairingProtocol.Kind.PAIR, challenge.verifierId, true, listOf(leafDer))
        val paired = PairingVerifier.verify(pairing, challenge, emptyList(), now, reader(attestation(challenge.challenge), identity.public.encoded))
        assertEquals(Verdict.Outcome.PAIRED, paired.outcome)
        val pin = paired.pin!!
        assertEquals("Pixel 10", pin.name)
        assertEquals(202609, pin.osPatch)
        assertTrue(paired.checks.none { it.status == Verdict.Status.FAIL })

        // Later: a fresh key for a fresh challenge, signed by the pinned identity key.
        val next = PairingProtocol.Challenge(Random(10).nextBytes(32), challenge.verifierId)
        val fresh = ecKeys()
        val freshLeaf = Random(11).nextBytes(620)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(PairingProtocol.auditSignedData(next.challenge, freshLeaf))
            sign()
        }
        val audit = PairingProtocol.Response(PairingProtocol.Kind.AUDIT, next.verifierId, true, listOf(freshLeaf), identity.public.encoded, sig)
        val decoded = PairingProtocol.Response.decode(audit.encode())!!
        val verified = PairingVerifier.verify(decoded, next, listOf(pin), now.plusSeconds(86400), reader(attestation(next.challenge, patch = 202610), fresh.public.encoded))
        assertEquals(verified.checks.toString(), Verdict.Outcome.VERIFIED, verified.outcome)
        assertEquals(202610, verified.pin!!.osPatch)
        assertEquals(pin.pairedAt, verified.pin!!.pairedAt)

        // The same answer cannot be replayed against a newer challenge.
        val later = PairingProtocol.Challenge(Random(12).nextBytes(32), challenge.verifierId)
        val replay = PairingVerifier.verify(decoded, later, listOf(pin), now, reader(attestation(next.challenge), fresh.public.encoded))
        assertTrue(replay.failed)
        assertTrue(replay.checks.any { it.label == "Fresh" && it.status == Verdict.Status.FAIL })

        // Another phone's key, a new OS or a downgrade all fail.
        val stranger = ecKeys()
        val forged = Signature.getInstance("SHA256withECDSA").run {
            initSign(stranger.private)
            update(PairingProtocol.auditSignedData(next.challenge, freshLeaf))
            sign()
        }
        val impostor = PairingProtocol.Response(PairingProtocol.Kind.AUDIT, next.verifierId, true, listOf(freshLeaf), identity.public.encoded, forged)
        assertTrue(PairingVerifier.verify(impostor, next, listOf(pin), now, reader(attestation(next.challenge), fresh.public.encoded)).checks.any { it.label == "Same phone" && it.status == Verdict.Status.FAIL })
        val newOs = PairingVerifier.verify(decoded, next, listOf(pin), now, reader(attestation(next.challenge, bootKey = hex("AB".repeat(32))), fresh.public.encoded))
        assertTrue(newOs.checks.any { it.label == "Same OS" && it.status == Verdict.Status.FAIL })
        val downgrade = PairingVerifier.verify(decoded, next, listOf(pin), now, reader(attestation(next.challenge, patch = 202601), fresh.public.encoded))
        assertTrue(downgrade.checks.any { it.label == "No downgrade" && it.status == Verdict.Status.FAIL })
        // Unknown to this verifier: pair again.
        assertTrue(PairingVerifier.verify(decoded, next, emptyList(), now, reader(attestation(next.challenge), fresh.public.encoded)).failed)
    }

    @Test
    fun unlockedSoftwareOrNonGoogleAttestationsNeverPair() {
        val key = ecKeys().public.encoded
        val r = PairingProtocol.Response(PairingProtocol.Kind.PAIR, challenge.verifierId, false, listOf(Random(13).nextBytes(500)))
        fun outcome(att: KeyAttestation, verified: Boolean = true) = PairingVerifier.verify(r, challenge, emptyList(), now, reader(att, key, verified))
        assertTrue(outcome(attestation(challenge.challenge, locked = false)).failed)
        assertTrue(outcome(attestation(challenge.challenge, level = SecurityLevel.SOFTWARE)).failed)
        assertTrue(outcome(attestation(challenge.challenge), verified = false).failed)
        assertTrue(outcome(attestation(challenge.challenge, state = VerifiedBootState.UNVERIFIED)).failed)
        val otherVerifier = PairingProtocol.Response(PairingProtocol.Kind.PAIR, Random(14).nextBytes(16), false, r.chain)
        assertTrue(PairingVerifier.verify(otherVerifier, challenge, emptyList(), now, reader(attestation(challenge.challenge), key)).failed)
        val unknownOs = outcome(attestation(challenge.challenge, bootKey = hex("CD".repeat(32))))
        assertEquals(Verdict.Outcome.PAIRED, unknownOs.outcome)
        assertTrue(unknownOs.checks.any { it.label == "Operating system" && it.status == Verdict.Status.WARN })
    }

    @Test
    fun pinsRoundTripAndDamagedLinesAreDropped() {
        val key = ecKeys().public.encoded
        val pin = Pin(Pin.idOf(key), key, "Pixel 10 | spare", "3F74", now, now, 202609, true)
        val back = Pin.decode(Pin.encode(listOf(pin)) + "\nbroken|line")
        assertEquals(1, back.size)
        assertEquals("Pixel 10 / spare", back.single().name)
        assertArrayEquals(key, back.single().identityKey)
        assertEquals(emptyList<Pin>(), Pin.decode(null))
    }
}
