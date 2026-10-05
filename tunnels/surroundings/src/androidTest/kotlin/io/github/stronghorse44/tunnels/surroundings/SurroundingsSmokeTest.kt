package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.ble.Advertisement
import io.github.stronghorse44.tunnels.ble.AppleFindMyFrame
import io.github.stronghorse44.tunnels.ble.CellLogText
import io.github.stronghorse44.tunnels.ble.CellLogbook
import io.github.stronghorse44.tunnels.ble.CellTech
import io.github.stronghorse44.tunnels.ble.DultProtocol
import io.github.stronghorse44.tunnels.ble.FollowingLevel
import io.github.stronghorse44.tunnels.ble.IdentityFacts
import io.github.stronghorse44.tunnels.ble.Proximity
import io.github.stronghorse44.tunnels.ble.RssiMeter
import io.github.stronghorse44.tunnels.ble.ServingCell
import io.github.stronghorse44.tunnels.ble.Signal
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.ble.ThreatSummary
import io.github.stronghorse44.tunnels.ble.TrackerGuides
import io.github.stronghorse44.tunnels.ble.TrackerSignatures
import io.github.stronghorse44.tunnels.ble.TrackerState
import io.github.stronghorse44.tunnels.ble.TrackerType
import io.github.stronghorse44.tunnels.ble.TowerVerdict
import io.github.stronghorse44.tunnels.ble.TrackerVerdict
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the surroundings scan on the emulator as the engine would. A stock emulator has no Bluetooth
 * adapter and, before anything is granted, no permissions: the scan must still come back with a
 * well-formed summary that says so, and must finish inside the engine's patience. The tracker v2
 * parts that need no tag (Apple frame parser, proximity, verdict, DULT codec, the actions and the
 * find-it screen, which must open without an adapter and leave no session row) are exercised here too.
 * Since v3 following is judged per identity: tracker findings carry identity subjects and only identities
 * can be muted, which the action checks below pin; the family-level rotating-tag notice gets find it and
 * Android's alerts only.
 */
@RunWith(AndroidJUnit4::class)
class SurroundingsSmokeTest {
    private companion object {
        const val TAG = "SurroundingsSmokeTest"
        val availability = setOf(
            SurroundingsKeys.AVAILABLE_YES, SurroundingsKeys.AVAILABLE_NO_ADAPTER, SurroundingsKeys.AVAILABLE_OFF,
            SurroundingsKeys.AVAILABLE_NO_PERMISSION, SurroundingsKeys.AVAILABLE_LOCATION_OFF, SurroundingsKeys.AVAILABLE_FAILED,
        )
        val mac = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}", RegexOption.IGNORE_CASE)
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerRegistersTheTunnel() {
        val module = SurroundingsTunnels().create(context).single()
        assertEquals(SurroundingsKeys.TUNNEL_ID, module.id)
        assertEquals("Network", module.info.line.label)
        assertEquals(
            setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            module.requiredPermissions.map { it.permission }.toSet(),
        )
        assertTrue(module.requiredPermissions.all { it.reason.isNotBlank() })
        assertTrue(module.specialAccess.isEmpty())
        assertEquals(SurroundingsRules.all.size, module.rules.size)
        assertTrue(module is TunnelUi)
        assertTrue((module as TunnelUi).showObservations)
    }

    @Test
    fun everyFindingKindHasAnAction() {
        val module = SurroundingsTunnels().create(context).single()
        val drafts = listOf(
            FindingDraft(module.id, "tracker:findmy:deadbeef", SurroundingsRules.TRACKER_FOLLOWING, Severity.CRITICAL, ""),
            FindingDraft(module.id, "tracker:tile:aa11bb22", SurroundingsRules.TRACKER_STAYS, Severity.NOTICE, ""),
            FindingDraft(module.id, "tracker:tile", SurroundingsRules.NEW_TRACKER_TYPE, Severity.NOTICE, "", sticky = true),
            FindingDraft(module.id, "tracker:tile", SurroundingsRules.ROTATING_TRACKER, Severity.NOTICE, ""),
            FindingDraft(module.id, "Cafe", SurroundingsRules.OPEN_WIFI_CONNECTED, Severity.NOTICE, ""),
            FindingDraft(module.id, "Office", SurroundingsRules.EVIL_TWIN_SUSPECT, Severity.WARN, ""),
            FindingDraft(module.id, SurroundingsKeys.CELL_SUMMARY, SurroundingsRules.CELL_DOWNGRADE, Severity.WARN, ""),
            FindingDraft(module.id, SurroundingsKeys.CELL_SUMMARY, SurroundingsRules.CELL_DOWNGRADED, Severity.WARN, "", sticky = true),
            FindingDraft(module.id, "tower 0a1b2c3d", SurroundingsRules.UNFAMILIAR_TOWER, Severity.NOTICE, "", sticky = true),
        )
        for (d in drafts) {
            val actions = module.actionsFor(d)
            assertTrue(d.kind, actions.isNotEmpty())
            assertTrue(d.kind, actions.all { it.label.isNotBlank() })
        }
        // Following findings are per identity: find it (locked to that key), Android's alerts and a mute of that identity only.
        // The brand guides are sheets in the detail, not snackbar text.
        val tracker = module.actionsFor(drafts[0])
        assertEquals(listOf(TrackerActions.LABEL_FIND_IT, TrackerActions.LABEL_ALERTS, SurroundingsTunnel.LABEL_MUTE), tracker.map { it.label })
        assertEquals("Known tracker: mute 30 days", SurroundingsTunnel.LABEL_MUTE)
        assertTrue(tracker.all { it is FindingAction.Perform })
        // A tag that stays put gets the same per-identity actions, mute included.
        assertEquals(listOf(TrackerActions.LABEL_FIND_IT, TrackerActions.LABEL_ALERTS, SurroundingsTunnel.LABEL_MUTE), module.actionsFor(drafts[1]).map { it.label })
        // A family subject (the new-family notice) is never offered a family-wide mute.
        assertEquals(listOf(TrackerActions.LABEL_FIND_IT, TrackerActions.LABEL_ALERTS), module.actionsFor(drafts[2]).map { it.label })
        // The rotating-tag notice is family-level too: find it for the family and Android's alerts.
        assertEquals(listOf(TrackerActions.LABEL_FIND_IT, TrackerActions.LABEL_ALERTS), module.actionsFor(drafts[3]).map { it.label })
        // An unfamiliar tower: the network settings and "Normal here".
        val tower = module.actionsFor(drafts[8])
        assertEquals(listOf("Mobile network settings", SurroundingsTunnel.LABEL_NORMAL_HERE), tower.map { it.label })
        assertTrue(tower[0] is FindingAction.OpenSettings)
        assertTrue(tower[1] is FindingAction.Perform)
        // A tower subject that is not `tower <8 hex>` offers the settings only, never a "Normal here" for nothing.
        assertEquals(listOf("Mobile network settings"), module.actionsFor(FindingDraft(module.id, "tower nope", SurroundingsRules.UNFAMILIAR_TOWER, Severity.NOTICE, "")).map { it.label })
        // A subject that is not a tracker subject gets neither find it nor a mute rather than a guess.
        val odd = module.actionsFor(FindingDraft(module.id, "not a tracker", SurroundingsRules.TRACKER_FOLLOWING, Severity.WARN, ""))
        assertEquals(listOf(TrackerActions.LABEL_ALERTS), odd.map { it.label })
    }

    @Test
    fun unknownTrackerAlertsActionAlwaysLandsSomewhere() {
        // Safety Center when enabled and resolvable, otherwise Settings with the explanation; never an exception.
        val message = TrackerActions.openUnknownTrackerAlerts(context)
        Log.i(TAG, "unknown tracker alerts: $message")
        assertTrue(message.isNotBlank())
        assertTrue(message.contains("Safety Center") || message.contains("Settings"))
    }

    @Test
    fun pureTrackerLogicRunsOnDevice() {
        // The Apple frame parser, the proximity buckets, the verdict and the DULT codec, as the panel and find-it mode use them.
        val separated = byteArrayOf(0x12, 0x19, 0x10) + ByteArray(22) { 1 } + byteArrayOf(0, 0)
        val frame = AppleFindMyFrame.parse(separated)!!
        assertEquals(TrackerState.SEPARATED, frame.state)
        assertEquals(AppleFindMyFrame.KIND_AIRTAG, frame.kind)
        assertEquals("full", frame.battery)
        assertEquals(TrackerState.WITH_OWNER, TrackerSignatures.match(Advertisement(manufacturerData = mapOf(0x004C to byteArrayOf(0x12, 0x02, 0x10, 0x00))))!!.state)

        assertEquals(Proximity.NEAR, Proximity.of(-50))
        assertEquals(Proximity.FAR, Proximity.of(-90))
        assertTrue(RssiMeter.pulseIntervalMs(-50.0) < RssiMeter.pulseIntervalMs(-80.0))

        val facts = IdentityFacts(TrackerType.APPLE_FINDMY, "deadbeef", TrackerState.WITH_OWNER, 1, 1, 0, 0L, 0L, -58, -58, "full", "airtag", true, false)
        val verdict = TrackerVerdict.line(facts)
        assertTrue(verdict, verdict.startsWith("Seen in 1 scan over 0 min."))
        assertTrue(verdict, verdict.contains("3 separate scans spread over at least 30 minutes by this same identity"))
        assertEquals(FollowingLevel.NONE, facts.level)
        assertEquals("1 identity nearby: 1 near its owner", ThreatSummary.line(listOf(facts)))
        // A crowd of strangers each heard once never adds up to "following".
        val crowd = (0 until 35).map { facts.copy(key = "c%07x".format(it), state = TrackerState.SEPARATED) }
        assertEquals("35 identities nearby: 35 separated (seen 1×, not yet following)", ThreatSummary.line(crowd))
        for (type in TrackerType.entries) assertTrue(type.name, TrackerGuides.of(type).identify.isNotBlank())

        val response = DultProtocol.parse(DultProtocol.command(DultProtocol.COMMAND_RESPONSE) + byteArrayOf(0x00, 0x03, 0x00, 0x00)) as DultProtocol.Response.CommandResponse
        assertEquals(DultProtocol.SOUND_START, response.commandOpcode)
        assertTrue(response.ok)
        assertEquals("15190001-12f4-c226-88ed-2ac5579f2a85", DultClient.SERVICE.toString())
        assertEquals("8e0c0001-1d68-fb92-bf61-48377421680e", DultClient.CHARACTERISTIC.toString())
    }

    @Test
    fun findItModeOpensAndStopsWithoutBluetooth() = runBlocking {
        // A stock emulator has no adapter: the screen must open, say so, and leave exactly nothing behind when closed.
        val store = TunnelsStore.get(context)
        val before = store.events(SurroundingsKeys.TUNNEL_ID, 500).first().count { it.kind == SurroundingsKeys.EVENT_FINDIT }
        var ran = false
        ActivityScenario.launch<FindItActivity>(FindItActivity.intent(context, TrackerType.APPLE_FINDMY, "deadbeef")).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            Thread.sleep(1_500)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val s = activity.currentState
                Log.i(TAG, "find-it: available=${s.available} running=${s.running}")
                assertEquals(TrackerType.APPLE_FINDMY, s.type)
                assertEquals("deadbeef", s.lockedKey)
                assertTrue(s.available, s.available in availability)
                // Without an adapter (the stock emulator) the screen says so and never starts; with one it may be scanning.
                if (s.available != SurroundingsKeys.AVAILABLE_YES) assertFalse(s.running) else ran = s.running
                if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) assertEquals(SurroundingsKeys.AVAILABLE_NO_ADAPTER, s.available)
            }
            scenario.moveToState(Lifecycle.State.DESTROYED)
        }
        Thread.sleep(500)
        // A session that never scanned writes no summary row; one that did writes exactly one.
        val after = store.events(SurroundingsKeys.TUNNEL_ID, 500).first().count { it.kind == SurroundingsKeys.EVENT_FINDIT }
        assertEquals(if (ran) before + 1 else before, after)
    }

    @Test
    fun keystoreTokensAreStable32Hex() {
        val hex32 = Regex("[0-9a-f]{32}")
        val a = SurroundingsKey.hmac("cell:v1|LTE|310|260|1|2|3|4")
        assertTrue(a, hex32.matches(a))
        assertEquals(a, SurroundingsKey.hmac("cell:v1|LTE|310|260|1|2|3|4"))
        assertTrue(a != SurroundingsKey.hmac("cell:v1|LTE|310|260|1|2|3|5"))
        assertEquals(listOf(a, SurroundingsKey.hmac("b")), SurroundingsKey.hmacAll(listOf("cell:v1|LTE|310|260|1|2|3|4", "b")))
        val kid = SurroundingsKey.keyId()
        assertTrue(kid, Regex("[0-9a-f]{8}").matches(kid))
        assertEquals(kid, SurroundingsKey.keyId())
        // The key id is the front of the key's tag of a fixed text, and says nothing else.
        assertEquals(SurroundingsKey.hmac("kid:v1").take(8), kid)
    }

    @Test
    fun cellLogbookStartsJudgesAcceptsAndClears() = runBlocking {
        val log = CellLogStore(context)
        val store = TunnelsStore.get(context)
        val before = store.setting(CellLogStore.KEY)
        try {
            log.clear()
            assertFalse(log.isOn())
            assertEquals(CellLogText.Row.Off, log.rowFlow().first())
            // A synthetic place and synthetic cells; the hashes come from the real Keystore key.
            val block = SurroundingsKey.hmacAll((0 until 9).map { "grid:smoke-test:$it" })
            val home = ServingCell(CellTech.LTE, "310", "260", 424242, 987654321, 7, 5230)

            // Off: nothing is read, learned or written.
            assertEquals(SurroundingsKeys.LOG_OFF to null, log.judge(block, listOf(home), 3))
            assertNull(store.setting(CellLogStore.KEY))
            // A cell without an id is not judged.
            assertEquals(SurroundingsKeys.LOG_NO_CELL_ID to null, log.judge(block, listOf(home.copy(cellId = null)), 3))

            assertTrue(log.start().startsWith("The cell logbook is on"))
            assertTrue(log.isOn())
            assertTrue(log.rowFlow().first() is CellLogText.Row.On)
            repeat(4) { i ->
                val (state, j) = log.judge(block, listOf(home), 3)
                assertEquals(SurroundingsKeys.LOG_ON, state)
                assertEquals(TowerVerdict.LEARNING, j!!.verdict)
                assertEquals(i + 1, j.placeScans)
            }
            assertEquals(TowerVerdict.FAMILIAR, log.judge(block, listOf(home), 3).second!!.verdict)

            // An unfamiliar tower: a cell never used here with a new tracking area. Held, not learned.
            val odd = home.copy(cellId = 123456789, area = 777777)
            val (state, j) = log.judge(block, listOf(odd), 3)
            assertEquals(SurroundingsKeys.LOG_ON, state)
            assertEquals(TowerVerdict.UNFAMILIAR, j!!.verdict)
            assertEquals(setOf(Signal.AREA), j.signals)
            assertEquals(8, j.towerId!!.length)
            assertEquals(TowerVerdict.UNFAMILIAR, log.judge(block, listOf(odd), 3).second!!.verdict)

            // The row holds hashes, counts and ranks only: none of the identity above, and it reads back.
            val raw = store.setting(CellLogStore.KEY)!!
            assertNotNull(CellLogbook.decode(raw))
            for (secret in listOf("987654321", "123456789", "424242", "777777")) assertFalse(secret, raw.contains(secret))
            assertTrue(raw.lines().all { it.isEmpty() || it.startsWith("CLOG1") || it.startsWith("kid ") || it.startsWith("P ") || it.startsWith("H ") })

            // Normal here learns it and drops the hold; asking again has nothing to remember.
            assertEquals(CellLogStore.MESSAGE_REMEMBERED, log.accept(j.towerId!!))
            assertEquals(TowerVerdict.FAMILIAR, log.judge(block, listOf(odd), 3).second!!.verdict)
            assertEquals(CellLogStore.MESSAGE_NOT_HELD, log.accept(j.towerId!!))

            // A row that cannot be read is never overwritten: a scan, a start and a "Normal here" all leave it.
            store.putSetting(CellLogStore.KEY, "junk")
            assertEquals(SurroundingsKeys.LOG_UNREADABLE to null, log.judge(block, listOf(home), 3))
            log.start()
            assertEquals(CellLogText.UNREADABLE, log.accept(j.towerId!!))
            assertEquals("junk", store.setting(CellLogStore.KEY))
            assertEquals(CellLogText.Row.Unreadable, log.rowFlow().first())

            // Clear deletes the row, readable or not, which turns the logbook off.
            log.clear()
            assertNull(store.setting(CellLogStore.KEY))
            assertEquals(CellLogText.Row.Off, log.rowFlow().first())
            assertEquals(SurroundingsKeys.LOG_OFF to null, log.judge(block, listOf(home), 3))
        } finally {
            store.putSetting(CellLogStore.KEY, before)
        }
    }

    @Test
    fun scanWithoutRadiosOrPermissionsReportsAvailability() = runBlocking {
        val module = SurroundingsTunnels().create(context).single()
        var reports = 0
        val started = System.currentTimeMillis()
        val obs = module.scan { _, _, _ -> reports++ }
        val took = System.currentTimeMillis() - started
        obs.sortedWith(compareBy({ it.subject }, { it.key })).forEach { Log.i(TAG, "${it.subject} / ${it.key} = ${it.value}") }
        Log.i(TAG, "scan took ${took}ms with $reports progress reports")

        assertTrue("progress reported", reports >= 2)
        assertTrue("finished within a minute", took < 60_000)
        checkShape(obs)
    }

    @Test
    fun scanWithPermissionsGrantedStillWellFormed() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            runCatching { automation.grantRuntimePermission(context.packageName, p) }.onFailure { Log.w(TAG, "grant $p failed: $it") }
        }
        val module = SurroundingsTunnels().create(context).single()
        val obs = module.scan(ScanProgress.NONE)
        obs.sortedWith(compareBy({ it.subject }, { it.key })).forEach { Log.i(TAG, "granted: ${it.subject} / ${it.key} = ${it.value}") }
        checkShape(obs)
        // With the permissions in, the cell probe either works or says why not; it never claims a permission problem it does not have.
        val cell = obs.filter { it.subject == SurroundingsKeys.CELL_SUMMARY }.associate { it.key to it.value }
        if (cell[SurroundingsKeys.CELL_AVAILABLE] == SurroundingsKeys.AVAILABLE_YES) {
            assertTrue(cell[SurroundingsKeys.CELL_TYPE] in setOf("NR", "LTE", "UMTS", "GSM", "unknown"))
            assertNotNull(cell[SurroundingsKeys.CELL_OPERATOR])
            assertTrue(cell[SurroundingsKeys.CELL_NEIGHBOURS]!!.toInt() >= 0)
        }
        // A second scan sees the first one's cell row and can say whether anything changed.
        val again = module.scan(ScanProgress.NONE)
        checkShape(again)
        val cell2 = again.filter { it.subject == SurroundingsKeys.CELL_SUMMARY }.associate { it.key to it.value }
        if (cell2[SurroundingsKeys.CELL_TYPE] != null && cell[SurroundingsKeys.CELL_TYPE] != null) {
            assertTrue(cell2[SurroundingsKeys.CELL_CHANGED] in setOf("true", "false"))
        }
    }

    private fun checkShape(obs: List<Observation>) {
        assertTrue(obs.isNotEmpty())
        assertTrue(obs.all { it.tunnelId == SurroundingsKeys.TUNNEL_ID })
        assertEquals("identities are unique", obs.size, obs.map { it.identity }.toSet().size)
        assertTrue(obs.none { it.value.isEmpty() })
        assertFalse("no addresses leak", obs.any { mac.containsMatchIn(it.value) || mac.containsMatchIn(it.subject) })

        val ble = obs.filter { it.subject == SurroundingsKeys.BLE_SUMMARY }.associate { it.key to it.value }
        assertTrue(ble[SurroundingsKeys.BLE_AVAILABLE], ble[SurroundingsKeys.BLE_AVAILABLE] in availability)
        assertTrue(ble[SurroundingsKeys.DEVICES_TOTAL]!!.toInt() >= 0)
        assertTrue(ble[SurroundingsKeys.TRACKERS_TOTAL]!!.toInt() >= 0)
        assertNotNull(ble[SurroundingsKeys.TRACKERS_BY_TYPE])
        // Whether the scan knew the phone's place: a word, never a position.
        val place = ble[SurroundingsKeys.PLACE_AVAILABLE]
        assertTrue(place, place in availability + SurroundingsKeys.AVAILABLE_NO_FIX)
        assertTrue("no coordinates", obs.none { Regex("""-?\d{1,3}\.\d{4,}""").containsMatchIn(it.value) })

        val wifi = obs.filter { it.subject == SurroundingsKeys.WIFI_SUMMARY }.associate { it.key to it.value }
        assertTrue(wifi[SurroundingsKeys.WIFI_AVAILABLE], wifi[SurroundingsKeys.WIFI_AVAILABLE] in availability)
        val networks = wifi[SurroundingsKeys.WIFI_NETWORKS]!!.toInt()
        assertTrue(networks >= 0)
        if (wifi[SurroundingsKeys.WIFI_AVAILABLE] != SurroundingsKeys.AVAILABLE_YES) assertEquals(0, networks)
        for ((subject, facts) in obs.filter { it.key.startsWith("wifi:") && it.subject != SurroundingsKeys.WIFI_SUMMARY }.groupBy { it.subject }) {
            val m = facts.associate { it.key to it.value }
            assertTrue(subject, m[SurroundingsKeys.WIFI_SECURITY] in setOf("open", "wep", "wpa", "wpa2", "wpa3", "enterprise", "owe"))
            assertTrue(subject, m[SurroundingsKeys.WIFI_BSSIDS]!!.toInt() >= 1)
            assertTrue(subject, m[SurroundingsKeys.WIFI_CURRENT] in setOf("true", "false"))
        }

        val cell = obs.filter { it.subject == SurroundingsKeys.CELL_SUMMARY }.associate { it.key to it.value }
        assertTrue(cell[SurroundingsKeys.CELL_AVAILABLE], cell[SurroundingsKeys.CELL_AVAILABLE] in availability)
        assertTrue(cell[SurroundingsKeys.CELL_DOWNGRADES_RECORDED]!!.toInt() >= 0)
        if (cell[SurroundingsKeys.CELL_AVAILABLE] != SurroundingsKeys.AVAILABLE_YES) assertTrue(SurroundingsKeys.CELL_TYPE !in cell)
        // The logbook always says what it did; with no row (off) or no place it judges nothing, and no hash is an observation.
        val state = cell[SurroundingsKeys.LOG_STATE]
        assertTrue(state, state in setOf("off", "on", "no-place", "no-cell-id", "restarted", "unreadable", "failed"))
        if (state == "off" || state == "no-place" || state == "no-cell-id") assertTrue(SurroundingsKeys.LOG_VERDICT !in cell)
        assertTrue("no hash in an observation", obs.none { Regex("[0-9a-f]{32}").containsMatchIn(it.value) || Regex("[0-9a-f]{32}").containsMatchIn(it.subject) })
    }
}
