package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.Samples.pass
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The export procedure (container spec 5.3): validated before anything is written, read back through the import's own
 * reader, and a failure says whether the destination was touched. Also the CI round trip: export, import into an empty
 * store, compare.
 */
class TunnelsExportTest {
    private val now = 1_791_028_800_000L

    private class Disk {
        val bytes = ByteArrayOutputStream()
        var opens = 0
        var reopens = 0
    }

    private fun run(
        data: TunnelsData,
        disk: Disk = Disk(),
        passphrase: CharArray = pass(),
        open: (Disk) -> OutputStream = { d -> d.opens++; d.bytes },
        reopen: (Disk) -> InputStream = { d -> d.reopens++; ByteArrayInputStream(d.bytes.toByteArray()) },
    ): ExportResult = TunnelsExport.run(data, passphrase, now, "0.1.0", { open(disk) }, { reopen(disk) })

    private fun assertFailure(opened: Boolean, block: () -> Unit): ExportFailure {
        try {
            block()
            fail("expected an ExportFailure")
            throw IllegalStateException()
        } catch (e: ExportFailure) {
            assertEquals(e.toString(), opened, e.opened)
            return e
        }
    }

    @Test
    fun exportThenImportIntoAnEmptyStoreGivesTheSameData() {
        val disk = Disk()
        val result = run(Samples.full, disk)
        assertEquals(Samples.full.counts, result.counts)
        assertEquals(disk.bytes.size().toLong(), result.bytes)
        assertEquals(1, disk.opens)
        assertEquals(1, disk.reopens)
        // "Import into an empty store": what an import would hand its store is exactly what was exported.
        val imported = TunnelsBundle.read(ByteArrayInputStream(disk.bytes.toByteArray()), pass())
        assertEquals(Samples.full, imported)
        assertEquals(Samples.full.snapshots, imported.snapshots)
    }

    @Test
    fun anEmptyPhoneExportsAndImportsToo() {
        val disk = Disk()
        run(TunnelsData.EMPTY, disk)
        assertEquals(TunnelsData.EMPTY, TunnelsBundle.read(ByteArrayInputStream(disk.bytes.toByteArray()), pass()))
    }

    @Test
    fun aPassphraseUnder12CharactersIsRefusedBeforeTheFileIsOpened() {
        val disk = Disk()
        val e = assertFailure(opened = false) { run(Samples.full, disk, passphrase = "eleven char".toCharArray()) }
        assertEquals(FwxError.BAD_PASSPHRASE, (e.cause as FwxException).code)
        assertEquals(0, disk.opens)
        assertEquals(0, disk.bytes.size())
        assertTrue(TransferMessages.exportFailure(e, ExportCleanup.UNTOUCHED).contains("at least 12 characters"))
    }

    @Test
    fun aNormalisedPassphraseIsCountedAfterNfc() {
        // 12 code points as typed, 11 after NFC (u + combining diaeresis becomes one).
        val decomposed = "abcdefghiük".toCharArray()
        assertEquals(12, String(decomposed).codePointCount(0, decomposed.size))
        assertNotNull(PassphraseCheck.problem(decomposed))
        assertEquals(null, PassphraseCheck.problem("abcdefghijkl".toCharArray()))
        assertNotNull(PassphraseCheck.problem(CharArray(0)))
        assertNotNull(PassphraseCheck.problem("abc\uD800defghijkl".toCharArray()))
    }

    @Test
    fun dataAnImportWouldRefuseIsNeverExported() {
        // A value past the line cap: import stops at it, so export must stop before writing anything.
        val huge = Samples.full.copy(
            snapshots = Samples.snapshots.copy(
                observations = Samples.snapshots.observations + BundleObservation(1, "doors", "s", "k", "x".repeat(ParseLimits.IMPORT.maxLineChars + 1)),
            ),
        )
        val disk = Disk()
        val e = assertFailure(opened = false) { run(huge, disk) }
        assertEquals(FwxError.MALFORMED_PAYLOAD, (e.cause as FwxException).code)
        assertEquals(0, disk.opens)

        // A tunnel id with a comma cannot be carried by the record.
        val comma = Samples.full.copy(snapshots = SnapshotBundle(listOf(BundleSnapshot(1, 1, false, listOf("a,b"))), emptyList()))
        assertFailure(opened = false) { run(comma, Disk()) }

        // Text that does not survive UTF-8 (a lone surrogate) would come back changed.
        val lone = Samples.full.copy(snapshots = Samples.snapshots.copy(observations = listOf(BundleObservation(1, "doors", "s", "k", "bad\uD800"))))
        val e2 = assertFailure(opened = false) { run(lone, Disk()) }
        assertEquals(FwxError.MALFORMED_PAYLOAD, (e2.cause as FwxException).code)

        // More snapshots than an import takes.
        val many = Samples.full.copy(snapshots = SnapshotBundle(List(ParseLimits.IMPORT.maxSnapshots + 1) { BundleSnapshot(it.toLong(), it.toLong(), false, emptyList()) }, emptyList()))
        assertFailure(opened = false) { run(many, Disk()) }
    }

    @Test
    fun aSettingThatIsNotCanonicalIsRefusedBeforeWriting() {
        val odd = Samples.full.copy(settings = mapOf("traffic.block" to "on=maybe"))
        assertFailure(opened = false) { run(odd, Disk()) }
        val unknown = Samples.full.copy(settings = mapOf("pairing.verifierId" to "00"))
        assertFailure(opened = false) { run(unknown, Disk()) }
    }

    @Test
    fun aDestinationThatCannotBeOpenedLeavesNothingToDelete() {
        assertFailure(opened = false) { run(Samples.full, open = { throw IOException("no such document") }) }
    }

    @Test
    fun aDestinationThatFailsMidWriteIsReportedAsOpened() {
        val e = assertFailure(opened = true) {
            run(Samples.full, open = {
                object : OutputStream() {
                    var n = 0
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        n += len
                        if (n > 200) throw IOException("No space left on device")
                    }
                }
            })
        }
        assertEquals("No space left on device", e.cause?.message)
        assertEquals(
            "Export failed: No space left on device. The partial file was deleted.",
            TransferMessages.exportFailure(e, ExportCleanup.DELETED),
        )
    }

    @Test
    fun aFileThatReadsBackDifferentlyFails() {
        // The reopened file is another valid bundle: same passphrase, same app, different content.
        val other = Samples.bundle(Samples.full.copy(networks = emptySet()))
        val e = assertFailure(opened = true) { run(Samples.full, reopen = { ByteArrayInputStream(other) }) }
        assertTrue(e.cause is ReadBackMismatch)
    }

    @Test
    fun aFileThatReadsBackDamagedFails() {
        val e = assertFailure(opened = true) {
            run(Samples.full, reopen = { d -> ByteArrayInputStream(d.bytes.toByteArray().let { it.copyOf(it.size - 3) }) })
        }
        assertEquals(FwxError.DAMAGED, (e.cause as FwxException).code)
    }

    @Test
    fun aFileThatCannotBeReadBackFails() {
        assertFailure(opened = true) { run(Samples.full, reopen = { throw IOException("gone") }) }
    }

    @Test
    fun theBufferedPlaintextIsZeroedAfterwards() {
        // The entries the export built are wiped in a finally block; check the wipe itself.
        val entries = TunnelsBundle.encode(Samples.full, "x")
        TunnelsBundle.wipe(entries)
        assertFalse(entries.any { e -> e.bytes.any { it != 0.toByte() } })
    }

    @Test
    fun theFileIsLargerThanItsEntriesByTheContainerOverheadOnly() {
        val disk = Disk()
        val result = run(Samples.full, disk)
        val plain = TunnelsBundle.encode(Samples.full, "0.1.0").sumOf { 11 + it.name.length + it.length } + 5
        // header 83 + mac 32 + one 16-byte tag per 1 MiB chunk.
        assertEquals(83L + 32 + plain + 16, result.bytes)
    }
}
