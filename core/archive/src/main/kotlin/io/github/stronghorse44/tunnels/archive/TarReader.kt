package io.github.stronghorse44.tunnels.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Plain or compressed tar. Streams the file once to list and once per extraction. */
internal class TarReader(private val file: File, override val format: ArchiveFormat) : ArchiveReader {

    private val listing: List<ArchiveEntry> = translating(false, null) {
        val out = mutableListOf<ArchiveEntry>()
        open().use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                out += ArchiveEntry(
                    index = out.size,
                    path = e.name,
                    isDirectory = e.isDirectory,
                    isSymlink = e.isSymbolicLink || e.isLink,
                    size = if (e.isFile) e.size else 0,
                    compressedSize = -1,
                    encrypted = false,
                )
            }
        }
        out
    }

    override fun entries() = listing

    override fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits,
        progress: ExtractProgress,
        isCancelled: () -> Boolean,
    ): ExtractResult {
        val total = listing.filter { selection == null || it.index in selection }.sumOf { it.size }
        val run = Extraction(limits, file.length(), total, progress, isCancelled)
        translating(false, null) {
            open().use { tar ->
                var index = -1
                while (true) {
                    val e: TarArchiveEntry = tar.nextEntry ?: break
                    index++
                    if (selection != null && index !in selection) continue
                    run.beginEntry()
                    val segments = SafePath.segments(e.name)
                    when {
                        segments == null -> run.skip(e.name, "unsafe path")
                        e.isDirectory -> run.directory(sink, segments)
                        e.isSymbolicLink || e.isLink -> run.skip(e.name, "link")
                        !e.isFile -> run.skip(e.name, "special file")
                        else -> run.file(sink, segments, e.name, -1, tar)
                    }
                }
            }
        }
        return run.result()
    }

    override fun close() = Unit

    private fun open(): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(FileInputStream(file))
        val decompressed = when (format) {
            ArchiveFormat.TAR_GZ -> ArchiveFormat.gzip(raw)
            ArchiveFormat.TAR_XZ -> ArchiveFormat.xz(raw)
            ArchiveFormat.TAR_BZ2 -> ArchiveFormat.bzip2(raw)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }
}
