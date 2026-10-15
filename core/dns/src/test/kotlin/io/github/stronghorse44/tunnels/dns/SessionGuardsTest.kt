package io.github.stronghorse44.tunnels.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class SessionGuardsTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    private val resolver = ip("192.168.1.1")
    private val query = DnsMessage.parse(DnsMessage.query(0x2a2a, "bank.example"))
    private val reply = DnsMessage.parse(DnsMessage.nxdomain(DnsMessage.query(0x2a2a, "bank.example"))!!)

    @Test
    fun replyFromTheQueriedResolverWithMatchingIdAndNameIsAccepted() {
        assertTrue(ReplyGate.accepts(resolver, ip("192.168.1.1"), 53, query, reply))
        assertTrue(ReplyGate.accepts(resolver, ip("::ffff:192.168.1.1"), 53, query, reply))
    }

    @Test
    fun replyFromAnotherAddressIsDropped() {
        // A device on the same Wi-Fi answering for the router: right id and name, wrong sender.
        assertFalse(ReplyGate.accepts(resolver, ip("192.168.1.66"), 53, query, reply))
        assertFalse(ReplyGate.accepts(resolver, ip("1.1.1.1"), 53, query, reply))
        assertFalse(ReplyGate.accepts(resolver, null, 53, query, reply))
    }

    @Test
    fun replyFromAnotherPortIsDropped() {
        assertFalse(ReplyGate.accepts(resolver, ip("192.168.1.1"), 5353, query, reply))
    }

    @Test
    fun replyWithAnotherIdOrNameOrNotAResponseIsDropped() {
        val otherId = DnsMessage.parse(DnsMessage.nxdomain(DnsMessage.query(0x2a2b, "bank.example"))!!)
        val otherName = DnsMessage.parse(DnsMessage.nxdomain(DnsMessage.query(0x2a2a, "evil.example"))!!)
        assertFalse(ReplyGate.accepts(resolver, resolver, 53, query, otherId))
        assertFalse(ReplyGate.accepts(resolver, resolver, 53, query, otherName))
        assertFalse(ReplyGate.accepts(resolver, resolver, 53, query, query))
    }

    @Test
    fun scopedLinkLocalResolversMustShareTheScope() {
        val a = Inet6Address.getByAddress(null, ip("fe80::1").address, 3)
        val sameScope = Inet6Address.getByAddress(null, ip("fe80::1").address, 3)
        val otherScope = Inet6Address.getByAddress(null, ip("fe80::1").address, 4)
        assertTrue(ReplyGate.accepts(a, sameScope, 53, query, reply))
        assertFalse(ReplyGate.accepts(a, otherScope, 53, query, reply))
    }

    @Test
    fun noUsableResolverMeansNoneNotAPublicFallback() {
        assertEquals(emptyList<InetAddress>(), Resolvers.select(emptyList()))
        // Link-local IPv6 without a scope cannot be sent to and nothing can scope it: none, so the session refuses.
        assertEquals(emptyList<InetAddress>(), Resolvers.select(listOf(ip("fe80::1"))))
        assertFalse(Resolvers.select(listOf(ip("fe80::1"))).contains(ip("1.1.1.1")))
        assertTrue(Resolvers.NONE.startsWith("No DNS resolver on this network"))
    }

    @Test
    fun resolversKeepIpv4FirstThenGlobalIpv6ThenScopedLinkLocal() {
        val scoped = Inet6Address.getByAddress(null, ip("fe80::53").address, 7)
        val picked = Resolvers.select(listOf(ip("2001:db8::53"), ip("fe80::53"), ip("192.168.1.1")), scope = { scoped })
        assertEquals(listOf(ip("192.168.1.1"), ip("2001:db8::53"), scoped), picked)
        assertEquals(7, (picked.last() as Inet6Address).scopeId)
    }

    @Test
    fun linkLocalResolversThatAlreadyCarryAScopeAreKept() {
        val scoped = Inet6Address.getByAddress(null, ip("fe80::1").address, 2)
        assertEquals(listOf<InetAddress>(scoped), Resolvers.select(listOf(scoped)))
    }

    @Test
    fun vpnCheckFailsClosed() {
        assertTrue(VpnPresence.anyUp { null })
        assertTrue(VpnPresence.anyUp { throw SecurityException("no access") })
        assertTrue(VpnPresence.anyUp { listOf(false, true) })
        assertFalse(VpnPresence.anyUp { listOf(false, false) })
        assertFalse(VpnPresence.anyUp { emptyList() })
    }
}
