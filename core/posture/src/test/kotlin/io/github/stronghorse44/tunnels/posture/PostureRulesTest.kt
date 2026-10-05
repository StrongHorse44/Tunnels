package io.github.stronghorse44.tunnels.posture

import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PostureRulesTest {
    private val tunnel = PostureKeys.TUNNEL_ID

    /** Every item confirmed, so a test can exercise each item's rule; the shipped flags are tested on their own. */
    private val confirmed = PostureKeys.ITEMS.map { it.copy(confirmed = true) }

    private fun reads(ok: Boolean = true): Map<PostureTable, TableRead> =
        PostureTable.entries.associateWith { if (ok) TableRead.Ok(emptyMap()) else PostureParser.notRun }

    /** Observations as a scan would store them, from raw table text. */
    private fun scan(
        global: String = "wifi_on=2",
        secure: String = "location_mode=3",
        props: String = "persist.security.usb_mode=",
        items: List<PostureItem> = confirmed,
    ): List<Observation> {
        val g = PostureParser.table(global, PostureKeys.keys(PostureTable.GLOBAL))
        val s = PostureParser.table(secure, PostureKeys.keys(PostureTable.SECURE))
        val p = PostureParser.props(props)
        return PostureObservations.to(
            tunnel, PostureReader.read(g, s, p, items),
            mapOf(PostureTable.GLOBAL to g, PostureTable.SECURE to s, PostureTable.PROPS to p),
        )
    }

    private fun drafts(obs: List<Observation>, items: List<PostureItem> = confirmed): List<FindingDraft> {
        val ctx = RuleContext(tunnel, obs, emptyList(), isFirstScan = true)
        return PostureRules.rulesFor(items).flatMap { it.evaluate(ctx) }
    }

    private fun draft(kind: String, obs: List<Observation>) = drafts(obs).singleOrNull { it.kind == kind }

    @Test
    fun autoRebootOffWarnsAndLongNotices() {
        val off = draft("POSTURE_AUTO_REBOOT", scan(global = "settings_reboot_after_timeout=0"))!!
        assertEquals(Severity.WARN, off.severity)
        assertEquals(PostureKeys.SUBJECT, off.subject)
        assertFalse(off.sticky)
        assertEquals(
            "Auto reboot is off. A locked phone stays in the after-first-unlock state, with more of its data decryptable, " +
                "until someone restarts it. Security & privacy > Exploit protection > Auto reboot.",
            off.evidence,
        )
        val long = draft("POSTURE_AUTO_REBOOT", scan(global = "settings_reboot_after_timeout=86400000"))!!
        assertEquals(Severity.NOTICE, long.severity)
        assertEquals("Auto reboot waits 24 h, longer than GrapheneOS's 18 h default. Security & privacy > Exploit protection > Auto reboot.", long.evidence)
    }

    @Test
    fun twelveHoursIsGood() {
        assertEquals(emptyList<FindingDraft>(), drafts(scan(global = "settings_reboot_after_timeout=43200000")).filter { it.kind == "POSTURE_AUTO_REBOOT" })
        assertTrue(drafts(scan(global = "settings_reboot_after_timeout=64800000")).none { it.kind == "POSTURE_AUTO_REBOOT" })
    }

    @Test
    fun usbModesMapToStates() {
        fun d(mode: Int) = draft("POSTURE_USB_PORT", scan(props = "persist.security.usb_mode=$mode"))
        assertTrue((0..2).all { d(it) == null })
        assertEquals(Severity.NOTICE, d(3)!!.severity)
        assertEquals(
            "The USB-C port accepts new data connections before the first unlock after a restart. Security & privacy > Exploit protection > USB-C port.",
            d(3)!!.evidence,
        )
        assertEquals(Severity.WARN, d(4)!!.severity)
        assertEquals("The USB-C port accepts data while the phone is locked. Security & privacy > Exploit protection > USB-C port.", d(4)!!.evidence)
    }

    @Test
    fun vpnWithoutLockdownNotices() {
        val obs = scan(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=0\nalways_on_vpn_lockdown_whitelist=")
        val d = draft("POSTURE_VPN_LOCKDOWN", obs)!!
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals(
            "Always-on VPN (org.vpn.client) runs without Block connections without VPN, so apps can reach the network outside it while it " +
                "starts or reconnects. Network & internet > VPN > the VPN's gear.",
            d.evidence,
        )
        // Lockdown on with nothing exempt is fine.
        assertEquals(null, draft("POSTURE_VPN_LOCKDOWN", scan(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=")))
        // No VPN at all: nothing to lock down.
        assertEquals(null, draft("POSTURE_VPN_LOCKDOWN", scan(secure = "always_on_vpn_app=null\nalways_on_vpn_lockdown=0\nalways_on_vpn_lockdown_whitelist=")))
    }

    @Test
    fun lockdownExemptionsAreCounted() {
        fun d(list: String) = draft(
            "POSTURE_VPN_LOCKDOWN",
            scan(secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=$list"),
        )
        assertEquals("2 apps may connect outside the always-on VPN lockdown. Network & internet > VPN > the VPN's gear.", d("com.chat.app,com.mail.client")!!.evidence)
        assertEquals("1 app may connect outside the always-on VPN lockdown. Network & internet > VPN > the VPN's gear.", d("com.chat.app")!!.evidence)
        assertFalse("com.chat" in d("com.chat.app,com.mail.client")!!.evidence)
    }

    @Test
    fun noVpnFollowsQ1() {
        val obs = scan(secure = "always_on_vpn_app=null\nalways_on_vpn_lockdown=0\nalways_on_vpn_lockdown_whitelist=")
        assertTrue(drafts(obs).none { it.subject == PostureKeys.SUBJECT && "VPN" in it.kind })
        assertFalse("POSTURE_NO_ALWAYS_ON_VPN" in PostureRules.kinds)
        assertEquals(null, PostureKeys.item(PostureKeys.VPN_ALWAYS_ON)!!.kind)
        // The panel still has it.
        val vpn = PostureObservations.from(obs, confirmed).reading(PostureKeys.VPN_ALWAYS_ON)!!
        assertEquals("none", vpn.value)
    }

    @Test
    fun privateDnsOnlyOffIsWeak() {
        fun d(mode: String) = draft("POSTURE_PRIVATE_DNS", scan(global = "private_dns_mode=$mode"))
        assertEquals(null, d("opportunistic"))
        assertEquals(null, d("hostname"))
        val off = d("off")!!
        assertEquals(Severity.NOTICE, off.severity)
        assertEquals(
            "Private DNS is off: every lookup goes unencrypted to the network's resolver. Automatic keeps Traffic sessions working. Network & internet > Private DNS.",
            off.evidence,
        )
        val readings = PostureObservations.from(scan(global = "private_dns_mode=hostname"), confirmed)
        assertEquals("provider", readings.reading(PostureKeys.PRIVATE_DNS)!!.value)
    }

    @Test
    fun weakItemsRaiseTheSpecsSeverities() {
        val obs = scan(
            global = "private_dns_mode=off\nallow_clipboard_read=1\nwifi_off_timeout=0\nbluetooth_off_timeout=0\nnfc_off_timeout=0",
            secure = "lockscreen_scramble_pin_layout=0\nclipboard_show_access_notifications=0\nauto_grant_OTHER_SENSORS_perm=1\n" +
                "lock_screen_lock_after_timeout=300000",
        )
        val bySeverity = drafts(obs).associate { it.kind to it.severity }
        assertEquals(Severity.NOTICE, bySeverity["POSTURE_PIN_SCRAMBLE"])
        assertEquals(Severity.NOTICE, bySeverity["POSTURE_CLIPBOARD_DEFAULT"])
        assertEquals(Severity.NOTICE, bySeverity["POSTURE_CLIPBOARD_NOTICES"])
        assertEquals(Severity.INFO, bySeverity["POSTURE_WIFI_AUTO_OFF"])
        assertEquals(Severity.INFO, bySeverity["POSTURE_BT_AUTO_OFF"])
        assertEquals(Severity.INFO, bySeverity["POSTURE_NFC_AUTO_OFF"])
        assertEquals(Severity.INFO, bySeverity["POSTURE_SENSORS_DEFAULT"])
        assertEquals(Severity.INFO, bySeverity["POSTURE_LOCK_DELAY"])
        assertEquals(
            "The phone locks 5 min after the screen turns off; whoever picks it up in that time gets in without the PIN. Device unlock > Screen lock (gear).",
            drafts(obs).single { it.kind == "POSTURE_LOCK_DELAY" }.evidence,
        )
    }

    @Test
    fun unknownNeverProducesAFinding() {
        // Nothing in either table, a failed table, malformed values: none of it is weak.
        assertTrue(drafts(scan()).isEmpty())
        val malformed = scan(
            global = "settings_reboot_after_timeout=soon\nprivate_dns_mode=strict\nwifi_off_timeout=-1",
            secure = "lockscreen_scramble_pin_layout=maybe\nauto_grant_OTHER_SENSORS_perm=2",
            props = "persist.security.usb_mode=7",
        )
        assertTrue(drafts(malformed).isEmpty())
        val unreadable = scan(global = "[timed out]", secure = "Error: nope", props = "[exit 1]")
        assertTrue(drafts(unreadable).all { it.kind == PostureRules.POSTURE_UNREAD })
        assertTrue(drafts(emptyList()).isEmpty())
    }

    @Test
    fun unconfirmedKeyNeverProducesAFinding() {
        // As shipped: only the four confirmed items can speak, and the rest read but stay unknown.
        val shipped = PostureKeys.ITEMS
        val weakEverywhere = scan(
            global = "settings_reboot_after_timeout=0\nprivate_dns_mode=off\nallow_clipboard_read=1\nwifi_off_timeout=0\n" +
                "bluetooth_off_timeout=0\nnfc_off_timeout=0",
            secure = "lockscreen_scramble_pin_layout=0\nclipboard_show_access_notifications=0\nauto_grant_OTHER_SENSORS_perm=1\n" +
                "lock_screen_lock_after_timeout=300000",
            props = "persist.security.usb_mode=4",
            items = shipped,
        )
        val kinds = drafts(weakEverywhere, shipped).map { it.kind }
        assertEquals(listOf("POSTURE_AUTO_REBOOT"), kinds)
        val readings = PostureObservations.from(weakEverywhere, shipped)
        assertEquals(PostureWhy.UNCONFIRMED, readings.reading(PostureKeys.USB_PORT)!!.why)
        assertEquals("on", readings.reading(PostureKeys.USB_PORT)!!.value)
        assertEquals(PostureState.UNKNOWN, readings.reading(PostureKeys.WIFI_AUTO_OFF)!!.state)
        // Even a stored weak state does not raise a finding once the item's flag is off.
        val stored = scan(global = "wifi_off_timeout=0", items = confirmed)
        assertTrue(drafts(stored, shipped).none { it.kind == "POSTURE_WIFI_AUTO_OFF" })
        assertTrue(drafts(stored, confirmed).any { it.kind == "POSTURE_WIFI_AUTO_OFF" })
    }

    @Test
    fun shippedConfirmationFlags() {
        val flags = PostureKeys.ITEMS.associate { it.id to it.confirmed }
        assertEquals(
            setOf(PostureKeys.AUTO_REBOOT, PostureKeys.VPN_ALWAYS_ON, PostureKeys.VPN_LOCKDOWN, PostureKeys.PIN_SCRAMBLE_2),
            flags.filterValues { it }.keys,
        )
        assertEquals(14, flags.size)
        // A confirmed item that can raise a finding must end its evidence with a Settings path.
        for (item in PostureKeys.ITEMS.filter { it.confirmed && it.kind != null }) assertTrue(item.id, item.path.isNotBlank())
    }

    @Test
    fun unreadableTableRaisesPostureUnread() {
        val obs = scan(global = "wifi_on=2\n[timed out]")
        val d = drafts(obs).single { it.kind == PostureRules.POSTURE_UNREAD }
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals(
            "Posture could not read global settings this scan (timed out); 6 items are unknown and their findings are hidden until the next full read.",
            d.evidence,
        )
        val two = drafts(scan(global = "[truncated]", props = "[timed out]")).single { it.kind == PostureRules.POSTURE_UNREAD }
        assertTrue(two.evidence, two.evidence.startsWith("Posture could not read global settings, system properties this scan (truncated, timed out); 7 items"))
        // A clean scan raises none, and a failed command does not hide the other tables' verdicts.
        assertTrue(drafts(scan()).none { it.kind == PostureRules.POSTURE_UNREAD })
        val mixed = drafts(scan(global = "[timed out]", secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=0\nalways_on_vpn_lockdown_whitelist="))
        assertTrue(mixed.any { it.kind == "POSTURE_VPN_LOCKDOWN" })
    }

    @Test
    fun everyKindButUnreadHasASettingsAction() {
        for (kind in PostureRules.kinds - PostureRules.POSTURE_UNREAD) {
            val actions = PostureRules.actionsFor(FindingDraft(tunnel, PostureKeys.SUBJECT, kind, Severity.INFO, "x"))
            val a = actions.single() as FindingAction.OpenSettings
            assertTrue(kind, a.action.startsWith("android.settings."))
            assertTrue(kind, a.label.isNotBlank())
        }
        assertEquals(emptyList<FindingAction>(), PostureRules.actionsFor(FindingDraft(tunnel, PostureKeys.SUBJECT, PostureRules.POSTURE_UNREAD, Severity.NOTICE, "x")))
        assertEquals(
            FindingAction.OpenSettings("android.settings.SECURITY_SETTINGS", "Security settings"),
            PostureRules.actionsFor(FindingDraft(tunnel, PostureKeys.SUBJECT, "POSTURE_AUTO_REBOOT", Severity.WARN, "x")).single(),
        )
        assertEquals(13, PostureRules.kinds.size) // 12 item kinds plus POSTURE_UNREAD
    }

    @Test
    fun observationsRoundTrip() {
        val g = PostureParser.table("settings_reboot_after_timeout=43200000\nprivate_dns_mode=hostname\nwifi_off_timeout=bad", PostureKeys.keys(PostureTable.GLOBAL))
        val s = PostureParser.table(
            "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=1\nalways_on_vpn_lockdown_whitelist=a.b\nlockscreen_scramble_pin_layout_secondary=0",
            PostureKeys.keys(PostureTable.SECURE),
        )
        val p = PostureParser.props("[timed out]")
        val readings = PostureReader.read(g, s, p, confirmed)
        val reads = mapOf(PostureTable.GLOBAL to g, PostureTable.SECURE to s, PostureTable.PROPS to p)
        val back = PostureObservations.from(PostureObservations.to(tunnel, readings, reads), confirmed)
        assertEquals(readings, back.readings)
        assertEquals(mapOf(PostureTable.GLOBAL to "ok", PostureTable.SECURE to "ok", PostureTable.PROPS to "timed out"), back.reads)
        // Unrelated observations are ignored.
        val noise = Observation(tunnel, "com.chat", "ops:CAMERA:mode", "allow")
        assertEquals(readings, PostureObservations.from(PostureObservations.to(tunnel, readings, reads) + noise, confirmed).readings)
        // A table that never ran is stored as not run.
        val notRun = PostureObservations.to(tunnel, readings, emptyMap())
        assertEquals("not run", notRun.single { it.key == "posture:read:global" }.value)
    }

    @Test
    fun valuesRespectTheCap() {
        val longName = "a".repeat(200)
        val obs = scan(secure = "always_on_vpn_app=$longName\nalways_on_vpn_lockdown=0\nalways_on_vpn_lockdown_whitelist=")
        for (o in obs) assertTrue(o.key, o.value.length <= PostureReader.MAX_VALUE)
        val vpn = obs.single { it.key == "posture:vpn_always_on:value" }.value
        assertEquals(PostureReader.MAX_VALUE, vpn.length)
        assertTrue(vpn.endsWith("…"))
        assertEquals(PostureReader.MAX_VALUE, PostureReader.cap("x".repeat(1000)).length)
        assertEquals("short", PostureReader.cap("short"))
    }

    // gate.py's bare-host rule (TLDS and BARE_HOST), mirrored: any lowercase dotted literal ending in one of these labels
    // would be listed as a host in the release gate.
    private val tlds = "com|net|org|io|dev|app|co|me|info|biz|xyz|ai|gov|edu|mil|int|us|uk|eu|de|fr|ca|au|jp|nl|ch|ru|cn|" +
        "tv|cc|gg|ly|to|page|cloud|site|online|tech|link|social|onion|arpa|local|lan"
    private val bareHost = Regex("""(?<![A-Za-z0-9._%+-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:$tlds))(?![A-Za-z0-9-]|\.[A-Za-z0-9])""")

    @Test
    fun noStringLiteralLooksLikeAHost() {
        // The detector itself.
        assertTrue(bareHost.containsMatchIn("see example.com now"))
        assertTrue(bareHost.containsMatchIn("go to a.to"))
        assertFalse(bareHost.containsMatchIn("persist.security.usb_mode"))
        assertFalse(bareHost.containsMatchIn("android.settings.SECURITY_SETTINGS"))

        val strings = ArrayList<String>()
        for (i in PostureKeys.ITEMS) strings += listOf(i.id, i.title, i.table.id, i.table.label, i.key, i.kind.orEmpty(), i.sourceDefault, i.action.orEmpty(), i.actionLabel.orEmpty(), i.path, i.extraKey.orEmpty())
        strings += PostureKeys.PROPS_COMMAND
        strings += PostureKeys.PROPS
        strings += PostureReader.USB_VALUES
        strings += PostureState.entries.map { it.word } + PostureWhy.entries.map { it.word }
        // Every evidence the rules can write, from a scan where everything is weak, plus the unread text.
        val worst = scan(
            global = "settings_reboot_after_timeout=0\nprivate_dns_mode=off\nallow_clipboard_read=1\nwifi_off_timeout=0\nbluetooth_off_timeout=0\nnfc_off_timeout=0",
            secure = "always_on_vpn_app=org.vpn.client\nalways_on_vpn_lockdown=0\nlockscreen_scramble_pin_layout=0\n" +
                "clipboard_show_access_notifications=0\nauto_grant_OTHER_SENSORS_perm=1\nlock_screen_lock_after_timeout=300000",
            props = "persist.security.usb_mode=4",
        )
        strings += drafts(worst).map { it.evidence }
        strings += drafts(scan(global = "[timed out]")).map { it.evidence }
        for (s in strings) assertFalse("host-like literal: $s", bareHost.containsMatchIn(s))

        // And the source files themselves: no literal, comment or KDoc in this module reads as a host either.
        val dir = File("src/main/kotlin/io/github/stronghorse44/tunnels/posture")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("sources found from ${File(".").absolutePath}", files.isNotEmpty())
        for (f in files) for (line in f.readLines()) {
            if (line.startsWith("package ") || line.startsWith("import ")) continue
            assertFalse("host-like text in ${f.name}: $line", bareHost.containsMatchIn(line))
        }
    }
}
