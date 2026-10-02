package io.github.stronghorse44.tunnels.traffic

import android.Manifest
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.dns.TrafficRules
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.runtime.TunnelUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scans through the provider as the engine would, on a stock emulator with no VPN consent and no
 * session ever started. The VPN itself is never started here.
 */
@RunWith(AndroidJUnit4::class)
class TrafficSmokeTest {
    private val keyPattern = Regex("dns:(domains30|queries30|top|trackerDomains30|trackerTop|encrypted30|blocked30)|sessions:count30|session:active|vpn:otherActive")

    @Test
    fun scansWithoutASession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = TrafficTunnels().create(context).single()
        assertEquals(TrafficKeys.TUNNEL_ID, module.id)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), module.requiredPermissions.map { it.permission })
        assertEquals(TrafficRules.all.size, module.rules.size)
        assertFalse((module as TunnelUi).showObservations)

        val access = module.specialAccess.single()
        assertEquals(VpnConsentActivity.ACTION, access.settingsAction)
        access.isGranted() // either way, it must not throw
        assertFalse(DnsVpnService.isRunning)

        var reports = 0
        val obs = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports >= 2)
        assertTrue(obs.all { it.tunnelId == TrafficKeys.TUNNEL_ID })
        assertTrue(obs.none { it.value.isEmpty() })
        assertTrue(obs.map { it.key }.filterNot(keyPattern::matches).isEmpty())

        val summary = obs.filter { it.subject == TrafficKeys.SUMMARY }.associate { it.key to it.value }
        assertEquals("false", summary[TrafficKeys.SESSION_ACTIVE])
        assertTrue(summary[TrafficKeys.VPN_OTHER_ACTIVE] in setOf("true", "false"))
        assertTrue(summary[TrafficKeys.SESSIONS_COUNT30]!!.toInt() >= 0)
        for ((subject, list) in obs.groupBy { it.subject }) {
            val keys = list.map { it.key }
            assertEquals(subject, keys.size, keys.toSet().size)
        }

        // A second scan describes the same world.
        val again = module.scan(ScanProgress.NONE)
        assertEquals(obs.sortedWith(compareBy({ it.subject }, { it.key })), again.sortedWith(compareBy({ it.subject }, { it.key })))

        val summaryActions = module.actionsFor(FindingDraft(module.id, TrafficKeys.SUMMARY, TrafficRules.OTHER_VPN_ACTIVE, Severity.INFO, "x"))
        assertEquals(listOf(FindingAction.OpenSettings(Settings.ACTION_VPN_SETTINGS, "VPN settings")), summaryActions)
        val own = module.actionsFor(FindingDraft(module.id, context.packageName, TrafficRules.TRACKER_DOMAINS, Severity.NOTICE, "x"))
        assertTrue(own.first() is FindingAction.OpenAppDetails)
        assertTrue("user apps get uninstall", own.any { it is FindingAction.RequestUninstall })
        val system = module.actionsFor(FindingDraft(module.id, "android", TrafficRules.TRACKER_DOMAINS, Severity.NOTICE, "x"))
        assertTrue(system.none { it is FindingAction.RequestUninstall })
        assertTrue(module.actionsFor(FindingDraft(module.id, "uid:1234", TrafficRules.TALKATIVE_APP, Severity.INFO, "x")).isNotEmpty())
        assertTrue(module.actionsFor(FindingDraft(module.id, TrafficKeys.UNKNOWN_SUBJECT, TrafficRules.TALKATIVE_APP, Severity.INFO, "x")).isNotEmpty())
    }

    @Test
    fun vpnStatusAnswersWithoutThrowing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VpnStatus.anyVpnActive(context)
        VpnStatus.otherVpnActive(context)
        // The guarded prepare() helpers agree with each other and never throw; on a VPN-free emulator with
        // no consent given, consent is needed and the dialog intent exists.
        val needed = VpnStatus.consentNeeded(context)
        val intent = VpnStatus.consentIntent(context)
        assertEquals(needed, intent != null)
        if (!VpnStatus.anyVpnActive(context)) assertTrue("fresh emulator needs consent", needed)
        assertFalse(DnsVpnService.state.value.ending)
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)!!
        val resolvers = VpnStatus.resolversOf(cm, VpnStatus.underlyingNetwork(cm))
        assertTrue(resolvers.isNotEmpty())
        assertEquals(listOf(VpnStatus.FALLBACK_RESOLVER), VpnStatus.resolversOf(cm, null))
        VpnStatus.privateDnsHost(context) // strict mode or not, it must not throw
        assertFalse("blocking is off until the user turns it on", DnsVpnService.policy.value.enabled)
    }
}
