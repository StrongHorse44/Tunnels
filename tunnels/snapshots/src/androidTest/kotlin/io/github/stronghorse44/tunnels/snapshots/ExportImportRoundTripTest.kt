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
import io.github.stronghorse44.tunnels.export.TunnelsExport
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.NetworkRecordCodec
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
 * the refusals that must leave the store as it was. Throwaway in-memory Room databases stand in for the encrypted store,
 * through the same code the screen uses ([DataTransfer]). Home network's confirmed networks (two rows of its settings
 * table) are never exported and never merged any more (audit L8, L8-Q4): the tests seed both rows and check that they
 * stay exactly as they were.
 */
@RunWith(AndroidJUnit4::class)
class ExportImportRoundTripTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = "correct horse battery staple"
    private val now = 1_791_028_800_000L
    private val dbs = ArrayList<TunnelsDatabase>()
    private val files = ArrayList<File>()

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
    }

    private fun db(): TunnelsDatabase = Room.inMemoryDatabaseBuilder(context, TunnelsDatabase::class.java).build().also { dbs += it }

    /** A v2 row as Home network writes it (keyed tokens under some key id), never carried by an export. */
    private val v2Row = "k=0a1b2c3d\n${"0".repeat(32)} r ${"1".repeat(32)} -\n${"2".repeat(32)} a - -\n"

    private fun setNetworks(dao: TunnelsDao, set: Set<String>) =
        runBlocking {
            dao.putSetting(SettingEntity(ConfirmedNetworkCodec.KEY, ConfirmedNetworkCodec.encode(set)))
            dao.putSetting(SettingEntity(NetworkRecordCodec.KEY, v2Row))
        }

    /** Both of Home network's rows, as stored. */
    private fun networkRows(dao: TunnelsDao) = runBlocking { dao.setting(ConfirmedNetworkCodec.KEY) to dao.setting(NetworkRecordCodec.KEY) }

    private fun file(name: String): File = File(context.cacheDir, "fwx-roundtrip-$name.fwx").also { files += it }

    private fun pinLine(seed: Int, auditedMs: Long): String {
        val key = ByteArray(40) { (it * 7 + seed).toByte() }
        val id = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02X".format(it) }
        return listOf(id, Base64.getEncoder().encodeToString(key), "Pixel $seed", "ab".repeat(32), "1700000000000", auditedMs.toString(), "202610", "true").joinToString("|")
    }

    /** A phone with history, choices, a paired phone and confirmed networks (both rows). */
    private fun seed(dao: TunnelsDao, moments: List<Long> = listOf(1_700_000_000_000, 1_700_000_060_000)) = runBlocking {
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

    /** What a phone holds that an import may change, plus both network rows (which it may not). */
    private fun everything(dao: TunnelsDao) = listOf(state(dao), networkRows(dao))

    private fun transfer(dao: TunnelsDao) = DataTransfer(context, dao, applyWatchSettings = {})

    private fun export(source: TunnelsDao, out: File) =
        runBlocking { transfer(source).export(Uri.fromFile(out), passphrase.toCharArray(), now) }

    private fun import(target: TunnelsDao, from: File, pw: String = passphrase) =
        runBlocking { transfer(target).import(Uri.fromFile(from), pw.toCharArray()) }

    private fun state(dao: TunnelsDao) = runBlocking { comparable(StoreBundles.gather(dao)) }

    /** A bundle as an older Tunnels wrote it, carrying confirmed-network lines. */
    private fun bundleWithNetworks(source: TunnelsDao, out: File, networks: Set<String>) = runBlocking {
        val data = StoreBundles.gather(source).copy(networks = networks)
        TunnelsExport.run(data, passphrase.toCharArray(), now, "0.1.15", { out.outputStream() }, { out.inputStream() })
    }

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
        seed(a)
        val out = file("a")
        val exported = export(a, out)
        assertEquals(2, exported.counts.snapshots)
        assertEquals(4, exported.counts.observations)
        assertEquals(3, exported.counts.settings)
        assertEquals(1, exported.counts.pairingPins)
        assertEquals("confirmed networks are never exported (L8-Q4)", 0, exported.counts.networks)
        assertEquals(out.length(), exported.bytes)

        // Before any passphrase: the header names this app and a schema this build reads.
        val info = runBlocking { transfer(a).inspect(Uri.fromFile(out)) }
        assertEquals(1L, (info as Inspected.Fwx).schema)
        assertEquals(now, info.createdMs)

        val b = db().dao()
        val report = import(b, out)
        val summary = report.summary
        assertEquals(2, summary.snapshots)
        assertEquals(4, summary.observations)
        assertEquals(0, summary.skippedSnapshots)
        assertEquals(3, summary.settings)
        assertEquals(1, summary.newPairedPhones)
        assertEquals(0, summary.newNetworks)
        assertEquals(0, report.networksLeftOut)
        assertFalse(report.message.contains(ImportReport.NETWORKS_NOT_CARRIED))
        assertEquals(state(a), state(b))
        assertEquals("no network row is created", null to null, networkRows(b))
        // This phone's own history and identity were not carried; each snapshot has the pin it had (one pinned, one not).
        runBlocking {
            assertEquals(null, b.setting("watch.status"))
            assertEquals(null, b.setting("pairing.verifierId"))
            assertEquals(listOf(true, false), b.snapshots().sortedBy { it.takenAt }.map { it.pinned })
        }

        // Importing the same file again adds no snapshot and changes nothing else.
        val again = import(b, out).summary
        assertEquals(0, again.snapshots)
        assertEquals(2, again.skippedSnapshots)
        assertEquals(0, again.newPairedPhones)
        assertEquals(0, again.newNetworks)
        assertEquals(state(a), state(b))
    }

    /** Spec 7 test 8: the manifest of an export says "networks":0, and its networks.txt is empty. */
    @Test
    fun anExportsManifestCountsNoNetworks() {
        val a = db().dao()
        seed(a)
        val out = file("manifest")
        export(a, out)
        val data = io.github.stronghorse44.tunnels.export.TunnelsBundle.read(out.inputStream().buffered(), passphrase.toCharArray())
        assertTrue(data.networks.isEmpty())
        val encoded = io.github.stronghorse44.tunnels.export.TunnelsBundle.encode(runBlocking { StoreBundles.gather(a) }, "test")
        fun text(name: String) = encoded.first { it.name == name }.let { String(it.bytes, 0, it.length, Charsets.UTF_8) }
        val manifest = text(io.github.stronghorse44.tunnels.export.TunnelsBundle.MANIFEST)
        assertTrue(manifest, manifest.contains("\"networks\":0}"))
        assertEquals("", text(io.github.stronghorse44.tunnels.export.TunnelsBundle.NETWORKS))
        assertEquals("the phone's own rows are untouched by an export", setOf(net1, net2), ConfirmedNetworkCodec.decode(networkRows(a).first))
    }

    /** Spec 7 test 8: an older bundle's network lines are read, counted and not merged into either row. */
    @Test
    fun importingABundleWithNetworkLinesLeavesBothRows() {
        val a = db().dao()
        seed(a, listOf(1_700_000_000_000))
        val out = file("old-nets")
        bundleWithNetworks(a, out, setOf(net1, net2, "c3".repeat(32)))

        val b = db().dao()
        setNetworks(b, setOf("d4".repeat(32)))
        val rows = networkRows(b)
        val report = import(b, out)
        assertEquals(1, report.summary.snapshots)
        assertEquals(0, report.summary.newNetworks)
        assertEquals(3, report.networksLeftOut)
        assertTrue(report.message.endsWith(" " + ImportReport.NETWORKS_NOT_CARRIED))
        assertEquals(
            "Confirmed networks aren't carried between phones any more: confirm your Wi-Fi in Home network on this phone.",
            ImportReport.NETWORKS_NOT_CARRIED,
        )
        assertEquals("both rows exactly as they were", rows, networkRows(b))

        // Into a phone that has no rows at all: none is created.
        val c = db().dao()
        assertEquals(3, import(c, out).networksLeftOut)
        assertEquals(null to null, networkRows(c))
    }

    @Test
    fun anUnpinnedSnapshotImportsUnpinnedAndIsSubjectToRetention() {
        val moments = (0 until 14).map { 1_700_000_000_000 + it * 60_000L }
        val a = db().dao()
        seed(a, moments) // the first is pinned, the other thirteen are not
        val out = file("r")
        export(a, out)

        val b = db().dao()
        import(b, out)
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
        seed(a)
        val out = file("m")
        export(a, out)

        val b = db().dao()
        runBlocking {
            b.insertSnapshot(SnapshotEntity(takenAt = 1_700_000_000_000, pinned = false)) // the same moment as one in the file
            val newer = b.insertSnapshot(SnapshotEntity(takenAt = 1_800_000_000_000, pinned = false))
            b.insertObservations(listOf(ObservationEntity(newer, "doors", "com.example.app", "exported", "9")))
            b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt="))
            b.putSetting(SettingEntity("pairing.pins", pinLine(2, 1_700_000_500_000) + "\n" + pinLine(1, 1_700_000_900_000)))
        }
        setNetworks(b, setOf("c3".repeat(32)))
        val rows = networkRows(b)

        val summary = import(b, out).summary
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
        assertEquals("the phone's own networks are as they were", rows, networkRows(b))
    }

    @Test
    fun aWrongPassphraseAndADamagedFileChangeNothing() {
        val a = db().dao()
        seed(a)
        val out = file("w")
        export(a, out)

        // A populated phone: nothing below may change any of it.
        val b = db().dao()
        seed(b, moments = listOf(1_650_000_000_000))
        runBlocking { b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt=")) }
        val empty = everything(b)
        assertCode(FwxError.WRONG_PASSPHRASE) { import(b, out, "correct horse battery stapl") }
        assertEquals(empty, everything(b))

        val bytes = out.readBytes()
        val truncated = file("t").also { it.writeBytes(bytes.copyOf(bytes.size - 20)) }
        assertCode(FwxError.DAMAGED) { import(b, truncated) }
        val flipped = file("f").also { it.writeBytes(bytes.copyOf().also { c -> c[c.size / 2] = (c[c.size / 2].toInt() xor 1).toByte() }) }
        assertCode(FwxError.DAMAGED) { import(b, flipped) }
        assertCode(FwxError.WRONG_APP) { import(b, file("lumen2").also { it.writeBytes(otherAppBundle()) }) }
        assertEquals(empty, everything(b))
        runBlocking { assertEquals(1, b.snapshots().size) }
    }

    @Test
    fun anotherAppsBundleIsRefusedByNameBeforeAnyPassphrase() {
        val lumen = file("lumen")
        lumen.writeBytes(otherAppBundle())

        val b = db().dao()
        try {
            runBlocking { transfer(b).inspect(Uri.fromFile(lumen)) }
            fail("accepted")
        } catch (e: FwxException) {
            assertEquals(FwxError.WRONG_APP, e.code)
            assertEquals("lumen", e.otherAppId)
        }
        assertCode(FwxError.WRONG_APP) { import(b, lumen) }
        assertEquals(state(b), runBlocking { comparable(TunnelsData.EMPTY) })
    }

    @Test
    fun anOldTsnape1ExportStillImportsAndTouchesOnlySnapshots() {
        val a = db().dao()
        seed(a, moments = listOf(1_700_000_000_000))
        val bundleText = runBlocking { io.github.stronghorse44.tunnels.export.BundleFormat.write(StoreBundles.read(a)) }
        val old = file("legacy").also { it.writeBytes(legacySeal(bundleText.toByteArray(), passphrase.toCharArray())) }

        val b = db().dao()
        runBlocking { b.putSetting(SettingEntity("traffic.block", "on=false;kinds=ADS;exempt=")) }
        setNetworks(b, setOf(net1))
        val rows = networkRows(b)
        val info = runBlocking { transfer(b).inspect(Uri.fromFile(old)) }
        assertEquals(Inspected.Legacy, info)
        val summary = import(b, old).summary
        assertEquals(1, summary.snapshots)
        assertEquals(2, summary.observations)
        assertEquals(0, summary.settings)
        runBlocking {
            assertEquals(1, b.snapshots().size)
            assertEquals("on=false;kinds=ADS;exempt=", b.setting("traffic.block")) // untouched
        }
        assertEquals(rows, networkRows(b))
        assertCode(FwxError.WRONG_PASSPHRASE) { import(b, old, "wrong password for the old file") }
    }

    @Test
    fun aFailedWriteKeepsNothing() {
        val a = db().dao()
        seed(a)
        val data = runBlocking { StoreBundles.gather(a) }.copy(networks = setOf(net1))

        // The store refuses only the paired phones row, which is the last thing the import writes: the transaction must
        // undo the snapshots and settings it already wrote, and the phone's own rows stay exactly as they were.
        val target = db()
        val dao = target.dao()
        setNetworks(dao, setOf("c3".repeat(32)))
        val rows = networkRows(dao)
        target.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_pins BEFORE INSERT ON settings WHEN NEW.\"key\" = 'pairing.pins' BEGIN SELECT RAISE(ABORT, 'refused'); END")
        try {
            runBlocking { StoreBundles.commit(dao, data) }
            fail("committed to a store that refuses the settings")
        } catch (_: ImportCommitFailed) {
        }
        runBlocking {
            assertTrue("no snapshot survives a failed import", dao.snapshots().isEmpty())
            assertEquals("the settings written before the pins row are undone too", null, dao.setting("traffic.block"))
        }
        assertEquals(rows, networkRows(dao))
    }

    /** A bundle with no networks (or an old TSNAPE1 file) must not create or change either row. */
    @Test
    fun anImportWithoutNetworksLeavesTheRowsAlone() {
        val a = db().dao()
        seed(a, listOf(1_700_000_000_000))
        val out = file("no-nets")
        export(a, out)

        val b = db().dao()
        assertEquals(0, import(b, out).summary.newNetworks)
        assertEquals(null to null, networkRows(b))
    }

    @Test
    fun aRefusedExportLeavesAnExistingFileExactlyAsItWas() {
        val a = db().dao()
        seed(a)
        val rows = networkRows(a)
        val out = file("x")
        out.writeBytes("keep!".toByteArray())
        // An 11-character passphrase is refused before the file is opened for writing (which would truncate it).
        try {
            runBlocking { transfer(a).export(Uri.fromFile(out), "eleven char".toCharArray(), now) }
            fail("exported with a short passphrase")
        } catch (e: DataTransfer.ExportFailed) {
            assertEquals(FwxError.BAD_PASSPHRASE, (e.cause as FwxException).code)
        }
        assertEquals("keep!", out.readText())
        assertEquals(rows, networkRows(a))
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
