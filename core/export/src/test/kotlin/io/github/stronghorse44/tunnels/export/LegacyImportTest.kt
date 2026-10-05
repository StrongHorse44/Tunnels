package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.Samples.assertCode
import io.github.stronghorse44.tunnels.export.Samples.pass
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/** An export made by an earlier Tunnels (TSNAPE1) must still import (container spec, section 11). */
class LegacyImportTest {
    private val password = pass("old export password")

    private fun legacyFile(snapshots: SnapshotBundle = Samples.snapshots) =
        LegacySeal.seal(BundleFormat.write(snapshots).toByteArray(), password)

    @Test
    fun anOldExportImportsAsSnapshotsOnly() {
        val data = LegacyImport.read(legacyFile(), password)
        assertEquals(TunnelsData(snapshots = Samples.snapshots), data)
        // Nothing else is touched by it.
        assertEquals(0, data.counts.settings + data.counts.pairingPins + data.counts.networks)
    }

    @Test
    fun theOldFormatIsRoutedFromItsFirstBytesNotItsName() {
        assertEquals(Inspected.Legacy, TunnelsBundle.inspect(ByteArrayInputStream(legacyFile())))
    }

    @Test
    fun theCodecsLegacyFixtureStillOpensWithTheOldReader() {
        // The program's shared vector: sealed exactly as the old EncryptedFile.seal did, by the desktop provider.
        val bytes = javaClass.getResourceAsStream("/fwx-v1/legacy-TSNAPE1.bin")!!.readBytes()
        val plain = EncryptedFile.open(bytes, "correct horse battery staple".toCharArray())
        assertEquals(58, plain.size)
        assertArrayEquals("TSNAP1 test fixture for fwx.py; not a real Tunnels bundle\n".toByteArray(), plain)
        // Its payload is not a bundle, and the importer says so rather than importing nothing.
        assertCode(FwxError.MALFORMED_PAYLOAD) { LegacyImport.read(bytes, "correct horse battery staple".toCharArray()) }
    }

    @Test
    fun aWrongPasswordIsTheSameErrorAsForTheNewFormat() {
        assertCode(FwxError.WRONG_PASSPHRASE) { LegacyImport.read(legacyFile(), pass("not the password")) }
        assertEquals(
            "Wrong password, or the file is damaged. Nothing was changed.",
            TransferMessages.importFailure(io.github.stronghorse44.tunnels.export.fwx.FwxException(FwxError.WRONG_PASSPHRASE, "x"), legacy = true),
        )
    }

    @Test
    fun aDamagedOldFileIsRefused() {
        val file = legacyFile()
        assertCode(FwxError.WRONG_PASSPHRASE) { LegacyImport.read(file.copyOf(file.size - 1), password) }
        assertCode(FwxError.NOT_AN_EXPORT) { LegacyImport.read("not sealed at all, just text.......................".toByteArray(), password) }
    }

    @Test
    fun anOldFileWhosePayloadIsNotABundleIsRefused() {
        for (payload in listOf("hello", "TSNAP1\nbogus\n", "TSNAP1\nobs\t1\tx\ts\tk\tv\n")) {
            val file = LegacySeal.seal(payload.toByteArray(), password)
            assertCode(FwxError.MALFORMED_PAYLOAD) { LegacyImport.read(file, password) }
        }
        val badUtf8 = LegacySeal.seal(byteArrayOf('T'.code.toByte(), 0xC3.toByte(), 0x28), password)
        assertCode(FwxError.MALFORMED_PAYLOAD) { LegacyImport.read(badUtf8, password) }
    }
}
