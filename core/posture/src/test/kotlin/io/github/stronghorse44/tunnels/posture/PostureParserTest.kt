package io.github.stronghorse44.tunnels.posture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureParserTest {
    // RJ's phone, 2026-10-05 (facts/device.md): the confirmed lines among unrelated keys.
    private val globalFixture = """
        activity_manager_constants=max_cached_processes=32
        airplane_mode_on=0
        always_finish_activities=0
        auto_time=1
        bluetooth_on=1
        settings_reboot_after_timeout=43200000
        wifi_on=2
        zen_mode=0
    """.trimIndent()

    private val secureFixture = """
        accessibility_enabled=0
        always_on_vpn_app=null
        always_on_vpn_lockdown=0
        always_on_vpn_lockdown_whitelist=
        default_input_method=com.example.keyboard/.Service
        lockscreen_scramble_pin_layout_secondary=1
        location_mode=3
    """.trimIndent()

    private val propsFixture = "persist.security.usb_mode="

    private fun read(
        global: String? = globalFixture,
        secure: String? = secureFixture,
        props: String? = propsFixture,
        items: List<PostureItem> = PostureKeys.ITEMS,
    ) = PostureReader.read(
        PostureParser.table(global, PostureKeys.keys(PostureTable.GLOBAL)),
        PostureParser.table(secure, PostureKeys.keys(PostureTable.SECURE)),
        PostureParser.props(props),
        items,
    )

    private fun List<Reading>.of(id: String) = single { it.item.id == id }

    @Test
    fun rjsOutputOf20261005ReadsTheConfirmedKeys() {
        val readings = read()
        val reboot = readings.of(PostureKeys.AUTO_REBOOT)
        assertEquals(PostureState.GOOD, reboot.state)
        assertEquals("12 h", reboot.value)
        val vpn = readings.of(PostureKeys.VPN_ALWAYS_ON)
        assertEquals("none", vpn.value)
        assertEquals(PostureState.NA, vpn.state)
        assertEquals(PostureState.NA, readings.of(PostureKeys.VPN_LOCKDOWN).state)
        val second = readings.of(PostureKeys.PIN_SCRAMBLE_2)
        assertEquals("on", second.value)
        assertEquals(PostureState.NA, second.state)
        val settled = setOf(PostureKeys.AUTO_REBOOT, PostureKeys.VPN_ALWAYS_ON, PostureKeys.VPN_LOCKDOWN, PostureKeys.PIN_SCRAMBLE_2)
        for (r in readings.filter { it.item.id !in settled }) {
            assertEquals(r.item.id, PostureState.UNKNOWN, r.state)
            assertEquals(r.item.id, PostureWhy.ABSENT, r.why)
            assertNull(r.item.id, r.value)
        }
        // Nothing but the allowlist reaches the observations, and nothing from the unrelated lines.
        val obs = PostureObservations.to(
            PostureKeys.TUNNEL_ID, readings,
            mapOf(
                PostureTable.GLOBAL to PostureParser.table(globalFixture, PostureKeys.keys(PostureTable.GLOBAL)),
                PostureTable.SECURE to PostureParser.table(secureFixture, PostureKeys.keys(PostureTable.SECURE)),
                PostureTable.PROPS to PostureParser.props(propsFixture),
            ),
        )
        val ids = PostureKeys.ITEMS.map { it.id }.toSet()
        for (o in obs) {
            assertEquals(PostureKeys.SUBJECT, o.subject)
            val rest = o.key.removePrefix("posture:")
            val id = rest.substringBefore(':')
            assertTrue(o.key, id in ids || id == "read")
        }
        assertTrue(obs.none { "com.example" in it.value || "airplane" in it.key })
        assertEquals(listOf("ok", "ok", "ok"), PostureTable.entries.map { t -> obs.single { it.key == PostureKeys.readKey(t) }.value })
    }

    @Test
    fun rjsStep0OutputAfterFlippingEachToggleReadsTheNewlyConfirmedKeys() {
        // The lines that appeared after RJ changed each setting once (2026-10-05), among keys that must match nothing.
        val global = globalFixture + "\nallow_clipboard_read=0\nprivate_dns_mode=off\ntime_to_full_millis=0"
        val secure = secureFixture + "\nauto_grant_OTHER_SENSORS_perm=0\nclipboard_show_access_notifications=0\n" +
            "lock_screen_lock_after_timeout=15000\nlockscreen_scramble_pin_layout=1"
        val readings = read(global = global, secure = secure)
        fun check(id: String, state: PostureState, value: String) {
            val r = readings.of(id)
            assertEquals(id, state, r.state)
            assertEquals(id, value, r.value)
            assertNull(id, r.why)
        }
        check(PostureKeys.AUTO_REBOOT, PostureState.GOOD, "12 h")
        check(PostureKeys.CLIPBOARD_DEFAULT, PostureState.GOOD, "restricted")
        check(PostureKeys.PRIVATE_DNS, PostureState.WEAK, "off")
        check(PostureKeys.SENSORS_DEFAULT, PostureState.GOOD, "off")
        check(PostureKeys.CLIPBOARD_NOTICES, PostureState.WEAK, "off")
        check(PostureKeys.LOCK_DELAY, PostureState.GOOD, "15 s")
        check(PostureKeys.PIN_SCRAMBLE, PostureState.GOOD, "on")
        // 1 is Allow: weak.
        assertEquals(PostureState.WEAK, read(global = "allow_clipboard_read=1").of(PostureKeys.CLIPBOARD_DEFAULT).state)
        // The USB-C port and the three timers stay unconfirmed and absent; the unrelated battery key matches nothing.
        for (id in listOf(PostureKeys.USB_PORT, PostureKeys.WIFI_AUTO_OFF, PostureKeys.BT_AUTO_OFF, PostureKeys.NFC_AUTO_OFF)) {
            assertEquals(id, PostureWhy.ABSENT, readings.of(id).why)
        }
        val table = PostureParser.table(global, PostureKeys.keys(PostureTable.GLOBAL)) as TableRead.Ok
        assertFalse("time_to_full_millis" in table.values)
    }

    @Test
    fun missingKeyIsUnknownNeverOff() {
        val readings = read(global = "airplane_mode_on=0", secure = "location_mode=3")
        for (r in readings) {
            assertEquals(r.item.id, PostureState.UNKNOWN, r.state)
            assertEquals(r.item.id, PostureWhy.ABSENT, r.why)
        }
    }

    @Test
    fun timedOutTableMakesItsItemsUnreadable() {
        val readings = read(global = "wifi_on=2\n[timed out]")
        for (r in readings.filter { it.item.table == PostureTable.GLOBAL }) {
            assertEquals(r.item.id, PostureState.UNKNOWN, r.state)
            assertEquals(r.item.id, PostureWhy.UNREADABLE, r.why)
        }
        // The other tables are still judged.
        assertEquals(PostureState.NA, readings.of(PostureKeys.VPN_LOCKDOWN).state)
        assertEquals(TableRead.Failed("timed out"), PostureParser.table("settings_reboot_after_timeout=1\n[timed out]"))
    }

    @Test
    fun truncatedOrNonZeroExitIsUnreadable() {
        assertEquals(TableRead.Failed("truncated"), PostureParser.table("settings_reboot_after_timeout=43200000\n[truncated]"))
        assertEquals(TableRead.Failed("exit"), PostureParser.table("settings_reboot_after_timeout=43200000\n[exit 1]"))
        val readings = read(secure = "always_on_vpn_app=null\n[exit 255]")
        assertEquals(PostureWhy.UNREADABLE, readings.of(PostureKeys.VPN_ALWAYS_ON).why)
        assertEquals(PostureWhy.UNREADABLE, readings.of(PostureKeys.PIN_SCRAMBLE_2).why)
        // Partial output is never trusted: the good global table does not rescue the failed one.
        assertEquals(PostureState.GOOD, readings.of(PostureKeys.AUTO_REBOOT).state)
    }

    @Test
    fun errorFirstLineIsUnreadable() {
        for (first in listOf("Error: no such table", "Exception occurred while executing", "java.lang.SecurityException: denied", "Unknown command")) {
            assertEquals(first, TableRead.Failed("error"), PostureParser.table("$first\nsettings_reboot_after_timeout=43200000"))
        }
        assertEquals(TableRead.Failed("error"), PostureParser.table(null))
        assertEquals(TableRead.Failed("error"), PostureParser.table(""))
        assertEquals(TableRead.Failed("error"), PostureParser.table("no pairs here\njust text"))
        assertEquals(TableRead.Failed("error"), PostureParser.props(null))
        assertEquals(TableRead.Failed("error"), PostureParser.props("Error: getprop failed"))
        assertEquals(PostureWhy.UNREADABLE, read(props = null).of(PostureKeys.USB_PORT).why)
    }

    @Test
    fun continuationLineNeverMatchesAKey() {
        val text = "note=first line\n  settings_reboot_after_timeout=0\nwifi_on=2\nsome words settings_reboot_after_timeout=0"
        val table = PostureParser.table(text) as TableRead.Ok
        assertFalse("settings_reboot_after_timeout" in table.values)
        // A value that itself holds an equals sign keeps everything after the first one, under its own key.
        val eq = PostureParser.table("settings_reboot_after_timeout=a=b") as TableRead.Ok
        assertEquals("a=b", eq.values["settings_reboot_after_timeout"])
        assertEquals(PostureWhy.ABSENT, read(global = text).of(PostureKeys.AUTO_REBOOT).why)
    }

    @Test
    fun conflictingDuplicateIsMalformed() {
        val readings = read(global = "settings_reboot_after_timeout=43200000\nsettings_reboot_after_timeout=0")
        assertEquals(PostureWhy.MALFORMED, readings.of(PostureKeys.AUTO_REBOOT).why)
        assertEquals(PostureState.UNKNOWN, readings.of(PostureKeys.AUTO_REBOOT).state)
        assertNull(readings.of(PostureKeys.AUTO_REBOOT).value)
        // The same value twice is not a conflict.
        val same = read(global = "settings_reboot_after_timeout=43200000\nsettings_reboot_after_timeout=43200000")
        assertEquals(PostureState.GOOD, same.of(PostureKeys.AUTO_REBOOT).state)
    }

    @Test
    fun nonNumericTimeoutIsMalformed() {
        for (bad in listOf("soon", "-5", "12.5", "", "1e6", "0x10", "99999999999999999999")) {
            val r = read(global = "settings_reboot_after_timeout=$bad").of(PostureKeys.AUTO_REBOOT)
            assertEquals(bad, PostureState.UNKNOWN, r.state)
            assertEquals(bad, PostureWhy.MALFORMED, r.why)
            assertNull(bad, r.value)
        }
        assertEquals(PostureWhy.MALFORMED, read(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=2").of(PostureKeys.VPN_LOCKDOWN).why)
        assertEquals(PostureWhy.MALFORMED, read(props = "persist.security.usb_mode=9").of(PostureKeys.USB_PORT).why)
        assertEquals(PostureWhy.MALFORMED, read(global = "private_dns_mode=strict").of(PostureKeys.PRIVATE_DNS).why)
    }

    @Test
    fun emptyGetpropIsAbsent() {
        val r = read(props = "persist.security.usb_mode=").of(PostureKeys.USB_PORT)
        assertEquals(PostureState.UNKNOWN, r.state)
        assertEquals(PostureWhy.ABSENT, r.why)
        assertEquals(PostureWhy.ABSENT, read(props = "persist.security.usb_mode=   ").of(PostureKeys.USB_PORT).why)
    }

    @Test
    fun missingGetpropLineIsUnreadable() {
        // Output that is not what the command prints: a line for another name only.
        val r = read(props = "persist.other.thing=1").of(PostureKeys.USB_PORT)
        assertEquals(PostureWhy.UNREADABLE, r.why)
        assertEquals(PostureWhy.UNREADABLE, read(props = "no pairs").of(PostureKeys.USB_PORT).why)
    }

    @Test
    fun exemptListIsStoredAsACountOnly() {
        val secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=com.chat.app,com.mail.client"
        val readings = read(secure = secure)
        val r = readings.of(PostureKeys.VPN_LOCKDOWN)
        assertEquals(PostureState.WEAK, r.state)
        assertEquals("lockdown on, 2 exempt", r.value)
        val obs = PostureObservations.to(
            PostureKeys.TUNNEL_ID, readings,
            mapOf(PostureTable.SECURE to PostureParser.table(secure, PostureKeys.keys(PostureTable.SECURE))),
        )
        assertTrue(obs.none { "com.chat" in it.value || "com.mail" in it.value || "whitelist" in it.key })
        assertEquals("lockdown on, 0 exempt", read(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=")
            .of(PostureKeys.VPN_LOCKDOWN).value)
        // An exempt list with something that is not a package name is not judged.
        assertEquals(
            PostureWhy.MALFORMED,
            read(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=a b;c").of(PostureKeys.VPN_LOCKDOWN).why,
        )
        // Lockdown on with no exempt-list key at all cannot be called good.
        val missing = read(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1").of(PostureKeys.VPN_LOCKDOWN)
        assertEquals(PostureState.UNKNOWN, missing.state)
        assertEquals(PostureWhy.ABSENT, missing.why)
    }

    @Test
    fun propNamesAreShellSafe() {
        val safe = Regex("^[A-Za-z0-9_.]+$")
        assertTrue(PostureKeys.PROPS.isNotEmpty())
        for (name in PostureKeys.PROPS) assertTrue(name, safe.matches(name))
        assertEquals("echo \"persist.security.usb_mode=\$(getprop persist.security.usb_mode)\"", PostureKeys.PROPS_COMMAND)
        assertTrue(PostureKeys.ITEMS.filter { it.table == PostureTable.PROPS }.all { it.key in PostureKeys.PROPS })
    }

    @Test
    fun anUnconfirmedKeyShowsItsValueButIsUnknown() {
        val unconfirmed = PostureKeys.ITEMS.map { if (it.id == PostureKeys.AUTO_REBOOT) it.copy(confirmed = false) else it }
        val r = read(items = unconfirmed).of(PostureKeys.AUTO_REBOOT)
        assertEquals(PostureState.UNKNOWN, r.state)
        assertEquals(PostureWhy.UNCONFIRMED, r.why)
        assertEquals("12 h", r.value)
        // A missing key stays absent, not unconfirmed.
        assertEquals(PostureWhy.ABSENT, read(global = "wifi_on=2", items = unconfirmed).of(PostureKeys.AUTO_REBOOT).why)
    }

    @Test
    fun valuesNormalise() {
        fun reboot(ms: String) = read(global = "settings_reboot_after_timeout=$ms").of(PostureKeys.AUTO_REBOOT)
        assertEquals("off", reboot("0").value)
        assertEquals(PostureState.WEAK, reboot("0").state)
        assertEquals(PostureState.GOOD, reboot("64800000").state)
        assertEquals(PostureState.WEAK, reboot("64800001").state)
        assertEquals("1 h 30 min", reboot("5400000").value)
        assertEquals("15 min", reboot("900000").value)
        val allConfirmed = PostureKeys.ITEMS.map { it.copy(confirmed = true) }
        fun lock(ms: String) = read(secure = "lock_screen_lock_after_timeout=$ms", items = allConfirmed).of(PostureKeys.LOCK_DELAY)
        assertEquals(PostureState.GOOD, lock("5000").state)
        assertEquals("5 s", lock("5000").value)
        assertEquals(PostureState.WEAK, lock("60000").state)
        assertEquals("1 min", lock("60000").value)
        fun usb(m: Int) = read(props = "persist.security.usb_mode=$m", items = allConfirmed).of(PostureKeys.USB_PORT)
        assertEquals(listOf(PostureState.GOOD, PostureState.GOOD, PostureState.GOOD, PostureState.WEAK, PostureState.WEAK), (0..4).map { usb(it).state })
        assertEquals("charging only when locked", usb(2).value)
    }
}
