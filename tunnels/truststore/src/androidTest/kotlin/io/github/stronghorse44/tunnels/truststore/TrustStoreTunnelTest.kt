package io.github.stronghorse44.tunnels.truststore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.certs.TrustStoreKeys
import io.github.stronghorse44.tunnels.certs.TrustStoreOverview
import io.github.stronghorse44.tunnels.certs.TrustStoreRules
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.ScanProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scans the real AndroidCAStore on the device or emulator and checks the observations against the schema. */
@RunWith(AndroidJUnit4::class)
class TrustStoreTunnelTest {
    private val fingerprint = Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$")
    private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    @Test
    fun scanListsTheSystemRootsWithinTheSchema() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val module = TrustStoreTunnels().create(context).single()
        assertEquals(TrustStoreKeys.TUNNEL_ID, module.id)
        assertTrue(module.requiredPermissions.isEmpty())

        var reports = 0
        val observations = module.scan { _, _, _ -> reports++ }
        assertTrue("progress reported", reports > 0)
        assertTrue(observations.all { it.tunnelId == module.id })

        val summary = observations.filter { it.subject == TrustStoreKeys.SUMMARY }.associate { it.key to it.value }
        assertEquals(TrustStoreKeys.summaryKeys, summary.keys)
        val systemCount = summary.getValue(TrustStoreKeys.SYSTEM_COUNT).toInt()
        val userCount = summary.getValue(TrustStoreKeys.USER_COUNT).toInt()
        assertTrue("a stock image ships dozens of system roots, saw $systemCount", systemCount > 20)
        assertTrue(userCount >= 0)
        assertEquals("0", summary.getValue(TrustStoreKeys.SKIPPED_COUNT))

        val bySubject = observations.filter { it.subject != TrustStoreKeys.SUMMARY }.groupBy { it.subject }
        assertEquals(systemCount, bySubject.keys.count { it.startsWith("system:") })
        assertEquals(userCount, bySubject.keys.count { it.startsWith("user:") })
        for ((alias, rows) in bySubject) {
            val byKey = rows.associate { it.key to it.value }
            assertEquals(alias, TrustStoreKeys.certificateKeys, byKey.keys)
            assertEquals(alias, TrustStoreKeys.sourceOf(alias), byKey[TrustStoreKeys.SOURCE])
            assertTrue(alias, fingerprint.matches(byKey.getValue(TrustStoreKeys.FINGERPRINT)))
            assertTrue(alias, isoDate.matches(byKey.getValue(TrustStoreKeys.EXPIRES)))
            assertTrue(alias, byKey.getValue(TrustStoreKeys.SUBJECT).isNotBlank())
            assertTrue(alias, byKey[TrustStoreKeys.SELF_SIGNED] in setOf("true", "false"))
            assertTrue(alias, byKey.getValue(TrustStoreKeys.KEY_ALGO).isNotBlank())
        }
        // Roots are self-signed; nearly every system root on a stock image says so.
        val selfSigned = bySubject.values.count { rows -> rows.any { it.key == TrustStoreKeys.SELF_SIGNED && it.value == "true" } }
        assertTrue("$selfSigned of ${bySubject.size} self-signed", selfSigned * 2 > bySubject.size)

        // The first scan is the baseline: change rules stay quiet, and anything a state rule says has actions.
        val drafts = module.rules.flatMap { it.evaluate(RuleContext(module.id, observations, emptyList(), isFirstScan = true)) }
        assertTrue(drafts.none { it.kind == TrustStoreRules.CA_ADDED || it.kind == TrustStoreRules.CA_REMOVED })
        assertTrue(drafts.all { module.actionsFor(it).size == 2 })

        val overview = TrustStoreOverview.from(observations)
        assertEquals(systemCount, overview.systemCount)
        assertEquals(userCount, overview.userCas.size)
    }
}
