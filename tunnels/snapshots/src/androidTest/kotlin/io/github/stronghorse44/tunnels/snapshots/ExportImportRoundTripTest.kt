package io.github.stronghorse44.tunnels.snapshots

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.export.ImportCommitFailed
import io.github.stronghorse44.tunnels.export.Inspected
import io.github.stronghorse44.tunnels.export.TunnelsData
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import io.github.stronghorse44.tunnels.export.fwx.FwxWriter
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.SettingEntity
import io.github.stronghorse44.tunnels.store.SnapshotEntity
import io.github.stronghorse44.tunnels.store.TunnelsDao
import io.github.stronghorse44.tunnels.store.TunnelsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The CI round trip of the export (docs/EXPORT.md): export from one store, import into an empty one, compare; and
 * the refusals that must leave the store as it was. Throwaway in-memory Room databases stand in for the encrypted store
 * (the confirmed networks are a row of its settings table), and throwaway preference files for the old plaintext list,
 * through the same code the screen uses ([DataTransfer]).
 */
@RunWith(AndroidJUnit4::class)
class ExportImportRoundTripTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = "correct horse battery staple"
    private val now = 1_791_028_800_000L
    private val dbs = ArrayList<TunnelsDatabase>()
    private val files = ArrayList<File>()
    private val prefs = ArrayList<String>()

    private val net1 = "0f".repeat(32)
    private val net2 = "a1".repeat(32)
    private val block = "on=true;kinds=ADS,ANALYTICS;exempt=com.example.bank"
    private val upstream = "provider=quad9;url=https://dns.quad9.net/dns-query"

    @Before
    fun setUp() {
        files.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        dbs.forEach { runCatching { it.close() } }
        files.forEach { it.delete() }
        prefs.forEach { context.deleteSharedPreferences(it) }
    }

    private fun db(): TunnelsDatabase = Room.inMemoryDatabaseBuilder(context, TunnelsDatabase::class.java).build().also { dbs += it }

    /** The phone's confirmed networks for [dao]; its old plaintext file is a throwaway one of this test's, never the real one. */
    private fun nets(dao: TunnelsDao): ConfirmedNetworks = ConfirmedNetworks(context, dao, legacyName())

    private fun legacyName(): String {
        val name = "snapshots_roundtrip_legacy_${prefs.size}"
        prefs += name
        context.deleteSharedPreferences(name)
        return name
    }

    private fun setNetworks(dao: TunnelsDao, set: Set<String>) =
        runBlocking { dao.putSetting(SettingEntity(ConfirmedNetworkCodec.KEY, ConfirmedNetworkCodec.encode(set))) }

    private fun file(name: String): File = File(context.cacheDir, "fwx-roundtrip-$name.fwx").also { files += it }

    private fun pinLine(seed: Int, auditedMs: Long): String {
        val key = ByteArray(40) { (it * 7 + seed).toByte() }
        val id = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02X".format(it) }
        return listOf(id, Base64.getEncoder().encodeToString(key), "Pixel $seed", "ab".repeat(32), "1700000000000", auditedMs.toString(), "202610", "true").joinToString("|")
    }

    /** A phone with history, choices, a paired phone and two confirmed networks. */
    private fun seed(dao: TunnelsDao, networks: ConfirmedNetworks, moments: List<Long> = listOf(1_700_000_000_000, 1_700_000_060_000)) = runBlocking {
        moments.forEachIndexed { i, at ->
            val id = dao.insertSnapshot(SnapshotEntity(takenAt = at, pinned = i == 0))
            dao.insertObservations(
                listOf(
                    ObservationEntity(id, "doors", "com.example.app", "exported", "$i"),
                    ObservationEntity(id, "permissions", "a\tb\nc", "perm:CAMERA", "granted ü🔑"),
                ),
            )
        }
        dao.putSetting(SettingEntity("traffic.block", block))
        dao.putSetting(SettingEntity("traffic.upstream", upstream))
        dao.putSetting(SettingEntity("watch.settings", "enabled=true;interval=12;notify=WARN;extra="))
        dao.putSetting(SettingEntity("watch.status", "run=99"))
        dao.putSetting(SettingEntity("pairing.verifierId", "00ff"))
        dao.putSetting(SettingEntity("pairing.pins", pinLine(1, 1_700_000_100_000)))
        setNetworks(dao, setOf(net1, net2))
    }

    /** What two phones must share after a restore (the pin included); row ids are the store's own. */
    private fun comparable(d: TunnelsData) = listOf(
        d.snapshots.snapshots.sortedBy { it.takenAt }.map { Triple(it.takenAt, it.pinned, it.tunnelIds) },
        d.snapshots.observations.map { o ->
            listOf(d.snapshots.snapshots.first { it.localId == o.snapshotLocalId }.takenAt, o.tunnelId, o.subject, o.key, o.value).joinToString("|")
        }.sorted(),
        d.settings, d.pairingPins, d.networks,
    )

    private fun export(source: TunnelsDao, networks: ConfirmedNetworks, out: File) =
        runBlocking { DataTransfer(context, source, networks, applyWatchSettings = {}).export(Uri.fromFile(out), passphrase.toCharArray(), now) }

    private fun import(target: TunnelsDao, networks: ConfirmedNetworks, from: File, pw: String = passphrase) =
        runBlocking { DataTransfer(context, target, networks, applyWatchSettings = {}).import(Uri.fromFile(from), pw.toCharArray()) }

    private fun state(dao: TunnelsDao, networks: ConfirmedNetworks) = runBlocking { comparable(StoreBundles.gather(dao, networks.all())) }

    private fun assertCode(code: FwxError, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: FwxException) {
            assertEquals(e.toString(), code, e.code)
        }
    }

    @Test
    fun exportThenImportIntoAnEmptyStoreCompares() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA)
        val out = file("a")
        val exported = export(a, netsA, out)
        assertEquals(2, exported.counts.snapshots)
        assertEquals(4, exported.counts.observations)
        assertEquals(3, exported.counts.settings)
        assertEquals(1, exported.counts.pairingPins)
        assertEquals(2, exported.counts.networks)
        assertEquals(out.length(), exported.bytes)

        // Before any passphrase: the header names this app and a schema this build reads.
        val info = runBlocking { DataTransfer(context, a, netsA, applyWatchSettings = {}).inspect(Uri.fromFile(out)) }
        assertEquals(1L, (info as Inspected.Fwx).schema)
        assertEquals(now, info.createdMs)

        val b = db().dao()
        val netsB = nets(b)
        val summary = import(b, netsB, out)
        assertEquals(2, summary.snapshots)
        assertEquals(4, summary.observations)
        assertEquals(0, summary.skippedSnapshots)
        assertEquals(3, summary.settings)
        assertEquals(1, summary.newPairedPhones)
        assertEquals(2, summary.newNetworks)
        assertEquals(state(a, netsA), state(b, netsB))
        // This phone's own history and identity were not carried; each snapshot has the pin it had (one pinned, one not).
        runBlocking {
            assertEquals(null, b.setting("watch.status"))
            assertEquals(null, b.setting("pairing.verifierId"))
            assertEquals(listOf(true, false), b.snapshots().sortedBy { it.takenAt }.map { it.pinned })
        }

        // Importing the same file again adds no snapshot and changes nothing else.
        val again = import(b, netsB, out)
        assertEquals(0, again.snapshots)
        assertEquals(2, again.skippedSnapshots)
        assertEquals(0, again.newPairedPhones)
        assertEquals(0, again.newNetworks)
        assertEquals(state(a, netsA), state(b, netsB))
    }

    @Test
    fun anUnpinnedSnapshotImportsUnpinnedAndIsSubjectToRetention() {
        val moments = (0 until 14).map { 1_700_000_000_000 + it * 60_000L }
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA, moments) // the first is pinned, the other thirteen are not
        val out = file("r")
        export(a, netsA, out)

        val b = db().dao()
        import(b, nets(b), out)
        runBlocking {
            val rows = b.snapshots().sortedBy { it.takenAt }
            assertEquals(listOf(true) + List(13) { false }, rows.map { it.pinned })
            // Thirteen unpinned, twelve kept: the oldest unpinned one is what retention would remove; the pinned one never.
            val doomed = io.github.stronghorse44.tunnels.engine.RetentionPolicy.snapshotsToDelete(rows.map { it.toModel() })
            assertEquals(listOf(moments[1]), doomed.map { it.takenAt.toEpochMilli() })
        }
    }

    @Test
    fun importMergesIntoAPhoneThatHasData() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA)
        val out = file("m")
        export(a, netsA, out)

        val b = db().dao()
        val netsB = nets(b)
        runBlocking {
            b.insertSnapshot(SnapshotEntity(takenAt = 1_700_000_000_000, pinned = false)) // the same moment as one in the file
            val newer = b.insertSnapshot(SnapshotEntity(takenAt = 1_800_000_000_000, pinned = false))
            b.insertObservations(listOf(ObservationEntity(newer, "doors", "com.example.app", "exported", "9")))
            b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt="))
            b.putSetting(SettingEntity("pairing.pins", pinLine(2, 1_700_000_500_000) + "\n" + pinLine(1, 1_700_000_900_000)))
        }
        setNetworks(b, setOf("c3".repeat(32)))

        val summary = import(b, netsB, out)
        assertEquals(1, summary.snapshots)
        assertEquals(1, summary.skippedSnapshots)
        assertEquals(0, summary.newPairedPhones) // phone 1 is already here; the bundle brought none new
        runBlocking {
            assertEquals(3, b.snapshots().size)
            // The snapshot the phone took last is still each tunnel's latest, though the import gave an older one a higher id.
            val imported = b.snapshots().first { it.takenAt == 1_700_000_060_000 }
            val newest = b.snapshots().first { it.takenAt == 1_800_000_000_000 }
            assertTrue(imported.id > newest.id)
            assertEquals(newest.id, b.latestSnapshotIdFor("doors"))
            assertEquals(imported.id, b.latestSnapshotIdFor("doors", before = newest.id))
            assertEquals(null, b.latestSnapshotIdFor("silicon"))
            assertEquals(block, b.setting("traffic.block")) // the file's choice replaces this phone's
            assertEquals(upstream, b.setting("traffic.upstream"))
            val pins = b.setting("pairing.pins")!!.lines()
            assertEquals(2, pins.size)
            assertTrue("the more recently audited record stays", pins.any { it.contains("|1700000900000|") })
        }
        assertEquals(setOf(net1, net2, "c3".repeat(32)), netsB.all())
    }

    @Test
    fun aWrongPassphraseAndADamagedFileChangeNothing() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA)
        val out = file("w")
        export(a, netsA, out)

        // A populated phone: nothing below may change any of it.
        val b = db().dao()
        val netsB = nets(b)
        seed(b, netsB, moments = listOf(1_650_000_000_000))
        runBlocking { b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt=")) }
        val empty = state(b, netsB)
        assertCode(FwxError.WRONG_PASSPHRASE) { import(b, netsB, out, "correct horse battery stapl") }
        assertEquals(empty, state(b, netsB))

        val bytes = out.readBytes()
        val truncated = file("t").also { it.writeBytes(bytes.copyOf(bytes.size - 20)) }
        assertCode(FwxError.DAMAGED) { import(b, netsB, truncated) }
        val flipped = file("f").also { it.writeBytes(bytes.copyOf().also { c -> c[c.size / 2] = (c[c.size / 2].toInt() xor 1).toByte() }) }
        assertCode(FwxError.DAMAGED) { import(b, netsB, flipped) }
        assertCode(FwxError.WRONG_APP) { import(b, netsB, file("lumen2").also { it.writeBytes(otherAppBundle()) }) }
        assertEquals(empty, state(b, netsB))
        runBlocking { assertEquals(1, b.snapshots().size) }
    }

    @Test
    fun anotherAppsBundleIsRefusedByNameBeforeAnyPassphrase() {
        val lumen = file("lumen")
        lumen.writeBytes(otherAppBundle())

        val b = db().dao()
        val netsB = nets(b)
        try {
            runBlocking { DataTransfer(context, b, netsB, applyWatchSettings = {}).inspect(Uri.fromFile(lumen)) }
            fail("accepted")
        } catch (e: FwxException) {
            assertEquals(FwxError.WRONG_APP, e.code)
            assertEquals("lumen", e.otherAppId)
        }
        assertCode(FwxError.WRONG_APP) { import(b, netsB, lumen) }
        assertEquals(state(b, netsB), runBlocking { comparable(TunnelsData.EMPTY) })
    }

    @Test
    fun anOldTsnape1ExportStillImportsAndTouchesOnlySnapshots() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA, moments = listOf(1_700_000_000_000))
        val bundleText = runBlocking { io.github.stronghorse44.tunnels.export.BundleFormat.write(StoreBundles.read(a)) }
        val old = file("legacy").also { it.writeBytes(legacySeal(bundleText.toByteArray(), passphrase.toCharArray())) }

        val b = db().dao()
        val netsB = nets(b)
        runBlocking { b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt=")) }
        setNetworks(b, setOf(net1))
        val info = runBlocking { DataTransfer(context, b, netsB, applyWatchSettings = {}).inspect(Uri.fromFile(old)) }
        assertEquals(Inspected.Legacy, info)
        val summary = import(b, netsB, old)
        assertEquals(1, summary.snapshots)
        assertEquals(2, summary.observations)
        assertEquals(0, summary.settings)
        runBlocking {
            assertEquals(1, b.snapshots().size)
            assertEquals("on=false;kinds=ADS;exempt=", b.setting("traffic.block")) // untouched
        }
        assertEquals(setOf(net1), netsB.all())
        assertCode(FwxError.WRONG_PASSPHRASE) { import(b, netsB, old, "wrong password for the old file") }
    }

    @Test
    fun aFailedWriteKeepsNothingNetworksIncluded() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA)
        val data = runBlocking { StoreBundles.gather(a, netsA.all()) }

        // The store refuses only the networks row, which is the last thing the import writes: the transaction must undo the
        // snapshots and settings it already wrote, and the phone's own list stays exactly as it was.
        val target = db()
        val dao = target.dao()
        val netsC = nets(dao)
        setNetworks(dao, setOf("c3".repeat(32)))
        target.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_networks BEFORE INSERT ON settings WHEN NEW.\"key\" = '${ConfirmedNetworkCodec.KEY}' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            runBlocking { StoreBundles.commit(dao, netsC, data) }
            fail("committed to a store that refuses the settings")
        } catch (_: ImportCommitFailed) {
        }
        runBlocking {
            assertTrue("no snapshot survives a failed import", dao.snapshots().isEmpty())
            assertEquals("the settings written before the networks row are undone too", null, dao.setting("traffic.block"))
        }
        assertEquals(setOf("c3".repeat(32)), netsC.all())
    }

    /** The reviewer's probe on the real store: confirms and forgets on other threads while imports merge networks: nothing is lost. */
    @Test
    fun confirmsDuringImportsAreNotLostToTheMerge() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA, listOf(1_700_000_000_000))
        val data = runBlocking { StoreBundles.gather(a, netsA.all()) } // carries net1 and net2

        val dao = db().dao()
        val book = io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook(
            object : io.github.stronghorse44.tunnels.lan.ConfirmedNetworkTable {
                override fun read(): String? = runBlocking { dao.setting(ConfirmedNetworkCodec.KEY) }
                override fun write(text: String) = runBlocking { dao.putSetting(SettingEntity(ConfirmedNetworkCodec.KEY, text)) }
                override fun update(transform: (String?) -> String) = runBlocking {
                    dao.importAll(emptyList(), listOf(ConfirmedNetworkCodec.KEY)) { _, stored -> transform(stored) }
                    Unit
                }
            },
            object : io.github.stronghorse44.tunnels.lan.PlaintextNetworkFile {
                override fun exists() = false
                override fun hashes(): Set<String> = emptySet()
                override fun delete() = true
            },
        )
        val mine = (1..40).map { "%064x".format(it) }
        val confirmer = Thread { mine.forEach { check(book.confirm(it)) } }
        val netsD = nets(dao)
        confirmer.start()
        repeat(10) { runBlocking { StoreBundles.commit(dao, netsD, data.copy(networks = data.networks + "%064x".format(1000 + it))) } }
        confirmer.join()
        val have = netsD.all()
        assertTrue("every confirm survived the imports", have.containsAll(mine))
        assertTrue("every imported network survived the confirms", have.containsAll(setOf(net1, net2) + (0 until 10).map { "%064x".format(1000 + it) }))
    }

    /** A phone updated from the plaintext-preferences build, exporting before Home network has ever run its migration. */
    @Test
    fun exportMovesAndCarriesNetworksFromTheOldPlaintextFile() {
        val a = db().dao()
        val legacy = legacyName()
        val netsA = ConfirmedNetworks(context, a, legacy)
        seed(a, netsA, listOf(1_700_000_000_000))
        runBlocking { a.deleteSetting(ConfirmedNetworkCodec.KEY) } // no row of its own yet: only the old file has them
        context.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit().putStringSet(ConfirmedNetworkBook.LEGACY_KEY, setOf(net1, net2)).commit()

        val out = file("legacy-nets")
        val exported = export(a, netsA, out)
        assertEquals(2, exported.counts.networks)
        runBlocking {
            assertEquals(setOf(net1, net2), ConfirmedNetworkCodec.decode(a.setting(ConfirmedNetworkCodec.KEY)))
        }
        assertFalse("the old file is gone", File(File(context.dataDir, "shared_prefs"), "$legacy.xml").exists())

        // And into a clean phone: the same two networks, from the table.
        val b = db().dao()
        val netsB = nets(b)
        assertEquals(2, import(b, netsB, out).newNetworks)
        assertEquals(setOf(net1, net2), netsB.all())
    }

    /** An import into a phone that still holds its own networks in the old file: they are moved first, then merged with the file's. */
    @Test
    fun importMovesTheOldPlaintextFileBeforeMerging() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA, listOf(1_700_000_000_000))
        val out = file("merge-nets")
        export(a, netsA, out)

        val b = db().dao()
        val legacy = legacyName()
        val netsB = ConfirmedNetworks(context, b, legacy)
        context.getSharedPreferences(legacy, Context.MODE_PRIVATE).edit().putStringSet(ConfirmedNetworkBook.LEGACY_KEY, setOf("c3".repeat(32))).commit()
        val summary = import(b, netsB, out)
        assertEquals(2, summary.newNetworks)
        assertEquals(setOf(net1, net2, "c3".repeat(32)), netsB.all())
        assertFalse(File(File(context.dataDir, "shared_prefs"), "$legacy.xml").exists())
        runBlocking {
            assertEquals(setOf(net1, net2, "c3".repeat(32)), ConfirmedNetworkCodec.decode(b.setting(ConfirmedNetworkCodec.KEY)))
        }
    }

    /** A bundle with no networks (or an old TSNAPE1 file) must not create or change the row. */
    @Test
    fun anImportWithoutNetworksLeavesTheRowAlone() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA, listOf(1_700_000_000_000))
        runBlocking { a.deleteSetting(ConfirmedNetworkCodec.KEY) }
        val out = file("no-nets")
        export(a, netsA, out)

        val b = db().dao()
        assertEquals(0, import(b, nets(b), out).newNetworks)
        runBlocking { assertEquals(null, b.setting(ConfirmedNetworkCodec.KEY)) }
    }

    @Test
    fun aRefusedExportLeavesAnExistingFileExactlyAsItWas() {
        val a = db().dao()
        val netsA = nets(a)
        seed(a, netsA)
        val out = file("x")
        out.writeBytes("keep!".toByteArray())
        // An 11-character passphrase is refused before the file is opened for writing (which would truncate it).
        try {
            runBlocking { DataTransfer(context, a, netsA, applyWatchSettings = {}).export(Uri.fromFile(out), "eleven char".toCharArray(), now) }
            fail("exported with a short passphrase")
        } catch (e: DataTransfer.ExportFailed) {
            assertEquals(FwxError.BAD_PASSPHRASE, (e.cause as FwxException).code)
        }
        assertEquals("keep!", out.readText())
        assertEquals(setOf(net1, net2), netsA.all())
    }

    private fun otherAppBundle(): ByteArray {
        val buffer = ByteArrayOutputStream()
        FwxWriter(buffer, "lumen", 1, now, passphrase.toCharArray()).run {
            entry("manifest.json", "{}".toByteArray())
            finish()
        }
        return buffer.toByteArray()
    }

    /** The old sealer, for the legacy fixture: the documented TSNAPE1 layout. */
    private fun legacySeal(plain: ByteArray, password: CharArray): ByteArray {
        val magic = "TSNAPE1".toByteArray(Charsets.US_ASCII)
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, 310_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic + salt)
        return magic + salt + iv + cipher.doFinal(plain)
    }
}
