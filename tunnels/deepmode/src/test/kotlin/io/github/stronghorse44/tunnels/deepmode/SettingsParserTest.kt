package io.github.stronghorse44.tunnels.deepmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsParserTest {
    @Test
    fun keepsOnlyWatchedKeys() {
        val text = """
            adb_enabled=1
            adb_wifi_enabled=0
            wifi_scan_always_enabled=1
            private_dns_mode=hostname
            private_dns_specifier=dns.example.net
            wifi_p2p_device_name=Pixel of Someone
            airplane_mode_on=0
            broken line without equals
            =novalue
        """.trimIndent()
        val map = SettingsParser.parseList(text)
        assertEquals("1", map["adb_enabled"])
        assertEquals("0", map["adb_wifi_enabled"])
        assertEquals("hostname", map["private_dns_mode"])
        assertFalse("the DNS host name is not on the allowlist", map.containsKey("private_dns_specifier"))
        assertFalse("device names stay out of the store", map.containsKey("wifi_p2p_device_name"))
        assertEquals(5, map.size)
    }

    @Test
    fun keepsEqualsInsideValuesAndCapsLength() {
        val long = "x".repeat(DeepKeys.MAX_SETTING_VALUE + 50)
        val map = SettingsParser.parseList("default_input_method=com.a/.Ime=odd\nenabled_accessibility_services=$long")
        assertEquals("com.a/.Ime=odd", map["default_input_method"])
        assertEquals(DeepKeys.MAX_SETTING_VALUE, map.getValue("enabled_accessibility_services").length)
        assertTrue(map.getValue("enabled_accessibility_services").endsWith("…"))
    }

    @Test
    fun countsDisabledPackages() {
        val text = "package:com.a\npackage:com.b\n\nWarning: something\npackage:com.c"
        assertEquals(3, SettingsParser.countPackages(text))
        assertEquals(listOf("com.a", "com.b", "com.c"), SettingsParser.packages(text))
        assertEquals(0, SettingsParser.countPackages(""))
    }

    @Test
    fun shortensAccessibilityComponentNames() {
        val value = "com.example.reader/com.example.reader.ScreenService:org.other/org.other.svc.Helper:plain"
        assertEquals(
            listOf("com.example.reader/.ScreenService", "org.other/.svc.Helper", "plain"),
            SettingsParser.accessibilityServices(value),
        )
        assertTrue(SettingsParser.accessibilityServices("null").isEmpty())
        assertTrue(SettingsParser.accessibilityServices("").isEmpty())
        assertTrue(SettingsParser.accessibilityServices(null).isEmpty())
    }

    @Test
    fun coarseAgesAreDayGranular() {
        assertEquals("today", DeepKeys.coarseAge(5_000L))
        assertEquals("today", DeepKeys.coarseAge(23 * 3_600_000L))
        assertEquals("1 day ago", DeepKeys.coarseAge(30 * 3_600_000L))
        assertEquals("12 days ago", DeepKeys.coarseAge(12 * 86_400_000L + 1))
        assertEquals("30+ days", DeepKeys.coarseAge(45 * 86_400_000L))
        assertEquals("never", DeepKeys.coarseAge(null))
        assertEquals(0, DeepKeys.ageDays("today"))
        assertEquals(1, DeepKeys.ageDays("1 day ago"))
        assertEquals(12, DeepKeys.ageDays("12 days ago"))
        assertNull(DeepKeys.ageDays("30+ days"))
        assertNull(DeepKeys.ageDays("never"))
        assertNull(DeepKeys.ageDays(null))
    }

    @Test
    fun opKeysRoundTrip() {
        assertEquals("CAMERA", DeepKeys.opOf(DeepKeys.modeKey("CAMERA")))
        assertEquals("READ_CLIPBOARD", DeepKeys.opOf(DeepKeys.bgLastKey("READ_CLIPBOARD")))
        assertNull(DeepKeys.opOf("app:label"))
        assertTrue(DeepKeys.isModeKey("ops:CAMERA:mode"))
        assertFalse(DeepKeys.isModeKey("ops:CAMERA:last"))
        assertEquals("camera, microphone and precise location", DeepKeys.joinLabels(listOf("camera", "microphone", "precise location")))
    }
}
