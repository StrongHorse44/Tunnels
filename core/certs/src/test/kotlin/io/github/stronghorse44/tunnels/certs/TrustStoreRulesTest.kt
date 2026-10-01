package io.github.stronghorse44.tunnels.certs

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustStoreRulesTest {
    private val root = CertSummary.of(TestCerts.root)
    private val leaf = CertSummary.of(TestCerts.leaf)
    private val distrusted = root.copy(subjectCn = "DigiNotar Root CA", subjectO = "DigiNotar", fingerprint = TestCerts.LEAF_FINGERPRINT.replace("28:01", "FF:FF"))

    private fun snapshot(vararg certs: Pair<String, CertSummary>): List<Observation> {
        val obs = certs.flatMap { (alias, s) -> TrustStoreKeys.observations(alias, s) }
        val system = certs.count { TrustStoreKeys.sourceOf(it.first) == TrustStoreKeys.SOURCE_SYSTEM }
        return obs + TrustStoreKeys.summaryObservations(system, certs.size - system)
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>?): List<FindingDraft> {
        val ctx = RuleContext(TrustStoreKeys.TUNNEL_ID, current, DiffEngine.diff(previous.orEmpty(), current), isFirstScan = previous == null)
        return TrustStoreRules.all.flatMap { it.evaluate(ctx) }
    }

    @Test
    fun observationsFollowTheSchema() {
        val obs = TrustStoreKeys.observations("user:abcd1234.0", root)
        assertEquals(TrustStoreKeys.certificateKeys, obs.map { it.key }.toSet())
        assertTrue(obs.all { it.subject == "user:abcd1234.0" && it.tunnelId == "trust_store" })
        val byKey = obs.associate { it.key to it.value }
        assertEquals("user", byKey[TrustStoreKeys.SOURCE])
        assertEquals(TestCerts.ROOT_FINGERPRINT, byKey[TrustStoreKeys.FINGERPRINT])
        assertEquals("Tunnels Test Root", byKey[TrustStoreKeys.SUBJECT])
        assertEquals("Tunnels Project", byKey[TrustStoreKeys.ORG])
        assertEquals("2036-09-28", byKey[TrustStoreKeys.EXPIRES])
        assertEquals("RSA 2048", byKey[TrustStoreKeys.KEY_ALGO])
        assertEquals("true", byKey[TrustStoreKeys.SELF_SIGNED])
        assertEquals(TrustStoreKeys.summaryKeys, TrustStoreKeys.summaryObservations(1, 2).map { it.key }.toSet())
    }

    @Test
    fun userCaIsAStateFindingThatClearsWhenRemoved() {
        val withUser = snapshot("system:aa.0" to root, "user:bb.0" to leaf)
        val drafts = evaluate(withUser, previous = null)
        val user = drafts.single()
        assertEquals(TrustStoreRules.USER_CA_INSTALLED, user.kind)
        assertEquals(Severity.WARN, user.severity)
        assertEquals("user:bb.0", user.subject)
        assertFalse(user.sticky)
        assertEquals(
            "User-installed certificate authority: leaf.example.test (28016530…868C4A34) — apps that trust user CAs can be intercepted",
            user.evidence,
        )
        // The user removes it: no state draft, only the sticky removal notice.
        val after = snapshot("system:aa.0" to root)
        val later = evaluate(after, previous = withUser)
        assertEquals(listOf(TrustStoreRules.CA_REMOVED), later.map { it.kind })
        assertEquals(Severity.INFO, later.single().severity)
        assertTrue(later.single().sticky)
        assertEquals("User-installed certificate authority removed since the last scan: leaf.example.test (28016530…868C4A34)", later.single().evidence)
    }

    @Test
    fun firstScanIsTheBaselineAndChangesFireLater() {
        val base = snapshot("system:aa.0" to root)
        assertTrue(evaluate(base, previous = null).isEmpty())
        // Unchanged: nothing.
        assertTrue(evaluate(base, previous = base).isEmpty())

        val grown = snapshot("system:aa.0" to root, "system:cc.0" to leaf, "user:bb.0" to leaf)
        val drafts = evaluate(grown, previous = base)
        val added = drafts.filter { it.kind == TrustStoreRules.CA_ADDED }.associateBy { it.subject }
        assertEquals(setOf("system:cc.0", "user:bb.0"), added.keys)
        assertEquals(Severity.WARN, added["system:cc.0"]!!.severity)
        assertEquals(Severity.CRITICAL, added["user:bb.0"]!!.severity)
        assertTrue(added.values.all { it.sticky })
        assertTrue(added["system:cc.0"]!!.evidence.startsWith("New system certificate authority since the last scan: leaf.example.test (28016530…868C4A34)"))
        assertTrue(added["user:bb.0"]!!.evidence.startsWith("New user-installed certificate authority since the last scan: leaf.example.test"))
        // One CA_ADDED per certificate, not one per observation key; plus the state finding for the user CA.
        assertEquals(2, drafts.count { it.kind == TrustStoreRules.CA_ADDED })
        assertEquals(1, drafts.count { it.kind == TrustStoreRules.USER_CA_INSTALLED })
        // The summary subject never produces findings even though its counts changed.
        assertTrue(drafts.none { it.subject == TrustStoreKeys.SUMMARY })
    }

    @Test
    fun distrustedRootIsCriticalWhereverItSits() {
        val drafts = evaluate(snapshot("system:dd.0" to distrusted), previous = null)
        val d = drafts.single()
        assertEquals(TrustStoreRules.DISTRUSTED_CA, d.kind)
        assertEquals(Severity.CRITICAL, d.severity)
        assertFalse(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("Distrusted certificate authority: DigiNotar Root CA (FFFF6530…868C4A34) — DigiNotar was breached"))
        // As a user CA it is both distrusted and user-installed.
        val kinds = evaluate(snapshot("user:dd.0" to distrusted), previous = null).map { it.kind }.toSet()
        assertEquals(setOf(TrustStoreRules.DISTRUSTED_CA, TrustStoreRules.USER_CA_INSTALLED), kinds)
    }

    @Test
    fun overviewListsUserAndDistrustedCertificates() {
        val obs = snapshot("system:aa.0" to root, "user:bb.0" to leaf, "system:dd.0" to distrusted)
        val overview = TrustStoreOverview.from(obs)
        assertEquals(2, overview.systemCount)
        assertEquals(1, overview.userCount)
        assertEquals(listOf("user:bb.0"), overview.userCas.map { it.alias })
        assertEquals("leaf.example.test", overview.userCas.single().subject)
        assertEquals("28016530…868C4A34", overview.userCas.single().shortFingerprint)
        assertEquals("2027-10-01", overview.userCas.single().expires)
        assertEquals(listOf("system:dd.0"), overview.distrusted.map { it.alias })
        assertEquals("DigiNotar", overview.distrusted.single().distrusted?.name)
        assertFalse(overview.isEmpty)
        assertTrue(TrustStoreOverview.from(emptyList()).isEmpty)
    }
}
