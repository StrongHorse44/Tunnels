package io.github.stronghorse44.tunnels.snapshots

import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.export.BundleFormat
import io.github.stronghorse44.tunnels.export.EncryptedFile
import io.github.stronghorse44.tunnels.export.WrongPasswordOrCorrupt
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes a snapshot through the engine with the real registry, checks it is stored, then round-trips a bundle built
 * from the store through the sealed file format and back into the store. A second test opens the screen itself.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotsSmokeTest {
    @Test
    fun snapshotRoundTripsThroughSealedBundle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("snapshots registers no tunnels", SnapshotsTunnels().create(context).isEmpty())

        val rt = TunnelsRuntime.get(context)
        val dao = rt.store.dao
        val result = rt.engine.scan(null)
        assertTrue(result.observations >= 0)
        val stored = dao.snapshots()
        assertTrue("snapshot ${result.snapshotId} is in the store", stored.any { it.id == result.snapshotId })

        val bundle = StoreBundles.read(dao)
        assertTrue(bundle.snapshots.any { it.localId == result.snapshotId })
        assertEquals(stored.size, bundle.snapshots.size)
        assertTrue(bundle.observations.all { o -> bundle.snapshots.any { it.localId == o.snapshotLocalId } })

        val password = "emulator-test-password".toCharArray()
        val sealed = EncryptedFile.seal(BundleFormat.write(bundle).toByteArray(), password)
        assertTrue(EncryptedFile.looksSealed(sealed))
        val reopened = BundleFormat.parse(EncryptedFile.open(sealed, password).inputStream().reader())
        assertEquals(bundle, reopened)
        try {
            EncryptedFile.open(sealed, "wrong".toCharArray())
            fail("wrong password accepted")
        } catch (_: WrongPasswordOrCorrupt) {
        }

        // The same moments are already in the store: nothing is imported twice.
        val before = dao.snapshots().size
        val again = StoreBundles.write(dao, reopened)
        assertEquals(0, again.snapshots)
        assertEquals(reopened.snapshots.size, again.skipped)
        assertEquals(before, dao.snapshots().size)

        // Shifted over a day into the past (by a per-run amount, so a re-run on the same install does not collide),
        // as a restore from another install would be, everything comes in pinned.
        val day = 24L * 60 * 60 * 1000
        val shift = day + System.currentTimeMillis() % day
        val older = reopened.copy(snapshots = reopened.snapshots.map { it.copy(takenAt = it.takenAt - shift) })
        val imported = StoreBundles.write(dao, older)
        assertEquals(older.snapshots.size, imported.snapshots)
        assertEquals(older.observations.size, imported.observations)
        assertEquals(0, imported.skipped)
        val after = dao.snapshots()
        assertEquals(before + imported.snapshots, after.size)
        assertTrue("imported snapshots are pinned", after.filter { it.id > result.snapshotId }.all { it.pinned })
        assertTrue(after.any { it.takenAt == older.snapshots.first().takenAt && it.pinned })

        val resolved = context.packageManager.resolveActivity(SnapshotsActivity.intent(context), PackageManager.ResolveInfoFlags.of(0))
        assertNotNull("the SNAPSHOTS action resolves inside this package", resolved)
    }

    @Test
    fun screenOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SnapshotsActivity>(SnapshotsActivity.intent(context)).use { scenario ->
            // The ViewModel opens the store and reads the history in the background; give it a moment to crash if it will.
            Thread.sleep(2500)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
