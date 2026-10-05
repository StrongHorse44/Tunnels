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
        repeat(FolderScanner.MAX_HEADERS + 5) { m.file("f$it.fwx", bundle("prikey", 1)) }
        val s = FolderScanner.scan(m, now)
        assertTrue(s.truncated)
        assertEquals(FolderScanner.MAX_HEADERS, m.opened.size)
    }

    @Test
    fun emptyFolderIsOkAndEmpty() {
        val s = FolderScanner.scan(Fixtures.Memory(), now)
        assertEquals(FolderState.OK, s.state)
        assertTrue(s.apps.isEmpty())
        assertNull(s.apps["tunnels"])
    }
}
