package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mine by address: a quiet device goes on the list by its address while it stays quiet. */
class MineByAddressTest {
    private val net = NetworkFingerprint.sha256Hex("net:home")

    @Test
    fun onlyAQuietDeviceOnACompleteScanGetsAnAddressToken() {
        val quietHost = LanHostRecord("192.168.1.250").apply { swept = true; sweepAnswer = "80/refused" }
        val ids = DeviceIdentity.tokens(net, quietHost, HostKind.UNKNOWN, null, quietAddress = true)
        assertEquals(1, ids.size)
        assertTrue(DeviceIdentity.isAddressToken(ids[0]) && DeviceKeys.isKey(ids[0]))
        // The same address on the next scan gives the same token; another address another one; another network another one.
        assertEquals(ids, DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250"), HostKind.UNKNOWN, null, quietAddress = true))
        assertTrue(DeviceIdentity.tokens(net, LanHostRecord("192.168.1.184"), HostKind.UNKNOWN, null, quietAddress = true) != ids)
        assertTrue(DeviceIdentity.tokens(NetworkFingerprint.sha256Hex("net:office"), quietHost, HostKind.UNKNOWN, null, quietAddress = true) != ids)
        // Not when the scan was cut short, and never for a device that opens a door, announces itself or gives a name.
        assertTrue(DeviceIdentity.tokens(net, quietHost, HostKind.UNKNOWN, null, quietAddress = false).isEmpty())
        assertTrue(DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250").apply { openPorts += 22 }, HostKind.UNKNOWN, null, true).isEmpty())
        assertTrue(DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250").apply { mdnsTypes += "_http._tcp" }, HostKind.UNKNOWN, null, true).none(DeviceIdentity::isAddressToken))
        val named = DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250").apply { routerName = "lab-server" }, HostKind.UNKNOWN, null, true)
        assertEquals(listOf('h'), named.map { it[0] })
    }

    @Test
    fun theFindingAndTheButtonSayByAddress() {
        val token = DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250"), HostKind.UNKNOWN, null, quietAddress = true).single()
        fun o(subject: String, key: String, value: String) = Observation(LanKeys.TUNNEL_ID, subject, key, value)
        val current = listOf(
            o(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED),
            o(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, DeviceCensus.STATE_SET),
            o(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, "n" + "0".repeat(16)),
            o("192.168.1.250", LanKeys.HOST_KIND, HostKind.UNKNOWN.label),
            o("192.168.1.250", LanKeys.HOST_IDS, token),
        )
        val draft = LanRules.unknownDevice.evaluate(RuleContext(LanKeys.TUNNEL_ID, current, emptyList(), isFirstScan = false)).single()
        assertEquals("Unnamed device · $token", draft.subject)
        assertEquals(token, DeviceCensus.parsePrimary(draft.subject))
        assertTrue(draft.evidence.contains("Mine adds it by its address") && draft.evidence.contains("Reserve the address"))
        assertEquals(LanGuides.MINE_BY_ADDRESS_LABEL, LanGuides.mineLabel(token))
        assertEquals(LanGuides.MINE_LABEL, LanGuides.mineLabel("n" + "0".repeat(16)))
        // Once on the list, it is listed while the same quiet device is at the address.
        val listed = current.filterNot { it.key == LanKeys.CENSUS_KNOWN } + o(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, token)
        assertTrue(LanRules.unknownDevice.evaluate(RuleContext(LanKeys.TUNNEL_ID, listed, emptyList(), isFirstScan = false)).isEmpty())
        assertTrue(LanSummary.from(listed).hosts.single().listed == true)
    }

    @Test
    fun notesKeptByAddressMoveToTheAddressToken() {
        val token = DeviceIdentity.tokens(net, LanHostRecord("192.168.1.250"), HostKind.UNKNOWN, null, quietAddress = true).single()
        val moved = DeviceKeys.rekey(mapOf("ip:192.168.1.250" to "Lab server"), listOf(DeviceKeys.Host(listOf(token), "192.168.1.250")))
        assertEquals(mapOf(token to "Lab server"), moved)
        assertFalse(DeviceIdentity.isAddressToken("ip:192.168.1.250"))
    }
}
