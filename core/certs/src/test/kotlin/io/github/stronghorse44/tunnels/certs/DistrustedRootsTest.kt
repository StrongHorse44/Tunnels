package io.github.stronghorse44.tunnels.certs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistrustedRootsTest {
    @Test
    fun matchesByOrganizationPrefixCaseInsensitively() {
        assertEquals("DigiNotar", DistrustedRoots.match(null, "Some CA", "DigiNotar B.V.")?.name)
        assertEquals("TrustCor", DistrustedRoots.match(null, null, "trustcor systems s. de r.l.")?.name)
        assertEquals("WoSign", DistrustedRoots.match(null, null, "WoSign CA Limited")?.name)
    }

    @Test
    fun matchesByCommonName() {
        assertEquals("StartCom", DistrustedRoots.match(null, "StartCom Certification Authority", "Unrelated")?.name)
        assertEquals("CNNIC", DistrustedRoots.match(null, "CNNIC ROOT", null)?.name)
        assertEquals("WoSign", DistrustedRoots.match(null, "CA 沃通根证书", null)?.name)
    }

    @Test
    fun ignoresLegitimateRootsAndTestCertificates() {
        assertNull(DistrustedRoots.match(null, "ISRG Root X1", "Internet Security Research Group"))
        assertNull(DistrustedRoots.match(null, "DigiCert Global Root G2", "DigiCert Inc"))
        assertNull(DistrustedRoots.match(null, null, null))
        assertNull(DistrustedRoots.match(CertSummary.of(TestCerts.root)))
        // A substring that is not a prefix does not match: "Not DigiNotar" is somebody else.
        assertNull(DistrustedRoots.match(null, "x", "Not DigiNotar"))
    }

    @Test
    fun everyEntryHasAReasonAndSomethingToMatchOn() {
        DistrustedRoots.entries.forEach { e ->
            assertTrue(e.name, e.reason.length > 20)
            assertTrue(e.name, e.organizations.isNotEmpty() || e.commonNames.isNotEmpty() || e.fingerprints.isNotEmpty())
            e.fingerprints.forEach { fp -> assertTrue(fp, fp.matches(Regex("[0-9A-F]{64}"))) }
        }
    }
}
