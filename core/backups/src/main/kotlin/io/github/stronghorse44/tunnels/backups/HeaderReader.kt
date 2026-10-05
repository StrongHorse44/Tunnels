package io.github.stronghorse44.tunnels.backups

import io.github.stronghorse44.tunnels.export.fwx.Fwx
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** What the first bytes of one file say. Every value is unverified: the header MAC needs the passphrase (spec section 6). */
sealed interface HeaderRead {
    data class Bundle(val appId: String, val schema: Long, val createdMs: Long) : HeaderRead

    /** The old Tunnels `TSNAPE1` export: no app or time field, so the caller falls back to the file's date. */
    data object Legacy : HeaderRead

    /** Not a readable FWX v1 file. [reason] is a short code for the count, never shown as a finding. */
    data class Unreadable(val reason: String) : HeaderRead
}

/**
 * Reads a bundle header with the codec's passphrase-free reader and nothing more. [MAX_BYTES] is the spec's bound
 * (170 + 32): the stream handed to the codec ends there, so not one payload byte can be read, whatever the file is.
 */
object HeaderReader {
    const val MAX_BYTES = 202

    fun read(open: () -> InputStream): HeaderRead = try {
        open().use { raw ->
            val h = Fwx.readHeader(Capped(raw, MAX_BYTES))
            HeaderRead.Bundle(h.appId, h.schemaVersion, h.createdMs)
        }
    } catch (e: FwxException) {
        if (e.code == FwxError.LEGACY) HeaderRead.Legacy else HeaderRead.Unreadable(e.code.name)
    } catch (_: IOException) {
        HeaderRead.Unreadable("IO")
    } catch (_: SecurityException) {
        HeaderRead.Unreadable("DENIED")
    }

    /** Ends after [limit] bytes. */
    private class Capped(input: InputStream, private var left: Int) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            if (len == 0) return 0
            val n = super.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }

        override fun skip(n: Long): Long {
            val k = super.skip(minOf(n, left.toLong()))
            if (k > 0) left -= k.toInt()
            return k
        }

        override fun markSupported() = false
    }
}
