package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WatchPolicyTest {
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val everything = (WatchPolicy.ALWAYS + WatchPolicy.APP_FILES + WatchPolicy.OPTIONAL).toSet()
    private val recent = WatchStatus(lastRunAt = now.minus(Duration.ofHours(6)).toEpochMilli(), lastAppFilesAt = now.minus(Duration.ofHours(6)).toEpochMilli(), bootCount = 3, packageSequence = 40)

    private fun f(tunnel: String, kind: String, severity: Severity) = Finding(tunnel, "pkg", kind, severity, now, now, "e", emptyList())

    @Test
    fun theFirstCheckReadsEverything() {
        val plan = WatchPolicy.plan(everything, WatchSettings(enabled = true), WatchStatus(), packagesChanged = true, bootCount = 3, now = now)
        assertTrue(plan.appFiles)
        assertEquals("first check", plan.reason)
        assertEquals(WatchPolicy.ALWAYS + WatchPolicy.APP_FILES, plan.tunnels)
    }

    @Test
    fun aQuietPhoneGetsTheQuickCheck() {
        val plan = WatchPolicy.plan(everything, WatchSettings(enabled = true), recent, packagesChanged = false, bootCount = 3, now = now)
        assertFalse(plan.appFiles)
        assertEquals(WatchPolicy.ALWAYS, plan.tunnels)
        assertEquals("quick check", plan.reason)
    }

    @Test
    fun appChangesRestartsAndADayReadTheAppFiles() {
        assertEquals("apps changed", WatchPolicy.plan(everything, WatchSettings(), recent, true, 3, now).reason)
        assertEquals("after a restart", WatchPolicy.plan(everything, WatchSettings(), recent, false, 4, now).reason)
        val dayOld = recent.copy(lastAppFilesAt = now.minus(Duration.ofHours(25)).toEpochMilli())
        val plan = WatchPolicy.plan(everything, WatchSettings(), dayOld, false, 3, now)
        assertEquals("daily app-file check", plan.reason)
        assertTrue(plan.tunnels.containsAll(WatchPolicy.APP_FILES))
    }

    @Test
    fun optionalTunnelsRunOnlyWhenChosenAndAvailable() {
        val chosen = WatchSettings(extra = setOf("timeline", "notifications"))
        assertTrue("timeline" in WatchPolicy.plan(everything, chosen, recent, false, 3, now).tunnels)
        val noAccess = everything - "timeline"
        val plan = WatchPolicy.plan(noAccess, chosen, recent, false, 3, now)
        assertFalse("timeline" in plan.tunnels)
        assertTrue("notifications" in plan.tunnels)
        assertFalse("permissions missing from the build is skipped", "permissions" in WatchPolicy.plan(everything - "permissions", chosen, recent, false, 3, now).tunnels)
    }

    @Test
    fun onlyFindingsAtOrAboveTheThresholdNotifyMostSevereFirst() {
        val added = listOf(f("permissions", "PERMISSION_GAINED", Severity.WARN), f("trust_store", "USER_CA_INSTALLED", Severity.CRITICAL), f("doors", "LINKS_CHANGED", Severity.NOTICE))
        assertEquals(listOf(Severity.CRITICAL, Severity.WARN), WatchPolicy.toNotify(added, Severity.WARN).map { it.severity })
        assertEquals(1, WatchPolicy.toNotify(added, Severity.CRITICAL).size)
    }

    @Test
    fun theNotificationNamesTunnelsAndKindsNeverApps() {
        val added = listOf(f("trust_store", "USER_CA_INSTALLED", Severity.CRITICAL), f("permissions", "PERMISSION_GAINED", Severity.WARN), f("permissions", "NETWORK_ENABLED", Severity.WARN))
        val n = WatchPolicy.notification(added, mapOf("trust_store" to "Trust store", "permissions" to "Permissions")::getValue)!!
        assertEquals("3 new findings to review", n.title)
        assertEquals("1 critical, 2 warnings", n.text)
        assertEquals(listOf("Trust store: User CA installed", "Permissions: Permission gained", "Permissions: Network enabled"), n.lines)
        assertFalse(n.lines.any { it.contains("pkg") })
        assertEquals("New findings to review", n.publicText)
        assertNull(WatchPolicy.notification(emptyList()) { it })
    }

    @Test
    fun longListsAreCut() {
        val added = (1..7).map { f("permissions", "KIND_$it", Severity.WARN) }
        val n = WatchPolicy.notification(added) { "Permissions" }!!
        assertEquals(6, n.lines.size)
        assertEquals("and 2 more", n.lines.last())
    }
}
