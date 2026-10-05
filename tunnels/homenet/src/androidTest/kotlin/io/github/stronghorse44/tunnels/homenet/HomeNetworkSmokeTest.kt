package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkTable
import io.github.stronghorse44.tunnels.lan.CensusMessages
import io.github.stronghorse44.tunnels.lan.DeviceCensus
import io.github.stronghorse44.tunnels.lan.DeviceIdentity
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import io.github.stronghorse44.tunnels.lan.NetworkMigration
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.store.FindingEntity
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * On a stock emulator nothing is confirmed (the gate needs no runtime permission), so the gate must
 * refuse quickly with a well-formed summary and open no sockets.
 */
@RunWith(AndroidJUnit4::class)
class HomeNetworkSmokeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun refusesToScanWithoutAConfirmedNetwork() = runBlocking {
        val module = HomeNetTunnels().create(context).single()
        assertEquals(LanKeys.TUNNEL_ID, module.id)
        assertEquals(emptyList<String>(), module.requiredPermissions.map { it.permission })
        assertEquals(LanRules.all.size, module.rules.size)
        // Opening the encrypted store (Keystore, SQLCipher) is not what this test times.
        TunnelsStore.get(context)
        assertTrue(NetworkGate(context).forgetAll())

        var reports = 0
        val started = System.nanoTime()
        val obs = module.scan { _, _, _ -> reports++ }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the gate answers without touching the network ($elapsedMs ms)", elapsedMs < 3_000)
        assertEquals("no stage progress before the gate", 0, reports)

        assertTrue(obs.isNotEmpty())
        assertTrue(obs.all { it.tunnelId == LanKeys.TUNNEL_ID && it.subject == LanKeys.SUBJECT_SUMMARY })
        assertFalse(obs.any { LanKeys.isHostSubject(it.subject) || it.subject == LanKeys.SUBJECT_ROUTER })
        assertEquals(LanKeys.GATE_UNCONFIRMED, LanKeys.value(obs, LanKeys.SCAN_GATE))
        val reason = LanKeys.value(obs, LanKeys.SCAN_GATE_REASON)
        assertTrue(
            "reason $reason",
            reason in setOf(LanKeys.REASON_NO_WIFI, LanKeys.REASON_NETWORK_UNKNOWN, LanKeys.REASON_NOT_CONFIRMED),
        )
        assertTrue(obs.none { it.key == LanKeys.SCAN_NETWORK || it.key == LanKeys.HOSTS_TOTAL })
        assertTrue("a refused scan writes no census key", obs.none { it.key.startsWith("census:") || it.key == LanKeys.HOST_IDS })
        assertEquals(obs.size, obs.map { it.key }.toSet().size)

        // Second scan is just as quiet and describes the same state.
        assertEquals(obs, module.scan(ScanProgress.NONE))
    }

    @Test
    fun everyFindingKindHasActionsAndAGuide() = runBlocking {
        val module = HomeNetTunnels().create(context).single()
        val router = FindingDraft(module.id, LanKeys.SUBJECT_ROUTER, LanRules.DNS_HIJACK, Severity.CRITICAL, "Your router's DNS answers for names that do not exist.")
        val routerActions = module.actionsFor(router)
        assertEquals(listOf(LanGuides.FIX_LABEL, LanGuides.ROUTER_ADMIN_LABEL, LanGuides.WIFI_SETTINGS_LABEL), routerActions.map { it.label })
        assertTrue(routerActions.last() is FindingAction.OpenSettings)
        val guide = (routerActions.first() as FindingAction.Perform).run()
        assertTrue(guide, guide.contains("DNS") && guide.contains("Private DNS"))

        val host = FindingDraft(module.id, "192.168.1.20", LanRules.RISKY_SERVICE, Severity.WARN, "DiskStation accepts connections for Telnet remote login (23), Windows file sharing (SMB) (445).")
        val hostActions = module.actionsFor(host)
        assertEquals(listOf(LanGuides.FIX_LABEL, LanGuides.WIFI_SETTINGS_LABEL), hostActions.map { it.label })
        val hostGuide = (hostActions.first() as FindingAction.Perform).run()
        assertTrue(hostGuide, hostGuide.contains("Telnet remote login") && hostGuide.contains("Windows file sharing"))

        // A retired NEW_HOST finding stored by an older build still gets its guide.
        for (kind in listOf(LanRules.UPNP_IGD_ENABLED, LanRules.NEW_HOST, LanRules.CAMERA_OPEN_WEB, LanRules.UNKNOWN_DEVICE, LanRules.CENSUS_NOT_SET_UP)) {
            val actions = module.actionsFor(FindingDraft(module.id, "x", kind, Severity.INFO, "e"))
            assertTrue(kind, actions.isNotEmpty())
            assertTrue(kind, (actions.first() as FindingAction.Perform).run().length > 40)
        }

        // The census findings: Mine and These are all mine come first, and only when the subject says what to act on.
        val primary = DeviceIdentity.token("00".repeat(32), "n", "living room tv")
        val stranger = module.actionsFor(
            FindingDraft(module.id, DeviceCensus.subject("Living Room TV", primary), LanRules.UNKNOWN_DEVICE, Severity.NOTICE, "A device that is not on your list", sticky = true),
        )
        assertEquals(listOf(LanGuides.MINE_LABEL, LanGuides.FIX_LABEL, LanGuides.ROUTER_ADMIN_LABEL, LanGuides.WIFI_SETTINGS_LABEL), stranger.map { it.label })
        val unidentified = module.actionsFor(FindingDraft(module.id, "unidentified · 192.168.1.9", LanRules.UNKNOWN_DEVICE, Severity.NOTICE, "e", sticky = true))
        assertEquals(listOf(LanGuides.FIX_LABEL, LanGuides.ROUTER_ADMIN_LABEL, LanGuides.WIFI_SETTINGS_LABEL), unidentified.map { it.label })
        val setUp = module.actionsFor(FindingDraft(module.id, DeviceCensus.setupSubject("ab12cd34"), LanRules.CENSUS_NOT_SET_UP, Severity.NOTICE, "e"))
        assertEquals(listOf(LanGuides.ALL_MINE_LABEL, LanGuides.FIX_LABEL, LanGuides.WIFI_SETTINGS_LABEL), setUp.map { it.label })
        assertTrue(module.actionsFor(FindingDraft(module.id, "Device census", LanRules.CENSUS_NOT_SET_UP, Severity.NOTICE, "e")).none { it.label == LanGuides.ALL_MINE_LABEL })
        assertTrue(setUp.none { it is FindingAction.Perform && it.destructive })
    }

    // ---- Device census, against the real encrypted store ---------------------------------------

    private val store: TunnelsStore get() = TunnelsStore.get(context)
    private val summarySubject = LanKeys.SUBJECT_SUMMARY

    private fun token(tag: String, value: String) = DeviceIdentity.token(tag.repeat(8), "n", value)

    /** Inserts a stored Home network snapshot [minutesAgo] old for the network [tag]; [mixed] adds another tunnel's row. */
    private suspend fun insertSnapshot(
        minutesAgo: Long,
        tag: String,
        state: String,
        pinned: Boolean = false,
        known: List<String> = emptyList(),
        hosts: Map<String, List<String>> = emptyMap(),
        mixed: Boolean = false,
    ): Long {
        val takenAt = System.currentTimeMillis() - minutesAgo * 60_000
        return store.dao.importSnapshot(SnapshotEntity(takenAt = takenAt, pinned = pinned)) { id ->
            fun row(subject: String, key: String, value: String) = ObservationEntity(id, LanKeys.TUNNEL_ID, subject, key, value)
            buildList {
                add(row(summarySubject, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED))
                add(row(summarySubject, LanKeys.SCAN_NETWORK, tag))
                add(row(summarySubject, LanKeys.CENSUS_STATE, state))
                if (state != DeviceCensus.STATE_UNAVAILABLE) add(row(summarySubject, LanKeys.CENSUS_KNOWN, LanKeys.list(known.sorted())))
                add(row(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_IP, "192.168.1.1"))
                add(row("192.168.1.1", LanKeys.HOST_KIND, "router"))
                for ((ip, ids) in hosts) {
                    add(row(ip, LanKeys.HOST_KIND, "tv"))
                    if (ids.isNotEmpty()) add(row(ip, LanKeys.HOST_IDS, LanKeys.list(ids)))
                }
                if (mixed) add(ObservationEntity(id, "permissions", "app", "k", "v"))
            }
        }
    }

    private suspend fun pinned(id: Long): Boolean = store.dao.snapshot(id)!!.pinned

    @Test
    fun censusPinMovesToTheNewestHomeNetworkSnapshot() = runBlocking {
        val tag = "5e7c0b08"
        val other = "5e7c0b09"
        val census = CensusStore(context)
        val ids = ArrayList<Long>()
        try {
            val old = insertSnapshot(50, tag, DeviceCensus.STATE_SET, pinned = true, known = listOf(token(tag, "a")))
            val older = insertSnapshot(40, tag, DeviceCensus.STATE_SET, known = listOf(token(tag, "a")))
            val newest = insertSnapshot(30, tag, DeviceCensus.STATE_SET, known = listOf(token(tag, "a"), token(tag, "b")))
            val mixed = insertSnapshot(20, tag, DeviceCensus.STATE_SET, known = listOf(token(tag, "a")), mixed = true)
            val unavailable = insertSnapshot(15, tag, DeviceCensus.STATE_UNAVAILABLE)
            val elsewhere = insertSnapshot(10, other, DeviceCensus.STATE_SET, pinned = true, known = listOf(token(other, "z")))
            ids += listOf(old, older, newest, mixed, unavailable, elsewhere)

            census.settle(tag)
            assertTrue("the newest Home-network-only list is pinned", pinned(newest))
            assertFalse("an older pin of this network moves", pinned(old))
            assertFalse(pinned(older))
            assertFalse("a mixed snapshot is never pinned", pinned(mixed))
            assertFalse(pinned(unavailable))
            assertTrue("another network's pin is left alone", pinned(elsewhere))
            census.settle(tag)
            assertTrue("settling again changes nothing", pinned(newest) && !pinned(old))

            // A reset ends the list: nothing taken before it is pinned any more.
            assertEquals(CensusMessages.RESET, census.reset(tag))
            assertFalse(pinned(newest))
            assertTrue(pinned(elsewhere))
            assertEquals(DeviceCensus.STATE_UNSET, census.census(tag).state)
            assertEquals("another network's list is untouched by the reset", DeviceCensus.STATE_SET, census.census(other).state)
        } finally {
            store.dao.deleteSnapshots(ids)
        }
    }

    @Test
    fun ackFoldsIntoTheNextListAndExpiresWithEvents() = runBlocking {
        val tag = "5e7c0b0a"
        val census = CensusStore(context)
        val listed = token(tag, "office printer")
        val tvIds = listOf(DeviceIdentity.token(tag.repeat(8), "u", "3f2a9c10-1111-2222-3333-444455556666"), token(tag, "living room tv"))
        val snapshots = ArrayList<Long>()
        val findingId = Finding.findingId(LanKeys.TUNNEL_ID, DeviceCensus.subject("Living Room TV", tvIds[0]), LanRules.UNKNOWN_DEVICE)
        // The same device raised once more under its other token (a lost SSDP reply), and an unrelated stranger.
        val twinId = Finding.findingId(LanKeys.TUNNEL_ID, DeviceCensus.subject("Living Room TV", tvIds[1]), LanRules.UNKNOWN_DEVICE)
        val strangerId = Finding.findingId(LanKeys.TUNNEL_ID, DeviceCensus.subject("Stranger", token(tag, "stranger")), LanRules.UNKNOWN_DEVICE)
        try {
            snapshots += insertSnapshot(
                5, tag, DeviceCensus.STATE_SET, known = listOf(listed),
                hosts = mapOf("192.168.1.30" to listOf(listed), "192.168.1.23" to tvIds, "192.168.1.77" to emptyList()),
            )
            val now = System.currentTimeMillis()
            store.dao.upsertFindings(
                listOf(
                    FindingEntity(
                        findingId, LanKeys.TUNNEL_ID, DeviceCensus.subject("Living Room TV", tvIds[0]), LanRules.UNKNOWN_DEVICE, "NOTICE",
                        now, now, "A device that is not on your list", sticky = true,
                    ),
                    FindingEntity(
                        twinId, LanKeys.TUNNEL_ID, DeviceCensus.subject("Living Room TV", tvIds[1]), LanRules.UNKNOWN_DEVICE, "NOTICE",
                        now, now, "A device that is not on your list", sticky = true,
                    ),
                    FindingEntity(
                        strangerId, LanKeys.TUNNEL_ID, DeviceCensus.subject("Stranger", token(tag, "stranger")), LanRules.UNKNOWN_DEVICE, "NOTICE",
                        now, now, "A device that is not on your list", sticky = true,
                    ),
                ),
            )
            val before = census.census(tag)
            assertEquals(setOf(listed), before.known)

            // Mine: the host is found by its primary token, all its tokens are acknowledged, the finding is dismissed.
            assertEquals(CensusMessages.ADDED, census.ack(DeviceCensus.subject("Living Room TV", tvIds[0])))
            val mine = census.events().filter { it.subject == tag }
            assertEquals(listOf(DeviceCensus.eventSummary(tvIds)), mine.map { it.summary })
            assertEquals(DeviceCensus.KIND_ACK, mine.single().kind)
            assertTrue(store.dao.findings().single { it.id == findingId }.dismissed)
            assertTrue("the same device under its other token goes too", store.dao.findings().single { it.id == twinId }.dismissed)
            assertFalse("a stranger stays", store.dao.findings().single { it.id == strangerId }.dismissed)
            val after = census.census(tag)
            assertEquals(DeviceCensus.STATE_SET, after.state)
            assertEquals(setOf(listed) + tvIds, after.known)

            // A device that is in no recent scan is refused, and nothing is written.
            assertEquals(CensusMessages.NOT_IN_SCAN, census.ack(DeviceCensus.subject("Ghost", token(tag, "ghost"))))
            assertEquals(CensusMessages.NOT_IN_SCAN, census.ack("unidentified · 192.168.1.77"))
            assertEquals(1, census.events().count { it.subject == tag })

            // Acknowledgements are events: after 30 days they are gone, and so is what only they held.
            store.maintain(Instant.now().plus(Duration.ofDays(31)))
            assertTrue(census.events().none { it.subject == tag })
        } finally {
            store.dao.deleteSnapshots(snapshots)
            store.dao.deleteFindings(listOf(findingId, twinId, strangerId))
        }
    }

    @Test
    fun anExpiredResetDoesNotRevivePastTheEventLife() = runBlocking {
        val tag = "5e7c0b0d"
        val pinnedTag = "5e7c0b0e"
        val census = CensusStore(context)
        val snapshots = ArrayList<Long>()
        val thirtyOneDays = 31L * 24 * 60
        try {
            // A list left in an old unpinned snapshot, with no reset event any more: it must not come back, or be pinned again.
            val old = insertSnapshot(thirtyOneDays, tag, DeviceCensus.STATE_SET, known = listOf(token(tag, "tv")))
            snapshots += old
            census.settle(tag)
            assertFalse(pinned(old))
            val list = census.census(tag)
            assertEquals(DeviceCensus.STATE_UNSET, list.state)
            assertTrue(list.known.isEmpty())
            assertFalse(pinned(old))
            // A pinned one is the list however old it is.
            snapshots += insertSnapshot(thirtyOneDays, pinnedTag, DeviceCensus.STATE_SET, pinned = true, known = listOf(token(pinnedTag, "tv")))
            assertEquals(DeviceCensus.STATE_SET, census.census(pinnedTag).state)
            // A hand-pinned full (mixed) snapshot is not exempt: after the reset event is gone it does not bring its list back.
            val mixedTag = "5e7c0b10"
            snapshots += insertSnapshot(thirtyOneDays, mixedTag, DeviceCensus.STATE_SET, pinned = true, known = listOf(token(mixedTag, "tv")), mixed = true)
            assertEquals(DeviceCensus.STATE_UNSET, census.census(mixedTag).state)
            // A stale unpinned mixed list in front does not hide a pinned Home-network-only list behind it.
            val behindTag = "5e7c0b11"
            snapshots += insertSnapshot(40L * 24 * 60, behindTag, DeviceCensus.STATE_SET, pinned = true, known = listOf(token(behindTag, "tv")))
            snapshots += insertSnapshot(thirtyOneDays, behindTag, DeviceCensus.STATE_SET, known = listOf(token(behindTag, "other")), mixed = true)
            assertEquals(setOf(token(behindTag, "tv")), census.census(behindTag).known)
        } finally {
            store.dao.deleteSnapshots(snapshots)
        }
    }

    @Test
    fun aFullListRefusesMineAndWritesNothing() = runBlocking {
        val tag = "5e7c0b0f"
        val census = CensusStore(context)
        val snapshots = ArrayList<Long>()
        val newcomer = listOf(token(tag, "newcomer"))
        val subject = DeviceCensus.subject("Newcomer", newcomer[0])
        val findingId = Finding.findingId(LanKeys.TUNNEL_ID, subject, LanRules.UNKNOWN_DEVICE)
        try {
            snapshots += insertSnapshot(
                5, tag, DeviceCensus.STATE_SET, known = (1..DeviceCensus.MAX_KNOWN).map { token(tag, "device $it") },
                hosts = mapOf("192.168.1.50" to newcomer),
            )
            val now = System.currentTimeMillis()
            store.dao.upsertFindings(listOf(FindingEntity(findingId, LanKeys.TUNNEL_ID, subject, LanRules.UNKNOWN_DEVICE, "NOTICE", now, now, "e", sticky = true)))
            assertEquals(DeviceCensus.MAX_KNOWN, census.census(tag).known.size)
            assertEquals(CensusMessages.FULL, census.ack(subject))
            assertTrue("nothing was written", census.events().none { it.subject == tag })
            assertFalse("the finding stays", store.dao.findings().single { it.id == findingId }.dismissed)
        } finally {
            store.dao.deleteSnapshots(snapshots)
            store.dao.deleteFindings(listOf(findingId))
        }
    }

    @Test
    fun setupAcknowledgesEveryDeviceWithAnIdentityButNotTheGateway() = runBlocking {
        val tag = "5e7c0b0b"
        val census = CensusStore(context)
        val snapshots = ArrayList<Long>()
        try {
            assertEquals(CensusMessages.NO_SCAN_FOR_SETUP, census.setup(tag))
            snapshots += insertSnapshot(
                5, tag, DeviceCensus.STATE_UNSET,
                hosts = mapOf("192.168.1.23" to listOf(token(tag, "tv")), "192.168.1.30" to listOf(token(tag, "printer")), "192.168.1.77" to emptyList()),
            )
            assertEquals(DeviceCensus.STATE_UNSET, census.census(tag).state)
            assertEquals(CensusMessages.setupDone(2, 1), census.setup(tag))
            val list = census.census(tag)
            assertEquals(DeviceCensus.STATE_SET, list.state)
            assertEquals(setOf(token(tag, "tv"), token(tag, "printer")), list.known)
            // Starting again forgets them, however recent they are.
            assertEquals(CensusMessages.RESET, census.reset(tag))
            assertEquals(DeviceCensus.STATE_UNSET, census.census(tag).state)
        } finally {
            store.dao.deleteSnapshots(snapshots)
        }
    }

    @Test
    fun anUnavailableSnapshotNeverHidesTheList() = runBlocking {
        val tag = "5e7c0b0c"
        val census = CensusStore(context)
        val snapshots = ArrayList<Long>()
        try {
            snapshots += insertSnapshot(20, tag, DeviceCensus.STATE_SET, known = listOf(token(tag, "tv")))
            snapshots += insertSnapshot(10, tag, DeviceCensus.STATE_UNAVAILABLE)
            val list = census.census(tag)
            assertEquals(DeviceCensus.STATE_SET, list.state)
            assertEquals(setOf(token(tag, "tv")), list.known)
        } finally {
            store.dao.deleteSnapshots(snapshots)
        }
    }

    @Test
    fun gateRemembersOnlyHashes() = runBlocking {
        val gate = NetworkGate(context)
        assertTrue(gate.forgetAll())
        assertEquals(0, gate.confirmedCount())
        val home = NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("192.168.1.1"), "192.168.1.0/24", ssid = "Test Net")!!
        val other = NetworkFingerprint.of("192.168.0.1", "192.168.0.1", listOf("192.168.0.1"), "192.168.0.0/24")!!
        assertFalse(gate.isConfirmed(home))
        assertTrue(gate.confirm(home))
        assertTrue(gate.isConfirmed(home))
        assertFalse(gate.isConfirmed(other))
        assertEquals(1, gate.confirmedCount())
        // The hashes live in the encrypted settings table, nowhere else.
        val row = TunnelsStore.get(context).setting(ConfirmedNetworkCodec.KEY)!!
        assertEquals(setOf(home.hash), ConfirmedNetworkCodec.decode(row))
        assertTrue(row.lines().none { it.contains("192.168") || it.contains("Test") })
        assertFalse("no plaintext preferences file", plaintextFile(ConfirmedNetworkBook.LEGACY_PREFS).exists())
        assertTrue(gate.forget(home))
        assertFalse(gate.isConfirmed(home))
        assertTrue(gate.forgetAll())
    }

    /** An update from a build that kept the hashes in plaintext preferences: they move, once, and the file goes. */
    @Test
    fun oldPlaintextHashesMoveIntoTheEncryptedStore() = runBlocking {
        val store = TunnelsStore.get(context)
        val home = NetworkFingerprint.of("192.168.7.1", "192.168.7.1", listOf("192.168.7.1"), "192.168.7.0/24")!!
        val other = NetworkFingerprint.of("10.7.0.1", "10.7.0.1", listOf("10.7.0.1"), "10.7.0.0/24")!!
        store.putSetting(ConfirmedNetworkCodec.KEY, null) // as on a phone that never ran this build
        val prefs = context.getSharedPreferences(ConfirmedNetworkBook.LEGACY_PREFS, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putStringSet(ConfirmedNetworkBook.LEGACY_KEY, setOf(home.hash, "not-a-hash")).commit())
        assertTrue(plaintextFile(ConfirmedNetworkBook.LEGACY_PREFS).exists())

        val gate = NetworkGate(context)
        // The start-up migration (HomeNetTunnels.create) may have got there first; either way it happened exactly once.
        val moved = gate.migrate()
        assertTrue(moved.toString(), moved == NetworkMigration.Moved(1) || moved == NetworkMigration.NothingToMove)
        assertFalse("the plaintext file is deleted", plaintextFile(ConfirmedNetworkBook.LEGACY_PREFS).exists())
        assertEquals(setOf(home.hash), ConfirmedNetworkCodec.decode(store.setting(ConfirmedNetworkCodec.KEY)))
        assertTrue(gate.isConfirmed(home))
        assertFalse(gate.isConfirmed(other))
        assertEquals(1, gate.confirmedCount())
        assertEquals("a second run has nothing to move", NetworkMigration.NothingToMove, gate.migrate())
        assertTrue(gate.forgetAll())
    }

    /** With the table unusable nothing is confirmed, and the old file is neither read for the answer nor deleted. */
    @Test
    fun anUnavailableStoreFailsClosedAndKeepsTheOldFile() {
        val name = "homenet_gate_failclosed_test"
        val home = NetworkFingerprint.of("192.168.9.1", "192.168.9.1", listOf("192.168.9.1"), "192.168.9.0/24")!!
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putStringSet(ConfirmedNetworkBook.LEGACY_KEY, setOf(home.hash)).commit()
        try {
            val broken = object : ConfirmedNetworkTable {
                override fun read(): String? = throw UnsatisfiedLinkError("libsqlcipher.so missing")
                override fun write(text: String) = throw java.io.IOException("store unavailable")
                override fun update(transform: (String?) -> String) = throw UnsatisfiedLinkError("libsqlcipher.so missing")
            }
            val gate = NetworkGate(context, ConfirmedNetworkBook(broken, PrefsNetworkFile(context, name)))
            assertFalse(gate.isConfirmed(home))
            assertFalse(gate.confirm(home))
            assertEquals(0, gate.confirmedCount())
            assertTrue(gate.migrate() is NetworkMigration.Failed)
            assertTrue("the old file is untouched", plaintextFile(name).exists())
            assertEquals(setOf(home.hash), context.getSharedPreferences(name, Context.MODE_PRIVATE).getStringSet(ConfirmedNetworkBook.LEGACY_KEY, null))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    private fun plaintextFile(name: String) = java.io.File(java.io.File(context.dataDir, "shared_prefs"), "$name.xml")

    /** The emulator's virtual Wi-Fi has a gateway and a prefix, so the fingerprint path itself gets exercised. */
    @Test
    fun currentWifiStateIsReadableWithoutLocation() {
        val state = NetworkGate(context).current()
        if (!state.onWifi) return
        val fingerprint = state.fingerprint ?: return
        assertTrue(fingerprint.label, fingerprint.gateway != null || fingerprint.prefix != null)
        assertEquals(NetworkFingerprint.PREFIX_LENGTH, fingerprint.prefixTag.length)
        assertFalse("the hash reveals no address", fingerprint.gateway?.let { fingerprint.hash.contains(it) } ?: false)
    }
}
