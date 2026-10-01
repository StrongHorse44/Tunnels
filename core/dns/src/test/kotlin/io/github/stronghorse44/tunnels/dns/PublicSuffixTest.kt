package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSuffixTest {
    private fun reg(host: String) = PublicSuffix.registrableDomain(host)

    @Test
    fun plainTwoLabelDomainsAreTheirOwnRegistrableDomain() {
        assertEquals("example.com", reg("example.com"))
        assertEquals("example.com", reg("www.example.com"))
        assertEquals("example.com", reg("a.b.c.d.example.com"))
    }

    @Test
    fun normalizesCaseAndTrailingDot() {
        assertEquals("example.com", reg("WWW.Example.COM."))
        assertEquals("example.com", reg("  example.com  "))
    }

    @Test
    fun knownCountrySecondLevels() {
        assertEquals("bbc.co.uk", reg("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", reg("news.static.bbc.co.uk"))
        assertEquals("abc.net.au", reg("iview.abc.net.au"))
        assertEquals("yahoo.co.jp", reg("news.yahoo.co.jp"))
        assertEquals("globo.com.br", reg("g1.globo.com.br"))
        assertEquals("gov.uk", reg("gov.uk"))
    }

    @Test
    fun hostingSuffixesKeepTheTenantLabel() {
        assertEquals("foo.github.io", reg("foo.github.io"))
        assertEquals("foo.github.io", reg("www.foo.github.io"))
        assertEquals("myapp.herokuapp.com", reg("myapp.herokuapp.com"))
        assertEquals("d123.cloudfront.net", reg("d123.cloudfront.net"))
        assertEquals("bucket.s3.amazonaws.com", reg("bucket.s3.amazonaws.com"))
        assertEquals("amazonaws.com", reg("amazonaws.com"))
        // Only the listed AWS suffixes are public; a plain service host stays with the provider.
        assertEquals("amazonaws.com", reg("sts.amazonaws.com"))
        assertEquals("proj.appspot.com", reg("api.proj.appspot.com"))
    }

    @Test
    fun wildcardAndExceptionRules() {
        // *.compute.amazonaws.com: the region label is part of the suffix.
        assertEquals("ec2-1-2-3-4.eu-west-1.compute.amazonaws.com", reg("ec2-1-2-3-4.eu-west-1.compute.amazonaws.com"))
        // *.ck is public, except www.ck.
        assertEquals("foo.bar.ck", reg("www.foo.bar.ck"))
        assertEquals("www.ck", reg("www.ck"))
        assertEquals("www.ck", reg("sub.www.ck"))
    }

    @Test
    fun unknownSuffixesFallBackToLastTwoLabels() {
        assertEquals("example.xyz", reg("a.b.example.xyz"))
        assertEquals("example.unknowntld", reg("cdn.example.unknowntld"))
    }

    @Test
    fun ipLiteralsAndSingleLabelsPassThrough() {
        assertEquals("10.0.0.1", reg("10.0.0.1"))
        assertEquals("192.168.1.254", reg("192.168.1.254"))
        assertEquals("fe80::1", reg("FE80::1"))
        assertEquals("localhost", reg("localhost"))
        assertEquals("router", reg("router."))
        assertEquals("", reg(""))
        assertTrue(PublicSuffix.isIpLiteral("1.2.3.4"))
        assertFalse(PublicSuffix.isIpLiteral("1.2.3.4.com"))
    }

    @Test
    fun hostThatIsItselfAPublicSuffixIsReturnedWhole() {
        assertEquals("co.uk", reg("co.uk"))
        assertEquals("github.io", reg("github.io"))
        assertEquals("com", reg("com"))
    }

    @Test
    fun bundleHasAUsefulNumberOfRules() {
        assertTrue(PublicSuffix.ruleCount >= 200)
    }
}
