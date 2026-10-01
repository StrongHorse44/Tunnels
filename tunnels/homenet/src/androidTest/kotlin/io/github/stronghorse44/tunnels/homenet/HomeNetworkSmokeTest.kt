package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a stock emulator nothing is confirmed (and NEARBY_WIFI_DEVICES is not granted), so the gate must
 * refuse quickly with a well-formed summary and open no sockets.
 */
@RunWith(AndroidJUnit4::class)
class HomeNetworkSmokeTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun refusesToScanWithoutAConfirmedNetwork() = runBlocking {
        val module = HomeNetTunnels().create(context).single()
        assertEquals(LanKeys.TUNNEL_ID, module.id)
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), module.requiredPermissions.map { it.permission })
        assertEquals(LanRules.all.size, module.rules.size)
        NetworkGate(context).forgetAll()

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
            reason in setOf(LanKeys.REASON_NO_PERMISSION, LanKeys.REASON_NO_WIFI, LanKeys.REASON_SSID_UNKNOWN, LanKeys.REASON_NOT_CONFIRMED),
        )
        assertTrue(obs.none { it.key == LanKeys.SCAN_SSID || it.key == LanKeys.HOSTS_TOTAL })
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
    fun gateRemembersOnlyHashes() {
        val gate = NetworkGate(context)
        gate.forgetAll()
        assertEquals(0, gate.confirmedCount())
        assertFalse(gate.isConfirmed("Test Net"))
        gate.confirm("Test Net")
        assertTrue(gate.isConfirmed("Test Net"))
        assertFalse(gate.isConfirmed("test net"))
        assertEquals(1, gate.confirmedCount())
        val stored = context.getSharedPreferences(NetworkGate.PREFS, Context.MODE_PRIVATE).getStringSet(NetworkGate.KEY_CONFIRMED, emptySet())!!
        assertEquals(setOf(GateHashing.ssidHash("Test Net")), stored)
        assertTrue(stored.none { it.contains("Test") })
        gate.forget("Test Net")
        assertFalse(gate.isConfirmed("Test Net"))
        gate.forgetAll()
    }
}
