package io.github.stronghorse44.tunnels.snapshots

import android.content.pm.PackageManager
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
 * from the store through the sealed file format and back into the store.
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
        val reopened = BundleFormat.parse(String(EncryptedFile.open(sealed, password)))
        assertEquals(bundle, reopened)
        try {
            EncryptedFile.open(sealed, "wrong".toCharArray())
            fail("wrong password accepted")
        } catch (_: WrongPasswordOrCorrupt) {
        }

        val before = dao.snapshots().size
        val imported = StoreBundles.write(dao, reopened)
        assertEquals(bundle.snapshots.size, imported.snapshots)
        assertEquals(bundle.observations.size, imported.observations)
        val after = dao.snapshots()
        assertEquals(before + imported.snapshots, after.size)
        assertTrue("imported snapshots are pinned", after.filter { it.id > result.snapshotId }.all { it.pinned })
        assertTrue(after.any { it.takenAt == bundle.snapshots.first().takenAt && it.pinned })

        val resolved = context.packageManager.resolveActivity(SnapshotsActivity.intent(context), PackageManager.ResolveInfoFlags.of(0))
        assertNotNull("the SNAPSHOTS action resolves inside this package", resolved)
    }
}
