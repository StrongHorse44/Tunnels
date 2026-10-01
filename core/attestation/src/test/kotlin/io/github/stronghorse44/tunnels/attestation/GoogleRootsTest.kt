package io.github.stronghorse44.tunnels.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRootsTest {
    @Test
    fun bundledRootsDecodeAndMatchTheirFingerprints() {
        assertEquals(5, GoogleRoots.all.size)
        for (root in GoogleRoots.all) {
            assertEquals(root.name, root.sha256, GoogleRoots.sha256(root.der).toHex())
            val cert = root.certificate
            cert.verify(cert.publicKey) // self-signed
            assertEquals(root.name, cert.subjectX500Principal, cert.issuerX500Principal)
        }
        assertEquals(listOf("RSA", "RSA", "RSA", "RSA", "EC"), GoogleRoots.all.map { it.publicKey.algorithm })
        assertEquals(2, GoogleRoots.publicKeys.size)
        val ca1 = GoogleRoots.all[4].certificate.subjectX500Principal.name
        assertTrue(ca1, ca1.contains("CN=Key Attestation CA1") && ca1.contains("O=Google LLC"))
        // Roots 0..3 are re-issues of one subject and key.
        assertEquals(1, GoogleRoots.all.take(4).map { it.certificate.subjectX500Principal }.toSet().size)
        assertEquals(1, GoogleRoots.all.take(4).map { it.publicKey.encoded.toHex() }.toSet().size)
    }

    @Test
    fun aRootAloneVerifiesAgainstItself() {
        for (root in GoogleRoots.all) {
            val result = GoogleRoots.verify(listOf(root.certificate))
            assertTrue(root.name, result is ChainVerification.Verified)
            assertEquals("true", result.value)
        }
        // Roots 0..3 share a key, so root 1 alone resolves to the first bundled root with that key.
        assertEquals(GoogleRoots.all[0], (GoogleRoots.verify(listOf(GoogleRoots.all[1].certificate)) as ChainVerification.Verified).root)
    }

    @Test
    fun brokenChainsFail() {
        val rsa = GoogleRoots.all[0].certificate
        val ec = GoogleRoots.all[4].certificate
        val r = GoogleRoots.verify(listOf(ec, rsa))
        assertTrue(r is ChainVerification.Failed)
        assertEquals("false", r.value)
        assertTrue((r as ChainVerification.Failed).reason.contains("certificate 1 of 2 is not signed by certificate 2"))
    }

    @Test
    fun unreadableOrMissingChainsAreUnverified() {
        assertEquals("unverified: no certificate chain", GoogleRoots.verify(emptyList()).value)
        val r = GoogleRoots.verifyEncoded(listOf(byteArrayOf(1, 2, 3)))
        assertTrue(r is ChainVerification.Unverified)
        assertTrue(r.value.startsWith("unverified: unreadable certificate"))
        assertEquals("true", GoogleRoots.verifyEncoded(listOf(GoogleRoots.all[4].der)).value)
        val long = List(11) { GoogleRoots.all[4].certificate }
        assertTrue(GoogleRoots.verify(long).value.startsWith("unverified: chain of 11"))
    }
}
