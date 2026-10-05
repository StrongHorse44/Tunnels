package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TransferMessagesTest {
    @Test
    fun everyImportFailureSaysNothingWasChanged() {
        for (code in FwxError.entries) {
            val message = TransferMessages.importFailure(FwxException(code, "detail", otherAppId = "prikey"))
            assertTrue("$code: $message", message.endsWith("Nothing was changed."))
        }
        assertTrue(TransferMessages.importFailure(IOException("boom")).endsWith("Nothing was changed."))
        assertTrue(TransferMessages.importFailure(ImportCommitFailed(IOException("disk full"))).contains("Import failed while saving: disk full."))
    }

    @Test
    fun theWordingIsTheContainerSpecs() {
        fun msg(code: FwxError, other: String? = null) = TransferMessages.importFailure(FwxException(code, "d", other))
        assertEquals("This file is not an encrypted export. Nothing was changed.", msg(FwxError.NOT_AN_EXPORT))
        assertEquals("Wrong passphrase, or the file's header was altered. Nothing was changed.", msg(FwxError.WRONG_PASSPHRASE))
        assertEquals("The export is damaged or incomplete. Nothing was changed.", msg(FwxError.DAMAGED))
        assertEquals("The export's contents are invalid. Nothing was changed.", msg(FwxError.MALFORMED_PAYLOAD))
        assertEquals("This is a Prikey export, not a Tunnels export. Nothing was changed.", msg(FwxError.WRONG_APP, "prikey"))
        assertEquals("This is a Mardi Gras export, not a Tunnels export. Nothing was changed.", msg(FwxError.WRONG_APP, "mardigras"))
        assertEquals("This is another app's export, not a Tunnels export. Nothing was changed.", msg(FwxError.WRONG_APP, "x-unknown"))
        assertEquals("Made by a newer version of Tunnels. Update first. Nothing was changed.", msg(FwxError.SCHEMA_TOO_NEW))
    }

    @Test
    fun messagesNeverCarryTheDetailOrAnyPassphrase() {
        val secret = "hunter2 hunter2"
        for (code in FwxError.entries) {
            if (code == FwxError.BAD_PASSPHRASE) continue
            assertFalse(TransferMessages.importFailure(FwxException(code, secret)).contains(secret))
        }
    }

    @Test
    fun onlyAWrongPassphraseIsWorthRetrying() {
        assertTrue(TransferMessages.isWrongPassphrase(FwxException(FwxError.WRONG_PASSPHRASE, "x")))
        assertFalse(TransferMessages.isWrongPassphrase(FwxException(FwxError.DAMAGED, "x")))
        assertFalse(TransferMessages.isWrongPassphrase(IOException()))
    }

    @Test
    fun exportFailuresSayWhatBecameOfTheFile() {
        val e = ExportFailure(true, IOException("No space left on device"))
        assertEquals("Export failed: No space left on device. The partial file was deleted.", TransferMessages.exportFailure(e, ExportCleanup.DELETED))
        assertTrue(TransferMessages.exportFailure(e, ExportCleanup.NOT_DELETED).contains("delete it in your file manager"))
        assertTrue(TransferMessages.exportFailure(e, ExportCleanup.UNTOUCHED).endsWith("The file you picked wasn't written to."))
    }

    @Test
    fun theSummariesNameEveryKindOfData() {
        assertEquals(
            "Exported 3 snapshots with 4 observations, 3 settings, 2 paired phones and 2 confirmed networks. File size: 1.2 KB. " +
                "Anyone with this file and its passphrase can read it.",
            TransferMessages.exported(BundleCounts(3, 4, 3, 2, 2), "1.2 KB"),
        )
        val text = TransferMessages.imported(ImportSummary(2, 9, 1, 3, 1, 4))
        assertTrue(text, text.startsWith("Imported 2 snapshots with 9 observations (1 already here, skipped), 3 settings, 1 new paired phones and 4 new confirmed networks."))
        assertTrue(text, text.contains("kept the pin it had"))
        assertFalse(text, text.contains("Traffic sessions"))
        val withResolver = TransferMessages.imported(ImportSummary(0, 0, 0, 1, 0, 0, resolver = "dns.example.net (encrypted)"))
        assertTrue(withResolver, withResolver.endsWith("Traffic sessions will now send DNS lookups through dns.example.net (encrypted)."))
    }
}
