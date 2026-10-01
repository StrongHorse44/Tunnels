package io.github.stronghorse44.tunnels.certs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.security.auth.x500.X500Principal

class CertSummaryTest {
    @Test
    fun summarisesSelfSignedRsaRoot() {
        val s = CertSummary.of(TestCerts.root)
        assertEquals(TestCerts.ROOT_FINGERPRINT, s.fingerprint)
        assertEquals("E271773B…4275C265", s.shortFingerprint)
        assertEquals("Tunnels Test Root", s.subjectCn)
        assertEquals("Tunnels Project", s.subjectO)
        assertEquals("Tunnels Test Root", s.issuerCn)
        assertEquals("2026-10-01", s.notBefore)
        assertEquals("2036-09-28", s.notAfter)
        assertEquals("RSA", s.keyAlgorithm)
        assertEquals(2048, s.keySize)
        assertEquals("RSA 2048", s.keyDescription)
        assertTrue(s.isCa)
        assertTrue(s.selfSigned)
        assertEquals("SHA256withRSA", s.signatureAlgorithm)
        assertEquals("Tunnels Test Root", s.displayName)
    }

    @Test
    fun summarisesEcLeafSignedByRoot() {
        val s = CertSummary.of(TestCerts.leaf)
        assertEquals(TestCerts.LEAF_FINGERPRINT, s.fingerprint)
        assertEquals("leaf.example.test", s.subjectCn)
        assertEquals("Leaf Org", s.subjectO)
        assertEquals("Tunnels Test Root", s.issuerCn)
        assertEquals("2027-10-01", s.notAfter)
        assertEquals("EC", s.keyAlgorithm)
        assertEquals(256, s.keySize)
        assertFalse(s.isCa)
        assertFalse(s.selfSigned)
    }

    @Test
    fun fingerprintIsUppercaseHexWithColons() {
        val fp = CertSummary.fingerprint(byteArrayOf())
        // SHA-256 of the empty input.
        assertEquals("E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55", fp)
        assertEquals("E3B0C442…7852B855", CertSummary.shortForm(fp))
        assertEquals("ABCD", CertSummary.shortForm("AB:CD"))
    }

    @Test
    fun distinguishedNameHandlesEscapesAndMultiValuedRdns() {
        val dn = DistinguishedName.of(X500Principal("CN=Foo\\, Inc.,O=Bar+OU=Ops,C=US"))
        assertEquals("Foo, Inc.", dn.commonName)
        assertEquals("Bar", dn.organization)
        assertEquals("Ops", dn["ou"])
        assertEquals("US", dn["C"])
        assertNull(dn["L"])
    }

    @Test
    fun distinguishedNameKeepsNonAsciiAndQuotedValues() {
        assertEquals("Müller GmbH", DistinguishedName.of(X500Principal("O=Müller GmbH")).organization)
        assertEquals("a,b", DistinguishedName.parse("CN=\"a,b\",O=x").commonName)
        assertEquals("Müller", DistinguishedName.parse("CN=M\\C3\\BCller").commonName)
        assertEquals("hi", DistinguishedName.parse("CN=#0C026869").commonName) // UTF8String "hi"
        assertEquals("#00", DistinguishedName.parse("CN=#00").commonName) // not a string type: kept raw
    }

    @Test
    fun distinguishedNameSurvivesGarbage() {
        assertTrue(DistinguishedName.parse("").attributes.isEmpty())
        assertTrue(DistinguishedName.parse("=x,,+").attributes.isEmpty())
        assertEquals("x", DistinguishedName.parse("CN=x\\").commonName)
    }
}
