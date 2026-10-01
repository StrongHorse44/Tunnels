package io.github.stronghorse44.tunnels.surroundings

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.ble.SurroundingsKeys
import io.github.stronghorse44.tunnels.ble.SurroundingsRules
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the surroundings scan on the emulator as the engine would. A stock emulator has no Bluetooth
 * adapter and, before anything is granted, no permissions: the scan must still come back with a
 * well-formed summary that says so, and must finish inside the engine's patience.
 */
@RunWith(AndroidJUnit4::class)
class SurroundingsSmokeTest {
    private companion object {
        const val TAG = "SurroundingsSmokeTest"
        val availability = setOf(
            SurroundingsKeys.AVAILABLE_YES, SurroundingsKeys.AVAILABLE_NO_ADAPTER, SurroundingsKeys.AVAILABLE_OFF,
            SurroundingsKeys.AVAILABLE_NO_PERMISSION, SurroundingsKeys.AVAILABLE_FAILED,
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
            FindingDraft(module.id, "tracker:findmy", SurroundingsRules.TRACKER_FOLLOWING, Severity.CRITICAL, ""),
            FindingDraft(module.id, "tracker:tile", SurroundingsRules.NEW_TRACKER_TYPE, Severity.NOTICE, "", sticky = true),
            FindingDraft(module.id, "Cafe", SurroundingsRules.OPEN_WIFI_CONNECTED, Severity.NOTICE, ""),
            FindingDraft(module.id, "Office", SurroundingsRules.EVIL_TWIN_SUSPECT, Severity.WARN, ""),
            FindingDraft(module.id, SurroundingsKeys.CELL_SUMMARY, SurroundingsRules.CELL_DOWNGRADE, Severity.WARN, ""),
            FindingDraft(module.id, SurroundingsKeys.CELL_SUMMARY, SurroundingsRules.CELL_DOWNGRADED, Severity.WARN, "", sticky = true),
        )
        for (d in drafts) {
            val actions = module.actionsFor(d)
            assertTrue(d.kind, actions.isNotEmpty())
            assertTrue(d.kind, actions.all { it.label.isNotBlank() })
        }
        assertEquals(3, module.actionsFor(drafts[0]).size)
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
    }
}
