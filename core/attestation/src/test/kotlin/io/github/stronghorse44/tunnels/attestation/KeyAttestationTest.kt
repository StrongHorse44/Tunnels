package io.github.stronghorse44.tunnels.attestation

import io.github.stronghorse44.tunnels.attestation.DerBuilder.bool
import io.github.stronghorse44.tunnels.attestation.DerBuilder.ctx
import io.github.stronghorse44.tunnels.attestation.DerBuilder.enum
import io.github.stronghorse44.tunnels.attestation.DerBuilder.int
import io.github.stronghorse44.tunnels.attestation.DerBuilder.nul
import io.github.stronghorse44.tunnels.attestation.DerBuilder.octet
import io.github.stronghorse44.tunnels.attestation.DerBuilder.seq
import io.github.stronghorse44.tunnels.attestation.DerBuilder.set
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAttestationTest {
    private val challenge = DerBuilder.bytes(9, 32)
    private val bootKey = DerBuilder.bytes(5, 32)
    private val bootHash = DerBuilder.bytes(6, 32)

    /** A record shaped like a Pixel's: version 300, StrongBox, root of trust and patch levels in teeEnforced. */
    private fun record(
        teeExtras: ByteArray = ByteArray(0),
        softwareList: ByteArray = seq(ctx(709, octet("attestation application id, skipped"))),
        withHash: Boolean = true,
        state: Int = 1,
        locked: Boolean = true,
    ): ByteArray {
        val rot = if (withHash) seq(octet(bootKey), bool(locked), enum(state), octet(bootHash)) else seq(octet(bootKey), bool(locked), enum(state))
        val tee = seq(
            ctx(1, set(int(2), int(3))), // purpose, skipped
            ctx(2, int(3)), // algorithm, skipped
            ctx(3, int(256)), // key size, skipped
            ctx(600, nul()), // rollback resistance, skipped
            ctx(702, int(0)), // origin, skipped
            ctx(704, rot),
            ctx(705, int(160000)),
            ctx(706, int(202509)),
            ctx(710, octet("google")),
            ctx(711, octet("frankel")),
            ctx(712, octet("frankel")),
            ctx(718, int(20250905)),
            ctx(719, int(20250905)),
            teeExtras,
        )
        return seq(int(300), enum(2), int(300), enum(2), octet(challenge), octet(ByteArray(0)), softwareList, tee)
    }

    @Test
    fun parsesEveryField() {
        val a = KeyAttestation.parse(record())
        assertEquals(300, a.attestationVersion)
        assertEquals(SecurityLevel.STRONG_BOX, a.attestationSecurityLevel)
        assertEquals(300, a.keymasterVersion)
        assertEquals(SecurityLevel.STRONG_BOX, a.keymasterSecurityLevel)
        assertArrayEquals(challenge, a.attestationChallenge)

        val tee = a.teeEnforced
        val rot = tee.rootOfTrust!!
        assertArrayEquals(bootKey, rot.verifiedBootKey)
        assertTrue(rot.deviceLocked)
        assertEquals(VerifiedBootState.SELF_SIGNED, rot.verifiedBootState)
        assertArrayEquals(bootHash, rot.verifiedBootHash)
        assertEquals(160000, tee.osVersion)
        assertEquals(202509, tee.osPatchLevel)
        assertEquals(20250905, tee.vendorPatchLevel)
        assertEquals(20250905, tee.bootPatchLevel)
        assertEquals("google", tee.attestationIdBrand)
        assertEquals("frankel", tee.attestationIdDevice)
        assertEquals("frankel", tee.attestationIdProduct)
        assertEquals(listOf(1, 2, 3, 600, 702), tee.skippedTags)

        assertTrue(a.softwareEnforced.isEmpty)
        assertEquals(listOf(709), a.softwareEnforced.skippedTags)
        assertEquals(rot, a.rootOfTrust)
        assertEquals(tee.copy(skippedTags = emptyList()), a.enforced())
    }

    @Test
    fun acceptsTheOctetStringWrapperFromGetExtensionValue() {
        val wrapped = octet(record())
        val a = KeyAttestation.parse(wrapped)
        assertEquals(300, a.attestationVersion)
        assertEquals(202509, a.teeEnforced.osPatchLevel)
    }

    @Test
    fun rootOfTrustWithoutHashAndUnknownCodes() {
        val a = KeyAttestation.parse(seq(int(2), enum(7), int(3), enum(1), octet(challenge), octet(ByteArray(0)), seq(), seq(ctx(704, seq(octet(bootKey), bool(false), enum(9))))))
        assertEquals(2, a.attestationVersion)
        assertNull(a.attestationSecurityLevel)
        assertEquals(7, a.attestationSecurityLevelCode)
        assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, a.keymasterSecurityLevel)
        val rot = a.teeEnforced.rootOfTrust!!
        assertNull(rot.verifiedBootHash)
        assertFalse(rot.deviceLocked)
        assertNull(rot.verifiedBootState)
        assertEquals(9, rot.verifiedBootStateCode)
        assertNull(a.teeEnforced.osVersion)
    }

    @Test
    fun softwareOnlyAttestationFallsBackToSoftwareList() {
        val software = seq(ctx(704, seq(octet(ByteArray(32)), bool(false), enum(2))), ctx(705, int(140000)), ctx(706, int(202401)))
        val a = KeyAttestation.parse(seq(int(4), enum(0), int(41), enum(0), octet(challenge), octet(ByteArray(0)), software, seq()))
        assertEquals(SecurityLevel.SOFTWARE, a.attestationSecurityLevel)
        assertNull(a.teeEnforced.rootOfTrust)
        assertEquals(VerifiedBootState.UNVERIFIED, a.rootOfTrust!!.verifiedBootState)
        assertTrue(KnownBootKeys.isEmptyKey(a.rootOfTrust!!.verifiedBootKey))
        assertEquals(140000, a.enforced().osVersion)
        assertEquals(202401, a.enforced().osPatchLevel)
    }

    @Test
    fun unknownTagsAreSkippedNotFatal() {
        val a = KeyAttestation.parse(record(teeExtras = DerBuilder.concat(ctx(9999, octet("future")), ctx(720, seq(int(1), int(2))), ctx(1000, nul()))))
        assertEquals(listOf(1, 2, 3, 600, 702, 9999, 720, 1000), a.teeEnforced.skippedTags)
        assertEquals(202509, a.teeEnforced.osPatchLevel)
    }

    @Test
    fun attestationIdsAreTrimmedToPrintableText() {
        val a = KeyAttestation.parse(record(teeExtras = ByteArray(0), softwareList = seq(ctx(710, octet(byteArrayOf(0x41, 0x00, 0x0A, 0x42) + ByteArray(100) { 0x43 })))))
        assertEquals("AB" + "C".repeat(62), a.softwareEnforced.attestationIdBrand)
    }

    @Test
    fun truncatedExtensionIsAClearError() {
        val whole = record()
        val messages = HashSet<String>()
        for (cut in listOf(1, 2, 5, 20, whole.size / 3, whole.size / 2, whole.size - 40, whole.size - 2, whole.size - 1)) {
            val e = assertThrows("cut at $cut", DerException::class.java) { KeyAttestation.parse(whole.copyOf(cut)) }
            messages += e.message!!
            assertTrue("cut at $cut: ${e.message}", e.message!!.contains("truncated") || e.message!!.contains("runs past"))
        }
        assertTrue(messages.isNotEmpty())
        assertTrue(KeyAttestation.tryParse(whole.copyOf(10)).isFailure)
        assertTrue(KeyAttestation.tryParse(whole).isSuccess)
    }

    @Test
    fun wrongShapesAreErrorsWithContext() {
        assertEquals("attestation extension is empty", assertThrows(DerException::class.java) { KeyAttestation.parse(ByteArray(0)) }.message)
        assertTrue(assertThrows(DerException::class.java) { KeyAttestation.parse(int(5)) }.message!!.contains("SEQUENCE"))
        assertTrue(assertThrows(DerException::class.java) { KeyAttestation.parse(seq(int(1), int(2))) }.message!!.contains("expected at least 8"))
        val badList = seq(int(300), enum(2), int(300), enum(2), octet(challenge), octet(ByteArray(0)), int(1), seq())
        assertTrue(assertThrows(DerException::class.java) { KeyAttestation.parse(badList) }.message!!.contains("softwareEnforced"))
        val badRot = seq(int(300), enum(2), int(300), enum(2), octet(challenge), octet(ByteArray(0)), seq(), seq(ctx(704, seq(octet(bootKey)))))
        assertTrue(assertThrows(DerException::class.java) { KeyAttestation.parse(badRot) }.message!!.contains("rootOfTrust"))
        val trailing = DerBuilder.concat(record(), int(1))
        assertTrue(assertThrows(DerException::class.java) { KeyAttestation.parse(trailing) }.message!!.contains("trailing"))
    }

    @Test
    fun hexHelper() {
        assertEquals("00FF10", byteArrayOf(0, -1, 16).toHex())
        assertEquals("", ByteArray(0).toHex())
    }
}
