package io.github.stronghorse44.tunnels.archive

import java.io.InputStream
import java.io.OutputStream

/** Shared bookkeeping for one extraction run: limits, progress, skipped entries. */
internal class Extraction(
    private val limits: ExtractLimits,
    private val archiveLength: Long,
    private val totalHint: Long,
    private val progress: ExtractProgress,
    private val isCancelled: () -> Boolean,
) {
    var bytes = 0L
        private set
    var files = 0
        private set
    var directories = 0
        private set
    private var entries = 0
    val skipped = mutableListOf<SkippedEntry>()

    fun skip(path: String, reason: String) {
        skipped += SkippedEntry(path, reason)
    }

    fun beginEntry() {
        if (isCancelled()) throw ArchiveException(ArchiveError.Cancelled)
        if (++entries > limits.maxEntries) {
            throw ArchiveException(ArchiveError.LimitExceeded("more than ${limits.maxEntries} entries"))
        }
    }

    fun directory(sink: ExtractSink, segments: List<String>) {
        sink.directory(segments)
        directories++
    }

    fun file(sink: ExtractSink, segments: List<String>, path: String, compressedSize: Long, input: InputStream) {
        sink.file(segments).use { out -> copy(input, out, path, compressedSize) }
        files++
    }

    private fun copy(input: InputStream, out: OutputStream, path: String, compressedSize: Long) {
        val buf = ByteArray(64 * 1024)
        var entryBytes = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            entryBytes += r
            bytes += r
            if (bytes > limits.maxTotalBytes) {
                throw ArchiveException(ArchiveError.LimitExceeded("output would exceed ${limits.maxTotalBytes shr 20} MB"))
            }
            if (compressedSize > 0 && entryBytes > limits.ratioFloorBytes && entryBytes > compressedSize * limits.maxRatio) {
                throw ArchiveException(ArchiveError.LimitExceeded("$path expands suspiciously (possible archive bomb)"))
            }
            if (archiveLength > 0 && bytes > limits.ratioFloorBytes && bytes > archiveLength * limits.maxRatio) {
                throw ArchiveException(ArchiveError.LimitExceeded("archive expands suspiciously (possible archive bomb)"))
            }
            if (isCancelled()) throw ArchiveException(ArchiveError.Cancelled)
            progress.onProgress(bytes, totalHint, path)
        }
    }

    fun result() = ExtractResult(files, directories, bytes, skipped.toList())
}

/** Maps library exceptions onto [ArchiveError]. */
internal inline fun <T> translating(encrypted: Boolean, password: CharArray?, block: () -> T): T = try {
    block()
} catch (e: ArchiveException) {
    throw e
} catch (e: org.apache.commons.compress.PasswordRequiredException) {
    throw ArchiveException(ArchiveError.PasswordRequired, e)
} catch (e: net.lingala.zip4j.exception.ZipException) {
    val type = e.type
    throw when {
        type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD ->
            ArchiveException(if (password == null) ArchiveError.PasswordRequired else ArchiveError.WrongPassword, e)
        encrypted && password != null -> ArchiveException(ArchiveError.WrongPassword, e)
        encrypted -> ArchiveException(ArchiveError.PasswordRequired, e)
        else -> ArchiveException(ArchiveError.Corrupt(e.message ?: "unreadable zip"), e)
    }
} catch (e: java.io.IOException) {
    throw if (encrypted && password != null) {
        ArchiveException(ArchiveError.WrongPassword, e)
    } else {
        ArchiveException(ArchiveError.Corrupt(e.message ?: e.javaClass.simpleName), e)
    }
} catch (e: RuntimeException) {
    // Decoders throw unchecked exceptions on malformed input.
    throw ArchiveException(ArchiveError.Corrupt(e.message ?: e.javaClass.simpleName), e)
}
