package io.github.stronghorse44.tunnels.backups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderScannerTest {
    private val now = Fixtures.NOW
    private val day = Fixtures.DAY

    private fun bundle(app: String, daysAgo: Long, schema: Long = 1) = Fixtures.header(app, schema, now - daysAgo * day)

    @Test
    fun newestPerAppAndFileCounts() {
        val m = Fixtures.Memory()
            .file("tunnels-a.fwx", bundle("tunnels", 40))
            .file("tunnels-b.fwx", bundle("tunnels", 3, schema = 2))
            .file("prikey.fwx", bundle("prikey", 10))
        val s = FolderScanner.scan(m, now)
        assertEquals(FolderState.OK, s.state)
        assertEquals(setOf("tunnels", "prikey"), s.apps.keys)
        assertEquals(2, s.apps.getValue("tunnels").files)
        assertEquals(now - 3 * day, s.apps.getValue("tunnels").newestMs)
        assertEquals(2L, s.apps.getValue("tunnels").schema)
        assertEquals(now - 10 * day, s.apps.getValue("prikey").newestMs)
        assertEquals(3, s.bundleFiles)
    }

    @Test
    fun unknownAppsAreOtherAndFilesWithoutTheExtensionAreNeverOpened() {
        val m = Fixtures.Memory()
            .file("x.fwx", bundle("someapp", 2))
            .file("photo.jpg", ByteArray(100_000) { 3 })
            .file("notes.txt", ByteArray(10))
            .file("README", ByteArray(10))
        val s = FolderScanner.scan(m, now)
        assertTrue(s.apps.isEmpty())
        assertEquals(1, s.other.files)
        assertEquals(now - 2 * day, s.other.newestMs)
        assertEquals(3, s.skipped)
        assertEquals(listOf("x.fwx"), m.opened)
    }

    @Test
    fun readsOnlyTheHeaderOfEveryFile() {
        val m = Fixtures.Memory()
            .file("a.fwx", Fixtures.header("prikey", createdMs = now - day, bodyBytes = 2_000_000))
            .file("b.fwx", Fixtures.header("lumen", createdMs = now - day, bodyBytes = 2_000_000))
            .file("junk.fwx", ByteArray(2_000_000) { 9 })
        FolderScanner.scan(m, now)
        assertEquals(3, m.bytesRead.size)
        assertTrue(m.bytesRead.toString(), m.bytesRead.values.all { it <= HeaderReader.MAX_BYTES })
    }

    @Test
    fun aFutureHeaderIsSuspiciousNotFresh() {
        val m = Fixtures.Memory()
            .file("future.fwx", Fixtures.header("prikey", createdMs = now + 3 * day))
            .file("ok.fwx", bundle("lumen", 5))
            .file("future2.fwx", Fixtures.header("lumen", createdMs = now + 10 * day))
            .file("edge.fwx", Fixtures.header("mardigras", createdMs = now + day - 1000))
        val s = FolderScanner.scan(m, now)
        assertEquals(0L, s.apps.getValue("prikey").newestMs)
        assertEquals(1, s.apps.getValue("prikey").suspicious)
        assertEquals(now - 5 * day, s.apps.getValue("lumen").newestMs)
        assertEquals(1, s.apps.getValue("lumen").suspicious)
        assertEquals("within a day of the clock is not suspicious", now + day - 1000, s.apps.getValue("mardigras").newestMs)
    }

    @Test
    fun legacyTunnelsExportUsesTheFileDate() {
        val m = Fixtures.Memory().file("old.tsnap", Fixtures.legacy(), modified = now - 20 * day)
        val s = FolderScanner.scan(m, now)
        val t = s.apps.getValue("tunnels")
        assertEquals(now - 20 * day, t.newestMs)
        assertTrue(t.fromFileDate)
        assertEquals(0L, t.schema)
    }

    @Test
    fun aNewerHeaderBeatsAnOlderLegacyFile() {
        val m = Fixtures.Memory()
            .file("old.tsnap", Fixtures.legacy(), modified = now - 20 * day)
            .file("new.fwx", bundle("tunnels", 2))
        val t = FolderScanner.scan(m, now).apps.getValue("tunnels")
        assertEquals(now - 2 * day, t.newestMs)
        assertFalse(t.fromFileDate)
        assertEquals(2, t.files)
    }

    @Test
    fun followsSubfoldersTwoLevelsDown() {
        val m = Fixtures.Memory()
        val a = m.dir("prikey")
        val b = m.dir("deep", a)
        val c = m.dir("deeper", b)
        m.file("p.fwx", bundle("prikey", 1), dir = a)
        m.file("q.fwx", bundle("lumen", 1), dir = b)
        m.file("r.fwx", bundle("southbound", 1), dir = c)
        val s = FolderScanner.scan(m, now)
        assertEquals(setOf("prikey", "lumen"), s.apps.keys)
        assertEquals(1, s.skipped)
        assertEquals(listOf("p.fwx", "q.fwx"), m.opened.sorted())
    }

    @Test
    fun unreadableCandidatesAreCountedNotFatal() {
        val m = Fixtures.Memory()
            .file("bad.fwx", ByteArray(300) { 1 })
            .file("ok.fwx", bundle("prikey", 1))
        val s = FolderScanner.scan(m, now)
        assertEquals(1, s.unreadable)
        assertEquals(1, s.apps.getValue("prikey").files)
    }

    @Test
    fun anUnlistableFolderIsLost() {
        val m = Fixtures.Memory().also { it.failRoot = true }
        assertEquals(FolderState.LOST, FolderScanner.scan(m, now).state)
    }

    @Test
    fun stopsAtTheHeaderBound() {
        val m = Fixtures.Memory()
        // Ten app names with 100 files each: 64 per name are eligible, 640 in all, and 500 are read, a rank at a time.
        for (c in 'a'..'j') repeat(100) { m.file("$c-%03d.fwx".format(it), bundle("prikey", 1)) }
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(FolderScanner.MAX_HEADERS, m.opened.size)
        for (c in 'a'..'j') assertTrue("$c-099 is its name's newest", "$c-099.fwx" in m.opened)
        assertEquals("every name's newest was read and they all say prikey, so prikey is still judged", null, s.holdOf("prikey"))
    }

    @Test
    fun onePilesNeverStarvesAnotherAppsFiles() {
        val m = Fixtures.Memory()
        repeat(510) { m.file(Fixtures.name("tunnels", now - (it + 1) * 3_600_000L), Fixtures.header("tunnels", 1, now - (it + 1) * 3_600_000L)) }
        m.file(Fixtures.name("lumen", now - day), bundle("lumen", 1))
        m.file(Fixtures.name("mardigras", now - day), bundle("mardigras", 1))
        m.file(Fixtures.name("prikey", now - day), bundle("prikey", 1))
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(FolderScanner.MAX_PER_APP + 3, m.opened.size)
        assertEquals(setOf("tunnels", "lumen", "mardigras", "prikey"), s.apps.keys)
        for (id in s.apps.keys) assertEquals(id, null, s.holdOf(id))
        assertEquals("the newest tunnels file is read", now - 3_600_000L, s.apps.getValue("tunnels").newestMs)
    }

    @Test
    fun aNamePilesLimitDoesNotCountAnotherFolder() {
        val m = Fixtures.Memory()
        val a = m.dir("a")
        val b = m.dir("b")
        repeat(100) { m.file(Fixtures.name("prikey", now - (it + 1) * day), bundle("prikey", 1), dir = a) }
        repeat(100) { m.file(Fixtures.name("prikey", now - (it + 1) * day), bundle("prikey", 1), dir = b) }
        FolderScanner.scan(m, now)
        assertEquals(2 * FolderScanner.MAX_PER_APP, m.opened.size)
    }

    @Test
    fun aMixedPileWhoseUnreadFilesCouldBeAnyAppHoldsEveryApp() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_PER_APP + 6) { m.file("backup-%03d.fwx".format(it), bundle(if (it % 2 == 0) "prikey" else "tunnels", 1)) }
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(Hold.CUT, s.holdOf("lumen"))
        assertEquals(Hold.CUT, s.holdOf("prikey"))
    }

    @Test
    fun aListingCutAtTheEntryBoundHoldsEveryApp() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_ENTRIES) { m.file("photo-%05d.jpg".format(it), ByteArray(1)) }
        m.file("prikey.fwx", bundle("prikey", 1))
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertTrue(s.apps.isEmpty())
        assertEquals(Hold.CUT, s.holdOf("prikey"))
    }

    @Test
    fun emptyFolderIsOkAndEmpty() {
        val s = FolderScanner.scan(Fixtures.Memory(), now)
        assertEquals(FolderState.OK, s.state)
        assertTrue(s.apps.isEmpty())
        assertNull(s.apps["tunnels"])
    }

    @Test
    fun lumenPerItemFilesAreCountedByNameAndNeverOpened() {
        val m = Fixtures.Memory()
        repeat(600) { m.file("lumen-20261003-101500Z-$it.fwx", ByteArray(10)) }
        m.file("lumen-20261003-101500Z.fwx", bundle("lumen", 2))
        val s = FolderScanner.scan(m, now)
        assertFalse(s.truncated)
        val lumen = s.apps.getValue("lumen")
        assertEquals(600, lumen.items)
        assertEquals(1, lumen.files)
        assertEquals(now - 2 * day, lumen.newestMs)
        assertEquals(listOf("lumen-20261003-101500Z.fwx"), m.opened)
        assertEquals(601, s.bundleFiles)
        assertEquals(0, s.unreadable)
    }

    @Test
    fun theItemPatternDoesNotSwallowOtherAppsDatedNames() {
        assertTrue(FolderScanner.isLumenItem("lumen-20261003-101500Z-7.fwx"))
        assertTrue(FolderScanner.isLumenItem("lumen-20261003-101500Z-12.fwx"))
        assertFalse(FolderScanner.isLumenItem("lumen-20261003-101500Z.fwx"))
        assertFalse(FolderScanner.isLumenItem("prikey-2026-10-05.fwx"))
        assertFalse(FolderScanner.isLumenItem("tunnels-20261003Z-3.fwx"))
        assertFalse(FolderScanner.isLumenItem("lumen-20261003-101500Z-3.tsnap"))
        assertFalse("the spec's case: capital Z, lowercase name", FolderScanner.isLumenItem("LUMEN-20261003-101500Z-12.FWX"))
        assertFalse(FolderScanner.isLumenItem("lumen-20261003T101500Z-7.fwx"))
        assertFalse(FolderScanner.isLumenItem("lumen-20261003-101500z-7.fwx"))
    }

    @Test
    fun aNameThatIsNotTheSpecsItemFormIsNotAnItemAndIsRead() {
        assertFalse(FolderScanner.isLumenItem("lumen-baz-5.fwx"))
        val m = Fixtures.Memory().file("lumen-baz-5.fwx", ByteArray(40) { 1 })
        val s = FolderScanner.scan(m, now)
        assertEquals(listOf("lumen-baz-5.fwx"), m.opened)
        assertEquals(1, s.unreadable)
        assertTrue(s.apps.isEmpty())
    }

    @Test
    fun onlyItemFilesMeanAnExportWithoutItsManifest() {
        val m = Fixtures.Memory()
        repeat(3) { m.file("lumen-20261003-101500Z-$it.fwx", ByteArray(10)) }
        val a = FolderScanner.scan(m, now).apps.getValue("lumen")
        assertEquals(AppStatus.NO_MANIFEST, Freshness.status(true, a, now, 30))
    }

    @Test
    fun readsNewestNamesFirstAndSaysWhenItLeftSomeUnread() {
        val m = Fixtures.Memory()
        val total = FolderScanner.MAX_HEADERS + 12
        for (i in 0 until total) m.file("prikey-%04d.fwx".format(i), Fixtures.header("prikey", 1, now - (total - i) * day))
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(FolderScanner.MAX_PER_APP, m.opened.size)
        assertTrue("the newest name is read", "prikey-%04d.fwx".format(total - 1) in m.opened)
        assertFalse("the oldest name is left", "prikey-0000.fwx" in m.opened)
        assertEquals(now - day, s.apps.getValue("prikey").newestMs)
        assertEquals("its newest name was read, so it is still judged", null, s.holdOf("prikey"))
    }

    @Test
    fun aSameNamedFileInAnotherFolderIsReadToo() {
        val m = Fixtures.Memory()
        val a = m.dir("a")
        val b = m.dir("b")
        repeat(FolderScanner.MAX_HEADERS) { m.file("same-%03d.fwx".format(it), bundle("prikey", 5), modified = now - 9 * day, dir = a) }
        m.file("same-000.fwx", bundle("prikey", 1), modified = now - day, dir = b)
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals("same-000.fwx in b is read", now - day, s.apps.getValue("prikey").newestMs)
    }

    @Test
    fun aProviderRuntimeExceptionMakesOneFileUnreadableNotTheWholeScanLost() {
        val m = Fixtures.Memory()
            .file("bad.fwx", bundle("prikey", 1))
            .file("good.fwx", bundle("tunnels", 1))
        m.failOpen["bad.fwx"] = IllegalArgumentException("provider says no")
        val s = FolderScanner.scan(m, now)
        assertEquals(FolderState.OK, s.state)
        assertEquals(1, s.unreadable)
        assertEquals(1, s.faults)
        assertEquals(setOf("tunnels"), s.apps.keys)
        assertEquals("a file the provider failed on is not a missing backup", Hold.FAILED, s.heldAll)
    }

    @Test
    fun theOnlyFileOfAnAppFailingToOpenHoldsThatApp() {
        val m = Fixtures.Memory().file(Fixtures.name("prikey", now - day), bundle("prikey", 1))
        m.failOpen[Fixtures.name("prikey", now - day)] = IllegalStateException("provider")
        val s = FolderScanner.scan(m, now)
        assertEquals(1, s.faults)
        assertEquals(Hold.FAILED, s.holdOf("prikey"))
        assertEquals("only that app: the name says whose it is", null, s.holdOf("tunnels"))
        assertNull(s.heldAll)
    }

    @Test
    fun aFailingFileOlderThanTheNewestReadOneDoesNotHoldItsApp() {
        val m = Fixtures.Memory()
            .file(Fixtures.name("prikey", now - day), bundle("prikey", 1))
            .file(Fixtures.name("prikey", now - 9 * day), bundle("prikey", 9))
        m.failOpen[Fixtures.name("prikey", now - 9 * day)] = IllegalArgumentException("provider")
        val s = FolderScanner.scan(m, now)
        assertEquals(1, s.faults)
        assertNull(s.holdOf("prikey"))
    }

    @Test
    fun aFailingFileNewerThanTheNewestReadOneHoldsItsApp() {
        val m = Fixtures.Memory()
            .file(Fixtures.name("prikey", now - day), bundle("prikey", 1))
            .file(Fixtures.name("prikey", now - 9 * day), bundle("prikey", 9))
        m.failOpen[Fixtures.name("prikey", now - day)] = java.io.IOException("provider")
        assertEquals(Hold.FAILED, FolderScanner.scan(m, now).holdOf("prikey"))
    }

    @Test
    fun aFailingFileWithNoAppNameCouldBeAnyAppSoItHoldsThemAll() {
        val m = Fixtures.Memory().file("export.fwx", bundle("prikey", 1))
        m.failOpen["export.fwx"] = SecurityException("no grant")
        val s = FolderScanner.scan(m, now)
        assertEquals(Hold.FAILED, s.heldAll)
        assertEquals(Hold.FAILED, s.holdOf("lumen"))
    }

    @Test
    fun aSubfolderThatThrowsIsSkippedAndCounted() {
        val m = Fixtures.Memory()
        m.dir("broken")
        m.file("ok.fwx", bundle("prikey", 1))
        m.failList["broken"] = IllegalStateException("provider")
        val s = FolderScanner.scan(m, now)
        assertEquals(FolderState.OK, s.state)
        assertEquals(1, s.unreadable)
        assertEquals(1, s.faults)
        assertEquals(setOf("prikey"), s.apps.keys)
        assertEquals("what is in the folder that would not list could be any app's", Hold.FAILED, s.heldAll)
    }

    @Test
    fun aFileThatIsNotABundleIsNotAFault() {
        val m = Fixtures.Memory().file("bad.fwx", ByteArray(300) { 1 }).file("ok.fwx", bundle("prikey", 1))
        val s = FolderScanner.scan(m, now)
        assertEquals(0, s.faults)
        assertNull(s.heldAll)
    }

    @Test
    fun anOldFormatFileWithNoLastModifiedCountsAsPresentWithoutADate() {
        val m = Fixtures.Memory().file("old.tsnap", Fixtures.legacy(), modified = 0)
        val t = FolderScanner.scan(m, now).apps.getValue("tunnels")
        assertEquals(1, t.files)
        assertEquals(1, t.undated)
        assertEquals(0L, t.newestMs)
        assertEquals(AppStatus.UNKNOWN_DATE, Freshness.status(true, t, now, 30))
    }

    @Test
    fun scansARealDirectoryThroughFileFolderSource() {
        val dir = java.nio.file.Files.createTempDirectory("b06").toFile()
        try {
            java.io.File(dir, "p.fwx").writeBytes(bundle("prikey", 2))
            java.io.File(dir, "sub").mkdirs()
            java.io.File(dir, "sub/t.fwx").writeBytes(bundle("tunnels", 4))
            java.io.File(dir, "photo.jpg").writeBytes(ByteArray(10))
            val s = FolderScanner.scan(FileFolderSource(dir), now)
            assertEquals(setOf("prikey", "tunnels"), s.apps.keys)
            assertEquals(1, s.skipped)
            assertEquals(FolderState.LOST, FolderScanner.scan(FileFolderSource(java.io.File(dir, "missing")), now).state)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun future(app: String, n: Int) = Fixtures.name(app, now + (n + 2) * day)

    @Test
    fun moreFutureDatedFilesThanTheGroupCapKeepReadingUntilADateAppears() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_PER_APP + 6) { m.file(future("prikey", it), Fixtures.header("prikey", 1, now + (it + 2) * day)) }
        m.file(Fixtures.name("prikey", now - 40 * day), bundle("prikey", 40))
        m.file(Fixtures.name("prikey", now - 50 * day), bundle("prikey", 50))
        val s = FolderScanner.scan(m, now)
        val prikey = s.apps.getValue("prikey")
        assertEquals(now - 40 * day, prikey.newestMs)
        assertEquals(FolderScanner.MAX_PER_APP + 6, prikey.suspicious)
        assertEquals("it stopped at the first file that dated something", FolderScanner.MAX_PER_APP + 7, m.opened.size)
        assertEquals(AppStatus.STALE, Freshness.status(true, prikey, now, 30, s.holdOf("prikey")))
    }

    @Test
    fun aGroupOfOnlyFutureDatedFilesThatNeverGaveADateHoldsItsAppWhenSomeWereNotRead() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_HEADERS + 20) { m.file(future("prikey", it), Fixtures.header("prikey", 1, now + (it + 2) * day)) }
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(FolderScanner.MAX_HEADERS, m.opened.size)
        assertEquals(Hold.CUT, s.holdOf("prikey"))
        assertEquals(AppStatus.INCOMPLETE, Freshness.status(true, s.apps.getValue("prikey"), now, 30, s.holdOf("prikey")))
    }

    @Test
    fun fewFutureDatedFilesAreStillSuspiciousWhenAllWereRead() {
        val m = Fixtures.Memory()
        repeat(3) { m.file(future("prikey", it), Fixtures.header("prikey", 1, now + (it + 2) * day)) }
        val s = FolderScanner.scan(m, now)
        assertNull(s.holdOf("prikey"))
        assertEquals(AppStatus.SUSPICIOUS, Freshness.status(true, s.apps.getValue("prikey"), now, 30, s.holdOf("prikey")))
    }

    @Test
    fun theExtraReadsStopAtTheGlobalBoundAndOtherAppsKeepTheirTurn() {
        val m = Fixtures.Memory()
        repeat(FolderScanner.MAX_HEADERS) { m.file(future("mardigras", it), Fixtures.header("mardigras", 1, now + (it + 2) * day)) }
        m.file(Fixtures.name("prikey", now - 2 * day), bundle("prikey", 2))
        val s = FolderScanner.scan(m, now)
        assertEquals("prikey was read in the first round, before the pile", now - 2 * day, s.apps.getValue("prikey").newestMs)
        assertEquals(FolderScanner.MAX_HEADERS, m.opened.size)
    }

    @Test
    fun aFailureOnAFileNamedForTheAppIsNamedAndOneOnAnUnnamedFileIsNot() {
        val named = Fixtures.name("prikey", now - day)
        val a = Fixtures.Memory().file(named, bundle("prikey", 1))
        a.failOpen[named] = IllegalStateException("provider")
        assertEquals(setOf("prikey"), FolderScanner.scan(a, now).failedNamed)

        val b = Fixtures.Memory().file("backup-001.fwx", bundle("prikey", 1))
        b.failOpen["backup-001.fwx"] = IllegalStateException("provider")
        val sb = FolderScanner.scan(b, now)
        assertTrue(sb.failedNamed.isEmpty())
        assertEquals(Hold.FAILED, sb.holdOf("prikey"))
    }

    @Test
    fun aFileNamedForAnotherAppIsNotedOnTheAppItHolds() {
        val m = Fixtures.Memory()
            .file(Fixtures.name("prikey", now - 40 * day), bundle("tunnels", 40))
            .file(Fixtures.name("tunnels", now - 90 * day), bundle("tunnels", 90))
        assertTrue(FolderScanner.scan(m, now).apps.getValue("tunnels").newestMisnamed)
        val own = Fixtures.Memory().file(Fixtures.name("tunnels", now - 40 * day), bundle("tunnels", 40)).file("backup-1.fwx", bundle("tunnels", 90))
        assertFalse(FolderScanner.scan(own, now).apps.getValue("tunnels").newestMisnamed)
    }
}
