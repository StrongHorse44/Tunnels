package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {
    private val net = NetworkFingerprint.sha256Hex("net:home")
    private val otherNet = NetworkFingerprint.sha256Hex("net:office")
    private val uuidA = "3f2a9c10-1111-2222-3333-444455556666"
    private val uuidB = "aaaaaaaa-1111-2222-3333-444455556666"

    private fun record(
        names: List<String> = emptyList(),
        uuids: List<String> = emptyList(),
        models: List<String> = emptyList(),
        mdnsTypes: List<String> = emptyList(),
        ssdpTypes: List<String> = emptyList(),
        ports: List<Int> = emptyList(),
    ) = LanHostRecord("192.168.1.23").apply {
        this.names += names
        uuids.forEach(::addUuid)
        this.models += models
        this.mdnsTypes += mdnsTypes
        this.ssdpTypes += ssdpTypes
        openPorts += ports
    }

    private fun tokens(r: LanHostRecord, kind: HostKind = HostKind.TV, vendor: String? = null, hash: String = net) =
        DeviceIdentity.tokens(hash, r, kind, vendor)

    @Test
    fun uuidTokenComesFirstAndIsPrimary() {
        val t = tokens(record(names = listOf("Living Room TV"), uuids = listOf(uuidA)))
        assertEquals(2, t.size)
        assertTrue(t[0].startsWith("u"))
        assertTrue(t[1].startsWith("n"))
        assertEquals(t[0], DeviceIdentity.primaryOf(t))
        assertTrue(t.all(DeviceIdentity::isToken))
        assertEquals(17, t[0].length)
        // The same value, hashed the documented way.
        val expected = "u" + NetworkFingerprint.sha256Hex("tunnels.census.v1\n$net\nu\n$uuidA").take(16)
        assertEquals(expected, t[0])
        assertNull(DeviceIdentity.primaryOf(emptyList()))
        assertNull(DeviceIdentity.primaryOf(listOf("not a token")))
    }

    @Test
    fun namesNormaliseCaseSpacesAndConflictSuffix() {
        val plain = tokens(record(names = listOf("Living Room TV")))
        assertEquals(plain, tokens(record(names = listOf("  living   ROOM\ttv "))))
        assertEquals(plain, tokens(record(names = listOf("Living Room TV (2)"))))
        assertEquals(plain, tokens(record(names = listOf("Living Room TV (12)"))))
        // Only one trailing suffix goes, and only when it follows a space.
        assertNotEquals(plain, tokens(record(names = listOf("Living Room TV (2) (3)"))))
        assertNotEquals(plain, tokens(record(names = listOf("Living Room TV(2)"))))
        assertNotEquals(plain, tokens(record(names = listOf("Living Room TV (1234)"))))
        assertEquals("living room tv", DeviceIdentity.normalizeName("Living Room TV (2)"))
        assertNull(DeviceIdentity.normalizeName("   "))
        // Two names give two tokens, sorted, so the order they arrived in does not matter.
        assertEquals(
            tokens(record(names = listOf("Kitchen Speaker", "Bedroom Speaker"))),
            tokens(record(names = listOf("Bedroom Speaker", "Kitchen Speaker"))),
        )
        assertEquals(2, tokens(record(names = listOf("Kitchen Speaker", "Bedroom Speaker"))).size)
        // Duplicates after normalising make one token; an empty name makes none.
        assertEquals(1, tokens(record(names = listOf("Tv", "TV (2)", " "))).size)
    }

    @Test
    fun shapeTokenOnlyWithoutUuidOrName() {
        val shape = tokens(record(models = listOf("BRAVIA 4K"), ssdpTypes = listOf("urn:schemas-upnp-org:device:MediaRenderer:1")), vendor = "Sony")
        assertEquals(1, shape.size)
        assertTrue(shape[0].startsWith("s"))
        val text = DeviceIdentity.shape(HostKind.TV, "Sony", listOf("BRAVIA 4K"), listOf("MediaRenderer"))
        assertEquals("kind=tv;vendor=Sony;models=bravia 4k;services=MediaRenderer", text)
        assertEquals("s" + NetworkFingerprint.sha256Hex("tunnels.census.v1\n$net\ns\n$text").take(16), shape[0])
        // A name or a UUID makes the shape unnecessary.
        assertTrue(tokens(record(names = listOf("tv"), models = listOf("x"))).none { it.startsWith("s") })
        assertTrue(tokens(record(uuids = listOf(uuidA), models = listOf("x"))).none { it.startsWith("s") })
        // Services and models are sorted and lowercased, so arrival order does not matter.
        assertEquals(
            tokens(record(models = listOf("B", "a"), mdnsTypes = listOf("_ipp._tcp", "_http._tcp"))),
            tokens(record(models = listOf("A", "b"), mdnsTypes = listOf("_http._tcp", "_ipp._tcp"))),
        )
    }

    @Test
    fun aShapeThatSaysNothingIsNoIdentity() {
        // Nothing but a kind would make every featureless host one device: acknowledging one would acknowledge them all.
        assertNull(DeviceIdentity.shape(HostKind.UNKNOWN, null, emptyList(), emptyList()))
        assertTrue(tokens(record(), kind = HostKind.UNKNOWN).isEmpty())
        assertTrue(tokens(record(ports = listOf(80, 443)), kind = HostKind.UNKNOWN).isEmpty())
        assertTrue(tokens(record(models = listOf("  "))).isEmpty())
    }

    @Test
    fun portsNeverChangeTheIdentity() {
        val quiet = record(names = listOf("Printer"), models = listOf("LaserJet"))
        val busy = record(names = listOf("Printer"), models = listOf("LaserJet"), ports = listOf(80, 443, 9100))
        assertEquals(tokens(quiet), tokens(busy))
        val shapeQuiet = record(models = listOf("LaserJet"), ssdpTypes = listOf("Printer"))
        val shapeBusy = record(models = listOf("LaserJet"), ssdpTypes = listOf("Printer"), ports = listOf(21, 22, 9100))
        assertEquals(tokens(shapeQuiet), tokens(shapeBusy))
    }

    @Test
    fun tokensDifferPerNetwork() {
        val r = record(names = listOf("Living Room TV"), uuids = listOf(uuidA))
        val home = tokens(r, hash = net)
        val office = tokens(r, hash = otherNet)
        assertEquals(home.size, office.size)
        assertTrue(home.none { it in office })
    }

    @Test
    fun atMostSixTokens() {
        val many = record(
            names = listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7"),
            uuids = listOf(uuidB, uuidA, "bbbbbbbb-1111-2222-3333-444455556666"),
        )
        val t = tokens(many)
        assertEquals(6, t.size)
        assertEquals(2, t.count { it.startsWith("u") })
        assertEquals(4, t.count { it.startsWith("n") })
        assertEquals(t.size, t.toSet().size)
        // The two smallest UUIDs are kept, whatever order they arrived in.
        assertEquals(listOf(uuidA, uuidB), many.ssdpUuids)
        assertEquals(t, tokens(record(names = many.names.toList().reversed(), uuids = listOf(uuidA, "bbbbbbbb-1111-2222-3333-444455556666", uuidB))))
    }

    @Test
    fun tokenNeverContainsTheRawValue() {
        val t = tokens(record(names = listOf("Living Room TV"), uuids = listOf(uuidA)))
        for (token in t) {
            assertFalse(token.contains(uuidA))
            assertFalse(token.lowercase().contains("living"))
            assertFalse(token.contains("room"))
            assertTrue(DeviceIdentity.TOKEN.matches(token))
        }
        assertFalse(DeviceIdentity.isToken("u0123456789abcdef0"))
        assertFalse(DeviceIdentity.isToken("x0123456789abcdef"))
        assertFalse(DeviceIdentity.isToken("u0123456789ABCDEF"))
        assertTrue(DeviceIdentity.isToken("u0123456789abcdef"))
    }

    @Test
    fun uuidOfUsnRejectsJunk() {
        assertEquals(uuidA, Ssdp.uuidOf("uuid:$uuidA::upnp:rootdevice"))
        assertEquals(uuidA, Ssdp.uuidOf("uuid:$uuidA"))
        assertEquals(uuidA, Ssdp.uuidOf("  UUID:${uuidA.uppercase()}::urn:schemas-upnp-org:service:RenderingControl:1 "))
        assertNull(Ssdp.uuidOf(null))
        assertNull(Ssdp.uuidOf(""))
        assertNull(Ssdp.uuidOf("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertNull(Ssdp.uuidOf("uuid:"))
        assertNull(Ssdp.uuidOf("uuid::upnp:rootdevice"))
        assertNull(Ssdp.uuidOf("uuid:abc"))
        assertNull(Ssdp.uuidOf("uuid:" + "a".repeat(65)))
        assertEquals("a".repeat(64), Ssdp.uuidOf("uuid:" + "a".repeat(64)))
        assertNull(Ssdp.uuidOf("uuid:../../../etc/passwd"))
        assertNull(Ssdp.uuidOf("uuid:zzzzzzzz-1111"))
        assertNull(Ssdp.uuidOf("uuid:3f2a9c10 1111 2222"))
        assertNull(Ssdp.uuidOf("prefix uuid:$uuidA"))
    }
}
