package io.github.stronghorse44.tunnels.backups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationsTest {
    private val now = Fixtures.NOW
    private val day = Fixtures.DAY

    @Test
    fun storesSummariesOnly() {
        val f = Fixtures.Memory()
            .file("my-secret-name.fwx", Fixtures.header("prikey", 2, now - 4 * day))
            .file("two.fwx", Fixtures.header("prikey", 2, now - 9 * day))
            .file("x.fwx", Fixtures.header("somethingelse", 1, now - day))
        val obs = BackupObservations.build(FolderScanner.scan(f, now), BackupSettings(), now, Fixtures.TODAY)
        assertTrue(obs.all { it.tunnelId == "backups" })
        assertTrue(obs.none { it.value.isEmpty() })
        val prikey = obs.filter { it.subject == "prikey" }.associate { it.key to it.value }
        assertEquals("fresh", prikey["status"])
        assertEquals("2", prikey["files"])
        assertEquals((now - 4 * day).toString(), prikey["newest_ms"])
        assertEquals("4", prikey["age_days"])
        assertEquals("2", prikey["schema"])
        // No file name and no unknown app ID appears anywhere in the stored values.
        assertFalse(obs.any { "secret" in it.value || "somethingelse" in it.value || "somethingelse" in it.subject })
        val other = obs.filter { it.subject == "other" }.associate { it.key to it.value }
        assertEquals("1", other["files"])
    }

    @Test
    fun ageIsTheOnlyKeyThatMovesWithTheClock() {
        assertEquals(setOf("age_days"), BackupKeys.VOLATILE)
        val f = Fixtures.Memory().file("a.fwx", Fixtures.header("prikey", 1, now - 4 * day))
        val a = BackupObservations.build(FolderScanner.scan(f, now), BackupSettings(), now, Fixtures.TODAY)
        val later = now + 2 * day
        val b = BackupObservations.build(FolderScanner.scan(f, later), BackupSettings(), later, Fixtures.TODAY.plusDays(2))
        val changed = a.zip(b).filter { it.first != it.second }.map { it.first.key }.toSet()
        assertEquals(setOf("age_days"), changed)
    }

    @Test
    fun viewReadsBackWhatTheScanStored() {
        val f = Fixtures.Memory()
            .file("a.fwx", Fixtures.header("lumen", 1, now - 40 * day))
            .file("junk.fwx", ByteArray(50))
            .file("pic.jpg", ByteArray(50))
        val obs = BackupObservations.build(FolderScanner.scan(f, now), BackupSettings(thresholdDays = 14), now, Fixtures.TODAY)
        val v = BackupView.from(obs)
        assertEquals(FolderState.OK, v.folder)
        assertEquals(1, v.bundles)
        assertEquals(1, v.unreadable)
        assertEquals(1, v.skipped)
        assertEquals(14, v.thresholdDays)
        val lumen = v.apps.first { it.app.id == "lumen" }
        assertEquals(AppStatus.STALE, lumen.status)
        assertTrue(lumen.tracked)
        assertEquals(40L, lumen.ageDays)
        assertEquals(BackupApps.all.size, v.apps.size)
        assertTrue(v.apps.any { it.app.id == "linx" })
        assertEquals(null, v.apps.first { it.app.id == "southbound" }.status)
    }
}
