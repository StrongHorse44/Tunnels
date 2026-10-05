package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkTable
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import io.github.stronghorse44.tunnels.lan.NetworkMigration
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

        for (kind in listOf(LanRules.UPNP_IGD_ENABLED, LanRules.NEW_HOST, LanRules.CAMERA_OPEN_WEB)) {
            val actions = module.actionsFor(FindingDraft(module.id, "x", kind, Severity.INFO, "e"))
            assertTrue(kind, actions.isNotEmpty())
            assertTrue(kind, (actions.first() as FindingAction.Perform).run().length > 40)
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
