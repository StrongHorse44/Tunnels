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
        override suspend fun openBackups(): String = "backups".also { calls += it }
        override suspend fun forgetFolder(): String = "forgot".also { calls += it }
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
        assertEquals(listOf("backups|folder|BACKUP_FOLDER_LOST"), findings(m, seen).map { it.id })
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

    @Test
    fun aLostFolderRaisesAWarningInsteadOfClearingTheStaleFindingsUnseen() {
        val two = BackupSettings(seen = setOf("tunnels", "prikey"))
        // Stale findings exist from an earlier scan; the folder is then deleted.
        val before = Fixtures.Memory().file("p.fwx", bundle("prikey", 60)).file("t.fwx", bundle("tunnels", 70))
        val stale = findings(before, two)
        assertEquals(setOf("backups|prikey|BACKUP_STALE", "backups|tunnels|BACKUP_STALE"), stale.map { it.id }.toSet())

        val gone = Fixtures.Memory().also { it.failRoot = true }
        val obs = BackupObservations.build(FolderScanner.scan(gone, now), two, now, today)
        val update = FindingsEngine.derive(RuleContext("backups", obs, emptyList(), false), rules, actions::forDraft, stale, Instant.ofEpochMilli(now))
        val lost = update.upserts.single()
        assertEquals("backups|folder|BACKUP_FOLDER_LOST", lost.id)
        assertEquals(Severity.WARN, lost.severity)
        assertEquals(listOf("Open Backups to choose the folder", "Forget the folder"), lost.actions.map { it.label })
        assertEquals("the stale findings are replaced, not left looking current", 2, update.removals.size)
        assertEquals("backups", runSuspend { (lost.actions.first() as FindingAction.Perform).run() }.let { env.calls.last() })

        // The folder comes back: the warning goes.
        val back = BackupObservations.build(FolderScanner.scan(before, now), two, now, today)
        val cleared = FindingsEngine.derive(RuleContext("backups", back, emptyList(), false), rules, actions::forDraft, update.upserts, Instant.ofEpochMilli(now))
        assertTrue(cleared.removals.contains("backups|folder|BACKUP_FOLDER_LOST"))
    }

    @Test
    fun noFolderChosenIsNotALostFolder() {
        val obs = BackupObservations.build(FolderScan.NONE, seen, now, today)
        assertTrue(rules.flatMap { it.evaluate(RuleContext("backups", obs, emptyList(), true)) }.isEmpty())
    }

    @Test
    fun theLumenPerItemProbeRaisesNothingFalse() {
        // 600 per-item files fill the folder; the manifest is fresh, the other apps are fresh.
        val m = Fixtures.Memory()
        repeat(600) { m.file("lumen-20261003-101500Z-$it.fwx", ByteArray(50_000) { 7 }) }
        m.file("lumen-20261003-101500Z.fwx", bundle("lumen", 2))
        m.file("tunnels.fwx", bundle("tunnels", 1)).file("prikey.fwx", bundle("prikey", 3))
        val out = findings(m, BackupSettings(seen = setOf("lumen", "tunnels", "prikey")))
        assertTrue(out.map { it.id }.toString(), out.isEmpty())
        assertEquals("the item files are never opened", setOf("lumen-20261003-101500Z.fwx", "tunnels.fwx", "prikey.fwx"), m.opened.toSet())
    }

    private fun statusOf(f: Fixtures.Memory, settings: BackupSettings, app: String) =
        BackupView.from(BackupObservations.build(FolderScanner.scan(f, now), settings, now, today)).apps.first { it.app.id == app }.status

    private val four = BackupSettings(seen = setOf("tunnels", "lumen", "mardigras", "prikey"))

    @Test
    fun fiveHundredAndTenTunnelsFilesDoNotMakeTheOtherAppsIncomplete() {
        val m = Fixtures.Memory()
        repeat(510) { m.file(Fixtures.name("tunnels", now - day - it * 3_600_000L), bundle("tunnels", 1)) }
        m.file(Fixtures.name("lumen", now - 2 * day), bundle("lumen", 2))
        m.file(Fixtures.name("mardigras", now - 3 * day), bundle("mardigras", 3))
        m.file(Fixtures.name("prikey", now - 4 * day), bundle("prikey", 4))
        val out = findings(m, four)
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        assertEquals("every watched app was judged, so it is only a notice", Severity.NOTICE, out.single().severity)
        for (app in listOf("tunnels", "lumen", "mardigras", "prikey")) assertEquals(app, AppStatus.FRESH, statusOf(m, four, app))
    }

    @Test
    fun aStaleAppIsStillCalledStaleWhenItsOwnPileIsTooBigToRead() {
        val m = Fixtures.Memory()
        repeat(510) { m.file(Fixtures.name("mardigras", now - 40 * day - it * day), bundle("mardigras", 40 + it.toLong())) }
        val out = findings(m, BackupSettings(seen = setOf("mardigras")))
        assertEquals(setOf("backups|mardigras|BACKUP_STALE", "backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id }.toSet())
        assertEquals(Severity.WARN, out.first { it.kind == BackupRules.STALE }.severity)
        assertEquals("no watched app is hidden", Severity.NOTICE, out.first { it.kind == BackupRules.SCAN_INCOMPLETE }.severity)
        assertEquals(AppStatus.STALE, statusOf(m, BackupSettings(seen = setOf("mardigras")), "mardigras"))
    }

    @Test
    fun aWatchedAppTheScanCouldNotSeeMakesTheIncompleteFindingAWarning() {
        // 20,000 other files fill the listing; prikey sorts after them and is never listed.
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_ENTRIES) { m.file("photo-%05d.jpg".format(it), ByteArray(1)) }
        m.file(Fixtures.name("prikey", now - 90 * day), bundle("prikey", 90))
        val out = findings(m, BackupSettings(seen = setOf("prikey")))
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        val f = out.single()
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.evidence, f.evidence.contains("Prikey is not judged"))
        assertEquals(listOf("Open Backups"), f.actions.map { it.label })
        assertEquals(AppStatus.INCOMPLETE, statusOf(m, BackupSettings(seen = setOf("prikey")), "prikey"))
    }

    @Test
    fun aMixedPileHidesEveryAppThatIsNotFreshAndWarns() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_PER_APP + 6) { m.file("backup-%03d.fwx".format(it), bundle(if (it % 2 == 0) "prikey" else "tunnels", 1)) }
        m.file(Fixtures.name("mardigras", now - 80 * day), bundle("mardigras", 80))
        val s = BackupSettings(seen = setOf("prikey", "tunnels", "mardigras"))
        val out = findings(m, s)
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        assertEquals(Severity.WARN, out.single().severity)
        assertEquals(AppStatus.FRESH, statusOf(m, s, "prikey"))
        assertEquals(AppStatus.INCOMPLETE, statusOf(m, s, "mardigras"))
    }

    @Test
    fun lumenItemFilesWithoutAManifestAreAnIncompleteExportWarning() {
        val m = Fixtures.Memory()
        repeat(600) { m.file(Fixtures.name("lumen", now - 200 * day, item = it), ByteArray(10), modified = now - 200 * day) }
        val s = BackupSettings(seen = setOf("lumen"))
        val out = findings(m, s)
        val f = out.single()
        assertEquals("backups|lumen|BACKUP_MISSING", f.id)
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.evidence, f.evidence.contains("Lumen item files (600) but no manifest: the export is incomplete and can't be imported"))
        assertEquals(listOf("Open Lumen", "Stop watching Lumen"), f.actions.map { it.label })
        assertEquals(AppStatus.NO_MANIFEST, statusOf(m, s, "lumen"))
        assertTrue("the item files are never opened", m.opened.isEmpty())
        // The manifest comes back (the export is whole): the finding clears.
        m.file(Fixtures.name("lumen", now - 2 * day), bundle("lumen", 2))
        assertTrue(findings(m, s).isEmpty())
    }

    @Test
    fun lumenItemFilesWithoutAManifestInATruncatedScanAreIncompleteNotUnknownDate() {
        val m = Fixtures.Memory()
        repeat(600) { m.file(Fixtures.name("lumen", now - 200 * day, item = it), ByteArray(10)) }
        repeat(FolderScanner.MAX_PER_APP + 6) { m.file("backup-%03d.fwx".format(it), bundle(if (it % 2 == 0) "prikey" else "tunnels", 1)) }
        val s = BackupSettings(seen = setOf("lumen"))
        assertEquals(AppStatus.INCOMPLETE, statusOf(m, s, "lumen"))
        val out = findings(m, s)
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        assertEquals(Severity.WARN, out.single().severity)
    }

    @Test
    fun theOnlyFileOfAnAppFailingToOpenIsNotReportedAsAMissingBackup() {
        val name = Fixtures.name("prikey", now - day)
        val m = Fixtures.Memory().file(name, bundle("prikey", 1))
        m.failOpen[name] = IllegalStateException("provider")
        val s = BackupSettings(seen = setOf("prikey"))
        val out = findings(m, s)
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        val f = out.single()
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.evidence, f.evidence.contains("A Prikey file could not be read, so Prikey is not judged"))
        assertFalse(f.evidence.contains("No Prikey bundle"))
        assertEquals(listOf("Open Backups"), f.actions.map { it.label })
        assertEquals(AppStatus.UNREADABLE, statusOf(m, s, "prikey"))
        val view = BackupView.from(BackupObservations.build(FolderScanner.scan(m, now), s, now, today))
        assertEquals(1, view.faults)
    }

    @Test
    fun aFileThatFailsOnAnAppNobodyWatchesIsOnlyANotice() {
        val name = Fixtures.name("prikey", now - day)
        val m = Fixtures.Memory().file(name, bundle("prikey", 1))
        m.failOpen[name] = IllegalStateException("provider")
        val out = findings(m, BackupSettings())
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        assertEquals(Severity.NOTICE, out.single().severity)
    }

    @Test
    fun aStaleAppIsStillJudgedWhenOnlyAnOlderFileFailsToOpen() {
        val newest = Fixtures.name("prikey", now - 45 * day)
        val older = Fixtures.name("prikey", now - 60 * day)
        val m = Fixtures.Memory().file(newest, bundle("prikey", 45)).file(older, bundle("prikey", 60))
        m.failOpen[older] = IllegalStateException("provider")
        val out = findings(m, BackupSettings(seen = setOf("prikey")))
        assertEquals(setOf("backups|prikey|BACKUP_STALE", "backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id }.toSet())
        assertEquals(Severity.NOTICE, out.first { it.kind == BackupRules.SCAN_INCOMPLETE }.severity)
    }

    @Test
    fun aSubfolderThatCannotBeListedHidesAStaleAppAndWarns() {
        val m = Fixtures.Memory()
        m.dir("broken")
        m.file(Fixtures.name("prikey", now - 90 * day), bundle("prikey", 90))
        m.failList["broken"] = IllegalStateException("provider")
        val out = findings(m, BackupSettings(seen = setOf("prikey")))
        assertEquals(listOf("backups|folder|BACKUP_SCAN_INCOMPLETE"), out.map { it.id })
        assertEquals(Severity.WARN, out.single().severity)
        assertTrue(out.single().evidence, out.single().evidence.contains("A Prikey file could not be read"))
    }

    @Test
    fun forgettingTheFolderSaysMonitoringIsOffUntilOneIsChosen() {
        assertTrue(BackupActions.FOLDER_FORGOTTEN, BackupActions.FOLDER_FORGOTTEN.contains("monitoring is now off"))
        assertTrue(BackupActions.FOLDER_FORGOTTEN.contains("until you choose a folder"))
    }

    @Test
    fun anOldFormatExportWithNoFileDateIsPresentNotMissing() {
        val m = Fixtures.Memory().file("old.tsnap", Fixtures.legacy(), modified = 0)
        val s = BackupSettings(seen = setOf("tunnels"))
        assertTrue(findings(m, s).isEmpty())
        val view = BackupView.from(BackupObservations.build(FolderScanner.scan(m, now), s, now, today))
        val row = view.apps.first { it.app.id == "tunnels" }
        assertEquals(AppStatus.UNKNOWN_DATE, row.status)
        assertEquals(1, row.files)
        assertEquals(1, row.undated)
    }

    @Test
    fun anUndatedOldFormatFileKeepsAnOldFormatDatedFileFromBeingCalledStale() {
        val m = Fixtures.Memory()
            .file("tunnels-undated.tsnap", Fixtures.legacy(), modified = 0)
            .file("tunnels-dated.tsnap", Fixtures.legacy(), modified = now - 80 * day)
        assertTrue(findings(m, BackupSettings(seen = setOf("tunnels"))).isEmpty())
        assertEquals(AppStatus.UNKNOWN_DATE, statusOf(m, BackupSettings(seen = setOf("tunnels")), "tunnels"))
    }

    @Test
    fun anUndatedOldFormatFileCannotKeepA400DayFwxBundleFromBeingCalledStale() {
        val bundleName = Fixtures.name("tunnels", now - 400 * day)
        val alone = Fixtures.Memory().file(bundleName, bundle("tunnels", 400))
        val both = Fixtures.Memory().file(bundleName, bundle("tunnels", 400)).file("tunnels-undated.tsnap", Fixtures.legacy(), modified = 0)
        val s = BackupSettings(seen = setOf("tunnels"))
        assertEquals(listOf("backups|tunnels|BACKUP_STALE"), findings(alone, s).map { it.id })
        assertEquals(listOf("backups|tunnels|BACKUP_STALE"), findings(both, s).map { it.id })
        assertTrue(findings(both, s).single().evidence.contains("400 days ago"))
    }
}
