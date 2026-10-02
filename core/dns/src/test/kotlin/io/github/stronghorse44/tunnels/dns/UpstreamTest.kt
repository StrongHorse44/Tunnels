package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamTest {
    @Test
    fun theNetworkResolverIsTheDefaultAndDamagedValuesFallBackToIt() {
        assertEquals(Upstream(), Upstream.decode(null))
        assertFalse(Upstream().encrypted)
        assertEquals("your network's resolver (unencrypted)", Upstream().label)
        assertEquals(Upstream(), Upstream.decode("provider=nope"))
        assertEquals(Upstream(), Upstream.decode("provider=custom;url=http://plain.example/dns-query"))
    }

    @Test
    fun presetsAndCustomEndpointsRoundTrip() {
        val quad9 = Upstream.preset("quad9")!!
        assertTrue(quad9.encrypted)
        assertEquals("Quad9 (encrypted)", quad9.label)
        assertEquals(quad9, Upstream.decode(quad9.encode()))
        val custom = Upstream.custom("https://DNS.Example.net/dns-query")!!
        assertEquals("https://dns.example.net/dns-query", custom.url)
        assertEquals("dns.example.net (encrypted)", custom.label)
        assertEquals(custom, Upstream.decode(custom.encode()))
        // A preset id always resolves to the current preset URL, not a stored copy.
        assertEquals(quad9, Upstream.decode("provider=quad9;url=https://old.example/x"))
    }

    @Test
    fun onlyPlainHttpsUrlsAreAccepted() {
        assertNull(Upstream.validUrl("http://dns.example.net/dns-query"))
        assertNull(Upstream.validUrl("https://user@dns.example.net/dns-query"))
        assertNull(Upstream.validUrl("https://dns.example.net/dns-query?dns=abc"))
        assertNull(Upstream.validUrl("https://dns.example.net/a;b"))
        assertNull(Upstream.validUrl("not a url"))
        assertEquals("https://dns.example.net/dns-query", Upstream.validUrl("https://dns.example.net"))
        assertEquals("https://dns.nextdns.io/abc123", Upstream.validUrl(" https://dns.nextdns.io/abc123 "))
        assertEquals("https://10.0.0.1:8443/dns-query", Upstream.validUrl("https://10.0.0.1:8443/dns-query"))
    }

    @Test
    fun privateDnsHostnamesBecomeTheirDohEndpoint() {
        assertEquals("https://dns.quad9.net/dns-query", Upstream.fromPrivateDns("dns.quad9.net.")?.url)
        assertNull(Upstream.fromPrivateDns("bad host"))
    }

    @Test
    fun requestsCarryIdZeroAndRepliesGetTheirIdBack() {
        val query = DnsMessage.query(0x1234, "example.com")
        val request = Doh.request(query)!!
        assertEquals(0, DnsMessage.parse(request).id)
        assertEquals("example.com", DnsMessage.parse(request).queryName)
        val upstreamReply = DnsMessage.nxdomain(request)!!
        val reply = Doh.response(upstreamReply, query)
        assertNotNull(reply)
        assertEquals(0x1234, DnsMessage.parse(reply!!).id)
        assertTrue(DnsMessage.parse(reply).isResponse)
    }

    @Test
    fun repliesToAnotherQuestionOrNonRepliesAreRefused() {
        val query = DnsMessage.query(7, "example.com")
        assertNull(Doh.request(DnsMessage.nxdomain(query)!!))
        assertNull(Doh.response(DnsMessage.nxdomain(DnsMessage.query(0, "evil.example"))!!, query))
        assertNull(Doh.response(DnsMessage.nxdomain(DnsMessage.query(0, "example.com", DnsMessage.TYPE_AAAA))!!, query))
        assertNull("a query is not an answer", Doh.response(DnsMessage.query(0, "example.com"), query))
        assertNull(Doh.response(byteArrayOf(1, 2, 3), query))
    }
}
