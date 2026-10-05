package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import java.io.InputStream
import java.io.OutputStream

/** A finished export: what it holds and how many bytes were written. */
class ExportResult(val counts: BundleCounts, val bytes: Long)

/**
 * The export failed. [opened] says whether the destination was opened for writing (and so was truncated or begun):
 * if not, an existing file the user picked was never touched. [cause] is the codec's [FwxException], the
 * destination's own IOException, or an IllegalArgumentException for data a bundle cannot carry.
 */
class ExportFailure(val opened: Boolean, cause: Throwable) : Exception(cause.message, cause)

/** The finished file did not read back as what was written. */
class ReadBackMismatch : Exception("the file did not read back the same")

object TunnelsExport {
    /**
     * Writes [data] as a bundle, then proves it: the entries are parsed as an import would parse them before the
     * destination is opened, and the whole finished file is read back through the import's reader with the same
     * passphrase and compared with [data]. So an export cannot succeed on anything an import would refuse. Nothing but
     * the destination is written. [open] returns the destination (opened to replace its content), [reopen] the same
     * file for reading. On any failure the caller deletes the destination per [ExportFailure.opened].
     */
    fun run(
        data: TunnelsData,
        passphrase: CharArray,
        createdMs: Long,
        appVersion: String,
        open: () -> OutputStream,
        reopen: () -> InputStream,
    ): ExportResult {
        var opened = false
        var entries: List<EncodedEntry> = emptyList()
        try {
            PassphraseCheck.problem(passphrase)?.let { throw FwxException(FwxError.BAD_PASSPHRASE, it) }
            entries = TunnelsBundle.encode(data, appVersion)
            if (TunnelsBundle.check(entries) != data) throw FwxException(FwxError.MALFORMED_PAYLOAD, "the data does not survive its own encoding")
            val counting = Counting(open())
            opened = true
            counting.use { TunnelsBundle.write(it, passphrase, entries, createdMs) }
            val back = reopen().use { TunnelsBundle.read(it, passphrase) }
            if (back != data) throw ReadBackMismatch()
            return ExportResult(data.counts, counting.written)
        } catch (e: Exception) {
            throw ExportFailure(opened, e)
        } finally {
            TunnelsBundle.wipe(entries)
        }
    }

    private class Counting(private val out: OutputStream) : OutputStream() {
        var written = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
