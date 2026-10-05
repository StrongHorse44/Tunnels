package io.github.stronghorse44.tunnels.snapshots

import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.export.TunnelsBundle
import io.github.stronghorse44.tunnels.export.TunnelsExport
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import io.github.stronghorse44.tunnels.runtime.TunnelsRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Takes a snapshot through the engine with the real registry, checks it is stored, then round-trips the store through
 * an FWX bundle and back into the real (encrypted) store. A second test opens the screen itself. The full export and
 * import behaviour, against throwaway databases, is in [ExportImportRoundTripTest].
 */
@RunWith(AndroidJUnit4::class)
class SnapshotsSmokeTest {
    @Test
    fun snapshotRoundTripsThroughAnFwxBundle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("snapshots registers no tunnels", SnapshotsTunnels().create(context).isEmpty())

        val rt = TunnelsRuntime.get(context)
        val dao = rt.store.dao
        val result = rt.engine.scan(null)
        assertTrue(result.observations >= 0)
        val stored = dao.snapshots()
        assertTrue("snapshot ${result.snapshotId} is in the store", stored.any { it.id == result.snapshotId })

        // The confirmed networks are a row of the real store: gathering reads them and the re-imports below merge the very
        // same hashes back (a union, so the real list is unchanged). The old plaintext file's name is a throwaway one.
        val networks = ConfirmedNetworks(context, dao, "snapshots_smoke_test_legacy")
        val data = StoreBundles.gather(dao, networks.all())
        assertTrue(data.snapshots.snapshots.any { it.localId == result.snapshotId })
        assertEquals(stored.size, data.snapshots.snapshots.size)
        assertTrue(data.snapshots.observations.all { o -> data.snapshots.snapshots.any { it.localId == o.snapshotLocalId } })

        val password = "emulator-test-passphrase".toCharArray()
        val out = ByteArrayOutputStream()
        TunnelsExport.run(data, password, System.currentTimeMillis(), "test", { out }, { ByteArrayInputStream(out.toByteArray()) })
        val reopened = TunnelsBundle.read(ByteArrayInputStream(out.toByteArray()), password)
        assertEquals(data, reopened)
        try {
            TunnelsBundle.read(ByteArrayInputStream(out.toByteArray()), "wrong passphrase!".toCharArray())
            fail("wrong passphrase accepted")
        } catch (e: FwxException) {
            assertEquals(FwxError.WRONG_PASSPHRASE, e.code)
        }

        // The same moments are already in the store: nothing is imported twice.
        val before = dao.snapshots().size
        val again = StoreBundles.commit(dao, networks, reopened)
        assertEquals(0, again.snapshots)
        assertEquals(reopened.snapshots.snapshots.size, again.skippedSnapshots)
        assertEquals(before, dao.snapshots().size)

        // Shifted over a day into the past (by a per-run amount, so a re-run on the same install does not collide),
        // as a restore from another install would be, everything comes in pinned.
        val day = 24L * 60 * 60 * 1000
        val shift = day + System.currentTimeMillis() % day
        val older = reopened.copy(snapshots = reopened.snapshots.copy(snapshots = reopened.snapshots.snapshots.map { it.copy(takenAt = it.takenAt - shift) }))
        val imported = StoreBundles.commit(dao, networks, older)
        assertEquals(older.snapshots.snapshots.size, imported.snapshots)
        assertEquals(older.snapshots.observations.size, imported.observations)
        assertEquals(0, imported.skippedSnapshots)
        val after = dao.snapshots()
        assertEquals(before + imported.snapshots, after.size)
        // Each imported snapshot has the pin it had in the file.
        for (s in older.snapshots.snapshots) assertEquals(s.pinned, after.first { it.takenAt == s.takenAt }.pinned)

        val resolved = context.packageManager.resolveActivity(SnapshotsActivity.intent(context), PackageManager.ResolveInfoFlags.of(0))
        assertNotNull("the SNAPSHOTS action resolves inside this package", resolved)
    }

    @Test
    fun screenOpens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SnapshotsActivity>(SnapshotsActivity.intent(context)).use { scenario ->
            // The ViewModel opens the store and reads the history in the background: a slow emulator may need a
            // few seconds to resume, and a crash in that work would tear the activity down again.
            val deadline = System.currentTimeMillis() + 10_000
            while (scenario.state != Lifecycle.State.RESUMED && System.currentTimeMillis() < deadline) Thread.sleep(250)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            Thread.sleep(2000)
            assertEquals("still resumed after the store opened", Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
