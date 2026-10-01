package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class HostKindsTest {
    private fun kind(
        types: Set<String> = emptySet(),
        names: List<String> = emptyList(),
        server: String? = null,
        ssdp: Set<String> = emptySet(),
        ports: Set<Int> = emptySet(),
        gateway: Boolean = false,
        models: List<String> = emptyList(),
    ) = HostKinds.infer(HostEvidence(types, names, server, ssdp, ports, gateway, models))

    @Test
    fun gatewayAlwaysWins() {
        assertEquals(HostKind.ROUTER, kind(types = setOf("_ipp._tcp"), gateway = true))
        assertEquals(HostKind.ROUTER, kind(server = "FRITZ!Box 7590 UPnP/1.0 AVM"))
    }

    @Test
    fun servicesPickTheKind() {
        assertEquals(HostKind.PRINTER, kind(types = setOf("_ipp._tcp.local.", "_http._tcp")))
        assertEquals(HostKind.PRINTER, kind(ports = setOf(9100, 80)))
        assertEquals(HostKind.TV, kind(types = setOf("_googlecast._tcp")))
        assertEquals(HostKind.TV, kind(types = setOf("_airplay._tcp", "_raop._tcp")))
        assertEquals(HostKind.SPEAKER, kind(types = setOf("_raop._tcp")))
        assertEquals(HostKind.SPEAKER, kind(types = setOf("_spotify-connect._tcp", "_http._tcp")))
        assertEquals(HostKind.SPEAKER, kind(server = "Linux UPnP/1.0 Sonos/70.3"))
        assertEquals(HostKind.NAS, kind(types = setOf("_smb._tcp", "_http._tcp")))
        assertEquals(HostKind.NAS, kind(names = listOf("DiskStation")))
        assertEquals(HostKind.COMPUTER, kind(types = setOf("_smb._tcp", "_workstation._tcp")))
        assertEquals(HostKind.COMPUTER, kind(types = setOf("_smb._tcp"), names = listOf("Ana's MacBook Pro")))
        assertEquals(HostKind.COMPUTER, kind(types = setOf("_ssh._tcp")))
        assertEquals(HostKind.COMPUTER, kind(ports = setOf(3389, 445)))
        assertEquals(HostKind.IOT, kind(types = setOf("_hap._tcp")))
        assertEquals(HostKind.IOT, kind(ports = setOf(1883)))
        assertEquals(HostKind.IOT, kind(names = listOf("shellyplug-s-1234")))
        assertEquals(HostKind.PHONE, kind(names = listOf("Pixel 10")))
        assertEquals(HostKind.PHONE, kind(names = listOf("Ana's iPhone"), types = setOf("_device-info._tcp")))
        assertEquals(HostKind.CAMERA, kind(ports = setOf(554, 80)))
        assertEquals(HostKind.CAMERA, kind(names = listOf("Front door cam")))
        assertEquals(HostKind.CAMERA, kind(server = "Hikvision-Webs"))
        assertEquals(HostKind.TV, kind(ssdp = setOf("urn:schemas-upnp-org:device:MediaRenderer:1"), server = "Linux/4.9 UPnP/1.0 LG WebOS TV"))
        assertEquals(HostKind.UNKNOWN, kind(types = setOf("_http._tcp")))
        assertEquals(HostKind.UNKNOWN, kind())
    }

    @Test
    fun wordMatchingDoesNotMisfireInsideWords() {
        // "cam" inside "Camilla" must not make a laptop a camera; "tv" inside "tvorog" is not a TV.
        assertEquals(HostKind.COMPUTER, kind(names = listOf("camillas-laptop")))
        assertEquals(HostKind.UNKNOWN, kind(names = listOf("tvorog")))
        assertEquals(HostKind.TV, kind(names = listOf("Living room TV")))
    }

    @Test
    fun labelsRoundTrip() {
        HostKind.entries.forEach { assertEquals(it, HostKind.byLabel(it.label)) }
        assertEquals(HostKind.UNKNOWN, HostKind.byLabel("toaster"))
        assertEquals(HostKind.UNKNOWN, HostKind.byLabel(null))
    }
}
