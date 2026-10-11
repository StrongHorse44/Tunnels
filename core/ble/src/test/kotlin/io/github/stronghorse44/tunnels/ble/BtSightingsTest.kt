package io.github.stronghorse44.tunnels.ble

import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtSightingsTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L

    private fun key(n: Int) = "%016x".format(n)

    private fun scan(minutesAgo: Long, place: Long?, vararg devs: Int) =
        BtScanRecord("b$minutesAgo", now - minutesAgo * min, place, devs.map { BtSeen(key(it), "dev$it") })

    @Test
    fun onlyAddressesThatStayTheSameAreKept() {
        assertTrue(BtAddress.isFixed("4C:11:22:33:44:55", BtAddressType.PUBLIC))
        assertTrue(BtAddress.isFixed("C4:11:22:33:44:55", BtAddressType.RANDOM)) // static random: 11
        assertFalse(BtAddress.isFixed("4C:11:22:33:44:55", BtAddressType.RANDOM)) // resolvable private: 01
        assertFalse(BtAddress.isFixed("0C:11:22:33:44:55", BtAddressType.RANDOM)) // non-resolvable: 00
        // Without a type (Android 14) only the private-looking 01 prefix is dropped.
        assertFalse(BtAddress.isFixed("5A:11:22:33:44:55", BtAddressType.UNKNOWN))
        assertTrue(BtAddress.isFixed("00:1A:7D:DA:71:13", BtAddressType.UNKNOWN))
        assertTrue(BtAddress.isFixed("F0:11:22:33:44:55", BtAddressType.UNKNOWN))
        assertFalse(BtAddress.isFixed("not an address", BtAddressType.PUBLIC))
        assertFalse(BtAddress.isFixed("00:11:22:33:44", BtAddressType.PUBLIC))
    }

    @Test
    fun aRowSurvivesNamesAndHintsAndOldRowsReadBack() {
        val r = BtScanRecord(
            "monitor-1", 1234L, 7L,
            listOf(BtSeen(key(1), "Mini Cam", "name-cam"), BtSeen(key(2), ""), BtSeen(key(3), "a,b~c;d=e%f", "maker-02f2")),
        )
        assertEquals(r, BtScanRecord.parse("monitor-1", r.encode()))
        assertEquals(r.copy(place = null), BtScanRecord.parse("monitor-1", r.copy(place = null).encode()))
        assertNull(BtScanRecord.parse("", r.encode()))
        assertNull(BtScanRecord.parse("x", "place=1"))
        // A malformed hint drops the hint only; a malformed key drops the entry.
        val parsed = BtScanRecord.parse("x", "at=5;place=;devs=${key(1)}~ok~NOT A HINT,NOTHEX~x,${key(2)}~two~name-mic")!!
        assertEquals(listOf(BtSeen(key(1), "ok"), BtSeen(key(2), "two", "name-mic")), parsed.devices)
        // Wi-Fi rows written before hints existed read back unchanged, and new ones carry the hint.
        assertEquals(listOf(ApSeen(key(1), "Home")), WifiScanRecord.parse("w", "at=1;place=;aps=${key(1)}~Home")!!.aps)
        val w = WifiScanRecord("w", 2L, null, listOf(ApSeen(key(1), "HDWiFiCam", "name-wificam"), ApSeen(key(2), "Home")))
        assertEquals(w, WifiScanRecord.parse("w", w.encode()))
    }

    @Test
    fun aggregateCountsMovesWithWifiAndKeepsTheLatestHint() {
        val bt = listOf(
            scan(200, 10, 1, 9),
            scan(150, 10, 1, 9),
            BtScanRecord("b100", now - 100 * min, 11, listOf(BtSeen(key(9), "Mini Cam", "name-cam"))),
            scan(50, 10, 1),
        )
        // Wi-Fi says the step 10 → 11 was a real move: home's access points were not heard at 11.
        val wifi = listOf(
            WifiScanRecord("w1", now - 200 * min, 10, listOf(ApSeen(key(101), "Home"), ApSeen(key(102), "Neighbour"))),
            WifiScanRecord("w2", now - 100 * min, 11, listOf(ApSeen(key(201), "Shop"))),
        )
        val byKey = BtFollowing.aggregate(bt, wifi).associateBy { it.key }
        val traveller = byKey.getValue(key(9))
        assertEquals(3, traveller.sessions)
        assertEquals(1, traveller.moves)
        assertEquals("name-cam", traveller.hint)
        assertEquals("Mini Cam", traveller.name)
        val speaker = byKey.getValue(key(1))
        assertEquals(3, speaker.sessions)
        assertEquals(0, speaker.moves)
        assertNull(speaker.hint)
    }

    private fun device(n: Int, name: String, sessions: Int, spanMin: Long, moves: Int = 0, hint: String? = null, lastSession: String = "old") =
        BtDeviceSighting(key(n), name, 1, now - (spanMin + 1) * min, now - min, sessions, 1 + moves, moves, lastSession, hint)

    private fun findings(devices: List<BtDeviceSighting>, muted: Set<String> = emptySet()) =
        SurroundingsKeys.btObservations(devices, 5, now, logged = true, fixedNow = devices.size, rotatingNow = 3, muted = muted, currentSession = "now").let { obs ->
            SurroundingsRules.all.flatMap { it.evaluate(RuleContext(SurroundingsKeys.TUNNEL_ID, obs, emptyList(), isFirstScan = true)) }
        }

    @Test
    fun flagOnSightAndRaiseOnRepeat() {
        val speaker = device(1, "JBL Flip 5", 12, 600)
        val camRepeat = device(2, "Mini Cam", 6, 90, hint = "name-cam")
        val camNow = device(3, "HDWiFiCam", 1, 0, hint = "name-wificam", lastSession = "now")
        val camGone = device(4, "IPCam", 1, 0, hint = "name-ipcam")
        val follower = device(5, "", 6, 120, moves = 1)
        val f = findings(listOf(speaker, camRepeat, camNow, camGone, follower)).associateBy { it.subject }
        assertEquals(4, f.size)
        f.getValue(SurroundingsKeys.btDeviceSubject(key(1))).let {
            assertEquals(SurroundingsRules.BT_DEVICE_REPEATED, it.kind)
            assertEquals(Severity.NOTICE, it.severity)
            assertTrue(it.evidence, it.evidence.startsWith("Bluetooth device \"JBL Flip 5\" was seen in 12 separate scans over 10 hours"))
        }
        f.getValue(SurroundingsKeys.btDeviceSubject(key(2))).let {
            assertEquals(SurroundingsRules.BT_DEVICE_REPEATED, it.kind)
            assertEquals(Severity.WARN, it.severity)
            assertTrue(it.evidence, it.evidence.contains("Its name says \"cam\": it may be a camera."))
        }
        f.getValue(SurroundingsKeys.btDeviceSubject(key(3))).let {
            assertEquals(SurroundingsRules.BT_DEVICE_HINT, it.kind)
            assertEquals(Severity.NOTICE, it.severity)
            assertTrue(it.evidence, it.evidence.contains(SurroundingsRules.HINT_NOT_PROOF))
        }
        f.getValue(SurroundingsKeys.btDeviceSubject(key(5))).let {
            assertEquals(SurroundingsRules.BT_DEVICE_FOLLOWING, it.kind)
            assertEquals(Severity.WARN, it.severity)
            assertTrue(it.evidence, it.evidence.startsWith("Bluetooth device \"unnamed 000000\""))
        }
        // A hinted device not in this scan and below the threshold stays listed without a finding.
        assertFalse(SurroundingsKeys.btDeviceSubject(key(4)) in f)
        // Muted devices keep their row and lose the finding.
        val muted = findings(listOf(camNow, camRepeat), muted = setOf(SurroundingsKeys.btDeviceSubject(key(3))))
        assertEquals(listOf(SurroundingsKeys.btDeviceSubject(key(2))), muted.map { it.subject })
    }

    @Test
    fun aWifiNameHintFlagsOnSightAndRaisesARepeat() {
        fun ap(n: Int, name: String, sessions: Int, span: Long, hint: String?, lastSession: String) =
            AccessPointSighting(key(n), name, 1, now - (span + 1) * min, now - min, sessions, 1, 0, lastSession, hint)
        val obs = SurroundingsKeys.apObservations(
            listOf(ap(1, "HDWiFiCam", 1, 0, "name-wificam", "now"), ap(2, "SMARTCAM-9F", 7, 120, "name-cam", "old"), ap(3, "Home", 7, 120, null, "old")),
            5, now, currentSession = "now",
        )
        val f = SurroundingsRules.all.flatMap { it.evaluate(RuleContext(SurroundingsKeys.TUNNEL_ID, obs, emptyList(), isFirstScan = true)) }
            .associateBy { it.subject }
        assertEquals(SurroundingsRules.WIFI_AP_HINT, f.getValue(SurroundingsKeys.apSubject(key(1))).kind)
        assertEquals(Severity.NOTICE, f.getValue(SurroundingsKeys.apSubject(key(1))).severity)
        assertEquals(Severity.WARN, f.getValue(SurroundingsKeys.apSubject(key(2))).severity)
        assertEquals(Severity.NOTICE, f.getValue(SurroundingsKeys.apSubject(key(3))).severity)
        val summary = WifiOverTime.summary(obs)!!
        assertEquals(2, summary.repeated)
        assertEquals(1, summary.hinted)
        val listed = WifiOverTime.accessPoints(obs)
        assertTrue(listed.first { it.name == "HDWiFiCam" }.flagged)
        assertEquals("name-wificam", listed.first { it.name == "HDWiFiCam" }.hint?.id)
        // "Mute all" covers the plain repeat only: a possible camera is muted one at a time, after a look.
        assertEquals(listOf(SurroundingsKeys.apSubject(key(3))), WifiOverTime.muteAllCandidates(listed))
    }

    @Test
    fun theSummarySaysWhetherThisScanLoggedAndHintedDevicesAreListedPastTheCap() {
        val crowd = (1..SurroundingsKeys.MAX_LISTED_APS + 10).map { device(it, "d$it", 1, 0) }
        val hinted = device(999, "Spy Pen", 1, 0, hint = "name-spy", lastSession = "now")
        val obs = SurroundingsKeys.btObservations(crowd + hinted, 5, now, logged = false, fixedNow = 0, rotatingNow = 7, currentSession = "now")
        assertEquals("false", SurroundingsKeys.value(obs.filter { it.subject == SurroundingsKeys.BT_SUMMARY }, SurroundingsKeys.BT_LOGGED))
        assertEquals("7", SurroundingsKeys.value(obs.filter { it.subject == SurroundingsKeys.BT_SUMMARY }, SurroundingsKeys.BT_ROTATING))
        val listed = WifiOverTime.bluetoothDevices(obs)
        assertTrue(listed.any { it.subject == SurroundingsKeys.btDeviceSubject(key(999)) && it.flagged })
        assertEquals(1, WifiOverTime.summary(obs, SurroundingsKeys.BT_SUMMARY)!!.hinted)
        // An unnamed device has no name fact (observations are never empty) and reads back as "".
        val unnamed = SurroundingsKeys.btObservations(listOf(device(7, "", 1, 0)), 5, now, logged = true, fixedNow = 1, rotatingNow = 0)
        assertTrue(unnamed.none { it.value.isEmpty() })
        assertEquals("", WifiOverTime.bluetoothDevices(unnamed).single().name)
        assertEquals("unnamed 00abcd", BtFollowing.displayName("", "btdev:00abcdef00000000"))
    }
}
