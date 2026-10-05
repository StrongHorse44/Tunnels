package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.engine.FindingsEngine
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Folder -> scan -> observations -> rules -> findings with actions: the acceptance cases, on a fake folder. */
private fun <T> runSuspend(block: suspend () -> T): T {
    var out: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
    return out!!.getOrThrow()
}

class BackupFindingsTest {
    private val now = Fixtures.NOW
    private val day = Fixtures.DAY
    private val today: LocalDate = Fixtures.TODAY
    private val rules = BackupRules.all(ZoneOffset.UTC)

    private val env = object : BackupEnv {
        val calls = mutableListOf<String>()
        override suspend fun openApp(app: BackupApp): String = "opened ${app.id}".also { calls += it }
        override suspend fun openSnapshots(): String = "snapshots".also { calls += it }
        override suspend fun stopTracking(appId: String): String = "stopped $appId".also { calls += it }
        override suspend fun recordDrill(): String = "drill".also { calls += it }
    }
    private val actions = BackupActions(env)

    private fun bundle(app: String, daysAgo: Long) = Fixtures.header(app, 1, now - daysAgo * day)

    private fun findings(folder: Fixtures.Memory, settings: BackupSettings, existing: List<Finding> = emptyList()): List<Finding> {
        val scan = FolderScanner.scan(folder, now)
        val obs = BackupObservations.build(scan, settings, now, today)
        val update = FindingsEngine.derive(RuleContext(BackupKeys.TUNNEL_ID, obs, emptyList(), true), rules, actions::forDraft, existing, Instant.ofEpochMilli(now))
        return update.upserts
    }

    private val seen = BackupSettings(seen = setOf("tunnels", "prikey", "mardigras"))

    @Test
    fun aStaleHeaderYieldsAFindingWithAnAction() {
        val f = Fixtures.Memory()
            .file("t.fwx", bundle("tunnels", 2))
            .file("p.fwx", bundle("prikey", 45))
            .file("m.fwx", bundle("mardigras", 1))
        val out = findings(f, seen)
        assertEquals(listOf("backups|prikey|BACKUP_STALE"), out.map { it.id })
        val finding = out.single()
        assertEquals(Severity.WARN, finding.severity)
        assertTrue(finding.evidence, finding.evidence.contains("header says 2026-08-21"))
        assertTrue(finding.evidence.contains("45 days ago"))
        assertTrue(finding.evidence.contains("not checked without the passphrase"))
        assertFalse(finding.evidence.contains("verified"))
        val labels = finding.actions.map { it.label }
        assertEquals(listOf("Open Prikey", "Stop watching Prikey"), labels)
        runSuspend { (finding.actions.first() as FindingAction.Perform).run() }
        assertEquals(listOf("opened prikey"), env.calls)
    }

    @Test
    fun freshHeadersYieldNone() {
        val f = Fixtures.Memory()
            .file("t.fwx", bundle("tunnels", 2))
            .file("p.fwx", bundle("prikey", 29))
            .file("m.fwx", bundle("mardigras", 0))
        assertTrue(findings(f, seen).isEmpty())
    }

    @Test
    fun movingTheNewestOutMakesItMissingAndPuttingItBackClearsIt() {
        val full = Fixtures.Memory().file("p.fwx", bundle("prikey", 1)).file("t.fwx", bundle("tunnels", 1)).file("m.fwx", bundle("mardigras", 1))
        assertTrue(findings(full, seen).isEmpty())
        val moved = Fixtures.Memory().file("t.fwx", bundle("tunnels", 1)).file("m.fwx", bundle("mardigras", 1))
        val out = findings(moved, seen)
        assertEquals(listOf("backups|prikey|BACKUP_MISSING"), out.map { it.id })
        assertEquals("Open Prikey", out.single().actions.first().label)
        // Rescanning the full folder yields no drafts, so the engine removes the finding.
        val scan = FolderScanner.scan(full, now)
        val obs = BackupObservations.build(scan, seen, now, today)
        val update = FindingsEngine.derive(RuleContext("backups", obs, emptyList(), false), rules, actions::forDraft, out, Instant.ofEpochMilli(now))
        assertEquals(listOf("backups|prikey|BACKUP_MISSING"), update.removals)
        assertTrue(update.upserts.isEmpty())
    }

    @Test
    fun aOneDayThresholdMakesAnOldExportStale() {
        val f = Fixtures.Memory().file("p.fwx", bundle("prikey", 3))
        assertTrue(findings(f, BackupSettings(thresholdDays = 7, seen = setOf("prikey"))).isEmpty())
        val out = findings(f, BackupSettings(thresholdDays = 1, seen = setOf("prikey")))
        assertEquals(listOf("backups|prikey|BACKUP_STALE"), out.map { it.id })
    }

    @Test
    fun anAppNeverSeenAndNotSwitchedOnIsNotMissing() {
        assertTrue(findings(Fixtures.Memory(), BackupSettings()).isEmpty())
        val on = findings(Fixtures.Memory(), BackupSettings().withTracked("lumen", true))
        assertEquals(listOf("backups|lumen|BACKUP_MISSING"), on.map { it.id })
    }

    @Test
    fun anUntrackedStaleAppShowsItsDateButRaisesNothing() {
        val f = Fixtures.Memory().file("p.fwx", bundle("prikey", 400))
        val settings = BackupSettings().withTracked("prikey", false)
        assertTrue(findings(f, settings).isEmpty())
        val view = BackupView.from(BackupObservations.build(FolderScanner.scan(f, now), settings, now, today))
        val row = view.apps.first { it.app.id == "prikey" }
        assertEquals(AppStatus.UNTRACKED, row.status)
        assertEquals(400L, row.ageDays)
    }

    @Test
    fun aFolderThatCannotBeReadIsNotAMissingBackup() {
        val m = Fixtures.Memory().also { it.failRoot = true }
        assertTrue(findings(m, seen).isEmpty())
        val obs = BackupObservations.build(FolderScanner.scan(m, now), seen, now, today)
        assertEquals(FolderState.LOST, BackupView.from(obs).folder)
        assertTrue(obs.none { it.subject == "prikey" })
    }

    @Test
    fun noFolderYetHasNoFindingsEither() {
        val obs = BackupObservations.build(FolderScan.NONE, seen, now, today)
        val update = FindingsEngine.derive(RuleContext("backups", obs, emptyList(), true), rules, actions::forDraft, emptyList(), Instant.ofEpochMilli(now))
        assertTrue(update.upserts.isEmpty())
        assertEquals(FolderState.NONE, BackupView.from(obs).folder)
    }

    @Test
    fun aFutureDatedBundleDoesNotHideAStaleOne() {
        val f = Fixtures.Memory()
            .file("old.fwx", bundle("prikey", 60))
            .file("planted.fwx", Fixtures.header("prikey", 1, now + 30 * day))
        val out = findings(f, seen.copy(seen = setOf("prikey")))
        assertEquals(listOf("backups|prikey|BACKUP_STALE"), out.map { it.id })
    }

    @Test
    fun onlyFutureDatedBundlesRaiseTheSuspiciousFinding() {
        val f = Fixtures.Memory().file("planted.fwx", Fixtures.header("prikey", 1, now + 30 * day))
        val out = findings(f, BackupSettings(seen = setOf("prikey")))
        assertEquals(listOf("backups|prikey|BACKUP_DATE_SUSPICIOUS"), out.map { it.id })
        assertEquals(Severity.NOTICE, out.single().severity)
    }

    @Test
    fun theOldTunnelsFormatCountsByFileDateAndSaysSo() {
        val f = Fixtures.Memory().file("old.tsnap", Fixtures.legacy(), modified = now - 50 * day)
        val out = findings(f, BackupSettings(seen = setOf("tunnels")))
        val finding = out.single()
        assertEquals("backups|tunnels|BACKUP_STALE", finding.id)
        assertTrue(finding.evidence, finding.evidence.contains("old format"))
        assertEquals(listOf("Open Snapshots to export", "Stop watching Tunnels"), finding.actions.map { it.label })
    }

    @Test
    fun pusherServerHasNoAppToOpenSoItGetsHowToRefresh() {
        val f = Fixtures.Memory().file("s.fwx", bundle("pusher-server", 90))
        val finding = findings(f, BackupSettings(seen = setOf("pusher-server"))).single()
        assertEquals(listOf("How to refresh", "Stop watching Pusher server"), finding.actions.map { it.label })
        val text = runSuspend { (finding.actions.first() as FindingAction.Perform).run() }
        assertTrue(text, text.contains("backup.sh") && text.contains("Chromebook"))
    }

    @Test
    fun theRestoreDrillIsDueAfterNinetyDaysAndHasActions() {
        val obs = BackupObservations.build(FolderScan.NONE, BackupSettings(drill = today.minusDays(91)), now, today)
        val update = FindingsEngine.derive(RuleContext("backups", obs, emptyList(), true), rules, actions::forDraft, emptyList(), Instant.ofEpochMilli(now))
        val f = update.upserts.single()
        assertEquals("backups|drill|RESTORE_DRILL_DUE", f.id)
        assertEquals(Severity.NOTICE, f.severity)
        assertEquals(listOf("Open Snapshots to try an import", "I did a drill today"), f.actions.map { it.label })
        assertTrue(f.evidence.contains("91 days ago"))
    }

    @Test
    fun noDrillDateOrARecentOneRaisesNothing() {
        for (d in listOf(null, today.minusDays(89), today)) {
            val obs = BackupObservations.build(FolderScan.NONE, BackupSettings(drill = d), now, today)
            val update = FindingsEngine.derive(RuleContext("backups", obs, emptyList(), true), rules, actions::forDraft, emptyList(), Instant.ofEpochMilli(now))
            assertTrue("drill=$d", update.upserts.isEmpty())
        }
    }

    @Test
    fun everyFindingKindHasAtLeastOneAction() {
        for (kind in listOf(BackupRules.STALE, BackupRules.MISSING, BackupRules.DATE_SUSPICIOUS)) {
            for (app in BackupApps.all) {
                assertTrue("$kind ${app.id}", actions.forDraft(FindingDraft("backups", app.id, kind, Severity.WARN, "x")).isNotEmpty())
            }
        }
        assertTrue(actions.forDraft(FindingDraft("backups", "drill", BackupRules.DRILL_DUE, Severity.NOTICE, "x")).isNotEmpty())
        assertNotNull(BackupApps.byId("tunnels"))
    }
}
