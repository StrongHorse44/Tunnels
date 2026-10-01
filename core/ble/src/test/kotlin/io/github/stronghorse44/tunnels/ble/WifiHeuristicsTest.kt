package io.github.stronghorse44.tunnels.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiHeuristicsTest {
    private fun ap(ssid: String?, bssid: String, caps: String, freq: Int = 2437, current: Boolean = false) = WifiNetwork(ssid, bssid, caps, freq, current)

    @Test
    fun securityParsing() {
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse("[ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse(""))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse(null))
        assertEquals(WifiSecurity.WEP, WifiSecurity.parse("[WEP][ESS]"))
        assertEquals(WifiSecurity.WPA, WifiSecurity.parse("[WPA-PSK-TKIP][ESS]"))
        assertEquals(WifiSecurity.WPA2, WifiSecurity.parse("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA2, WifiSecurity.parse("[RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.parse("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.parse("[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.ENTERPRISE, WifiSecurity.parse("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(WifiSecurity.OWE, WifiSecurity.parse("[RSN-OWE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.parse("[RSN-OWE_TRANSITION-CCMP][ESS]"))
    }

    @Test
    fun subjectsAndBands() {
        assertEquals("Home", WifiHeuristics.subjectOf("\"Home\""))
        assertEquals("Home", WifiHeuristics.subjectOf("Home"))
        assertEquals(WifiHeuristics.HIDDEN, WifiHeuristics.subjectOf(""))
        assertEquals(WifiHeuristics.HIDDEN, WifiHeuristics.subjectOf(null))
        assertEquals(WifiHeuristics.HIDDEN, WifiHeuristics.subjectOf("<unknown ssid>"))
        assertEquals("2.4GHz", WifiHeuristics.band(2412))
        assertEquals("5GHz", WifiHeuristics.band(5180))
        assertEquals("6GHz", WifiHeuristics.band(5955))
        assertEquals("?", WifiHeuristics.band(0))
        assertEquals("aa:bb:cc", WifiHeuristics.oui("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun ordinaryNetworksAreNotTwins() {
        val home = listOf(
            ap("Home", "aa:bb:cc:00:00:01", "[WPA2-PSK-CCMP][ESS]", 2437, current = true),
            ap("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", 5180),
            // WPA2/WPA3 transition on one mesh node is the same family, not a twin.
            ap("Home", "aa:bb:cc:00:00:03", "[WPA2-PSK+SAE-CCMP][ESS]", 5180),
        )
        val s = WifiHeuristics.summarise(home).single()
        assertEquals("Home", s.subject)
        assertNull(s.twinSuspect)
        assertEquals(3, s.bssids)
        assertEquals(setOf("2.4GHz", "5GHz"), s.bands)
        assertTrue(s.current)
        assertEquals(WifiSecurity.WPA2, s.security)
    }

    @Test
    fun openCopyOfSecuredNetwork() {
        val s = WifiHeuristics.summarise(
            listOf(ap("Cafe", "aa:bb:cc:00:00:01", "[WPA2-PSK-CCMP][ESS]"), ap("Cafe", "11:22:33:00:00:01", "[ESS]")),
        ).single()
        assertNotNull(s.twinSuspect)
        assertTrue(s.twinSuspect!!, s.twinSuspect!!.contains("open"))
        assertEquals(WifiSecurity.OPEN, s.security) // the weakest wins so the open impostor is visible
    }

    @Test
    fun mixedSecurityFamilies() {
        val s = WifiHeuristics.summarise(
            listOf(ap("Office", "aa:bb:cc:00:00:01", "[WPA2-EAP-CCMP][ESS]"), ap("Office", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]")),
        ).single()
        assertNotNull(s.twinSuspect)
        assertTrue(s.twinSuspect!!.contains("different security"))
        assertTrue(s.twinSuspect!!.contains("wpa2/enterprise"))
    }

    @Test
    fun tooManyVendorsForOneName() {
        val fiveVendors = (1..5).map { ap("Guest", "0$it:00:00:00:00:0$it", "[WPA2-PSK-CCMP][ESS]") }
        assertNotNull(WifiHeuristics.summarise(fiveVendors).single().twinSuspect)
        val fourVendors = fiveVendors.take(4)
        assertNull(WifiHeuristics.summarise(fourVendors).single().twinSuspect)
        // Many access points from one vendor (a mesh, a campus) are fine.
        val mesh = (1..9).map { ap("Campus", "aa:bb:cc:00:00:0$it", "[WPA2-PSK-CCMP][ESS]") }
        assertNull(WifiHeuristics.summarise(mesh).single().twinSuspect)
    }

    private val eap = "[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"

    @Test
    fun carrierNetworkWithManyVendorsIsNormal() {
        // The field case: six access points from six vendors, all Passpoint/802.1X.
        val spectrum = (1..6).map { ap("Spectrum Mobile", "0$it:1$it:2$it:00:00:01", eap) }
        assertNull(WifiHeuristics.summarise(spectrum).single().twinSuspect)
        // Case-insensitive, exact name only.
        assertNull(WifiHeuristics.summarise(spectrum.map { it.copy(ssid = "SPECTRUM MOBILE") }).single().twinSuspect)
        assertNotNull(WifiHeuristics.summarise(spectrum.map { it.copy(ssid = "Spectrum Mobile 2") }).single().twinSuspect)
        assertEquals(WifiHeuristics.HotspotSecurity.ENTERPRISE, WifiHeuristics.carrierHotspot("spectrum mobile"))
        assertEquals(WifiHeuristics.HotspotSecurity.OPEN, WifiHeuristics.carrierHotspot("XFINITYWIFI"))
        assertNull(WifiHeuristics.carrierHotspot("Home"))
        // An open carrier hotspot from many vendors is normal too, with or without enhanced-open neighbours.
        val xfinity = (1..6).map { ap("xfinitywifi", "0$it:1$it:2$it:00:00:01", "[ESS]") } + ap("xfinitywifi", "aa:bb:cc:00:00:09", "[RSN-OWE-CCMP][ESS]")
        assertNull(WifiHeuristics.summarise(xfinity).single().twinSuspect)
    }

    @Test
    fun carrierNetworkWithAnOpenCopyIsFlagged() {
        val spectrum = (1..6).map { ap("Spectrum Mobile", "0$it:1$it:2$it:00:00:01", eap) } + ap("Spectrum Mobile", "de:ad:be:00:00:01", "[ESS]")
        assertEquals(
            "a public hotspot name broadcast from many access points; one of them advertises open security while others use enterprise sign-in",
            WifiHeuristics.summarise(spectrum).single().twinSuspect,
        )
        // An open copy of an 802.1X name is flagged even alone.
        assertEquals(
            "a public hotspot name broadcast from many access points, normally with enterprise sign-in; one of them advertises open security",
            WifiHeuristics.summarise(listOf(ap("eduroam", "de:ad:be:00:00:01", "[ESS]"))).single().twinSuspect,
        )
        // A password copy of an 802.1X name: flagged only next to an enterprise sibling in range.
        assertEquals(
            "a public hotspot name broadcast from many access points; one of them advertises a Wi-Fi password (WPA2-Personal) while others use enterprise sign-in",
            WifiHeuristics.summarise(listOf(ap("eduroam", "01:00:00:00:00:01", eap), ap("eduroam", "de:ad:be:00:00:01", "[WPA2-PSK-CCMP][ESS]"))).single().twinSuspect,
        )
        // A home network literally named "Xfinity" (password only, several mesh vendors) is not an impostor of anything.
        assertNull(WifiHeuristics.summarise((1..5).map { ap("Xfinity", "0$it:00:00:00:00:0$it", "[WPA2-PSK-CCMP][ESS]") }).single().twinSuspect)
        // A password-protected AP among open hotspots.
        assertEquals(
            "a public hotspot name broadcast from many access points; one of them advertises a Wi-Fi password (WPA2-Personal) while others are open",
            WifiHeuristics.summarise(listOf(ap("attwifi", "01:00:00:00:00:01", "[ESS]"), ap("attwifi", "de:ad:be:00:00:01", "[WPA2-PSK-CCMP][ESS]"))).single().twinSuspect,
        )
    }

    @Test
    fun nonCarrierHomeNetworkKeepsTheVendorRule() {
        val home = (1..5).map { ap("MyHome", "0$it:00:00:00:00:0$it", "[WPA2-PSK-CCMP][ESS]") }
        assertEquals("5 access points from different vendors share this name", WifiHeuristics.summarise(home).single().twinSuspect)
        assertEquals(
            "an open network uses the same name as a secured one",
            WifiHeuristics.summarise(home + ap("MyHome", "de:ad:be:00:00:01", "[ESS]")).single().twinSuspect,
        )
    }

    @Test
    fun hiddenNetworksAreNeverSuspects() {
        val hidden = listOf(ap("", "aa:bb:cc:00:00:01", "[ESS]"), ap(null, "11:22:33:00:00:01", "[WPA2-PSK-CCMP][ESS]"))
        val s = WifiHeuristics.summarise(hidden).single()
        assertEquals(WifiHeuristics.HIDDEN, s.subject)
        assertNull(s.twinSuspect)
        assertEquals(2, s.bssids)
    }

    @Test
    fun observationsListCurrentAndSuspectsFirstAndStayBounded() {
        val many = (0 until 80).map { ap("Net%03d".format(it), "aa:bb:cc:00:%02x:01".format(it), "[WPA2-PSK-CCMP][ESS]") } +
            ap("Zzz", "aa:bb:cc:ff:ff:01", "[WPA2-PSK-CCMP][ESS]", current = true) +
            ap("Yyy", "aa:bb:cc:ff:fe:01", "[WPA2-PSK-CCMP][ESS]") + ap("Yyy", "11:22:33:ff:fe:01", "[ESS]")
        val obs = SurroundingsKeys.wifiObservations(WifiHeuristics.summarise(many), SurroundingsKeys.AVAILABLE_YES)
        assertEquals(obs.size, obs.map { it.identity }.toSet().size)
        assertEquals("82", SurroundingsKeys.value(obs.filter { it.subject == SurroundingsKeys.WIFI_SUMMARY }, SurroundingsKeys.WIFI_NETWORKS))
        val subjects = obs.map { it.subject }.toSet() - SurroundingsKeys.WIFI_SUMMARY
        assertEquals(SurroundingsKeys.MAX_LISTED_NETWORKS, subjects.size)
        assertTrue("Zzz" in subjects)
        assertTrue("Yyy" in subjects)
        assertEquals("true", obs.first { it.subject == "Zzz" && it.key == SurroundingsKeys.WIFI_CURRENT }.value)
        assertNotNull(obs.firstOrNull { it.subject == "Yyy" && it.key == SurroundingsKeys.WIFI_TWIN })
        assertFalse(obs.any { it.key == SurroundingsKeys.WIFI_BSSIDS && it.value.contains(':') })
    }
}
