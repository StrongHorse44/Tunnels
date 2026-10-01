package io.github.stronghorse44.tunnels.archive

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** A single compressed file (.gz, .xz, .bz2) presented as a one-entry archive. */
internal class SingleStreamReader(
    private val file: File,
    override val format: ArchiveFormat,
    displayName: String,
) : ArchiveReader {

    private val outputName: String = run {
        val lower = displayName.lowercase()
        val ext = listOf(".gz", ".gzip", ".xz", ".bz2", ".bzip2").firstOrNull { lower.endsWith(it) }
        val stripped = if (ext != null) displayName.dropLast(ext.length) else "$displayName.out"
        stripped.ifBlank { "file" }
    }

    override fun entries() = listOf(
        ArchiveEntry(0, outputName, isDirectory = false, isSymlink = false, size = -1, compressedSize = file.length(), encrypted = false),
    )

    override fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits,
        progress: ExtractProgress,
        isCancelled: () -> Boolean,
    ): ExtractResult {
        val run = Extraction(limits, file.length(), -1, progress, isCancelled)
        if (selection != null && 0 !in selection) return run.result()
        translating(false, null) {
            open().use { input ->
                run.beginEntry()
                val segments = SafePath.segments(outputName) ?: listOf("file")
                run.file(sink, segments, outputName, file.length(), input)
            }
        }
        return run.result()
    }

    override fun close() = Unit

    private fun open(): InputStream {
        val raw = BufferedInputStream(FileInputStream(file))
        return when (format) {
            ArchiveFormat.GZ -> ArchiveFormat.gzip(raw)
            ArchiveFormat.XZ -> ArchiveFormat.xz(raw)
            else -> ArchiveFormat.bzip2(raw)
        }
    }
}
