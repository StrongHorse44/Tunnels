package io.github.stronghorse44.tunnels.archive

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.File

/** ZIP via zip4j, which also handles ZipCrypto and AES encrypted entries. */
internal class ZipReader(private val file: File, private val password: CharArray?) : ArchiveReader {
    override val format = ArchiveFormat.ZIP

    private val zip = ZipFile(file, password)
    private val headers: List<FileHeader> = translating(false, null) { zip.fileHeaders.toList() }
    private val anyEncrypted = headers.any { it.isEncrypted }

    override fun entries(): List<ArchiveEntry> = headers.mapIndexed { i, h ->
        ArchiveEntry(
            index = i,
            path = h.fileName,
            isDirectory = h.isDirectory,
            isSymlink = isSymlink(h),
            size = h.uncompressedSize,
            compressedSize = h.compressedSize,
            encrypted = h.isEncrypted,
        )
    }

    override fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits,
        progress: ExtractProgress,
        isCancelled: () -> Boolean,
    ): ExtractResult {
        val chosen = headers.withIndex().filter { selection == null || it.index in selection }
        if (password == null && chosen.any { it.value.isEncrypted }) {
            throw ArchiveException(ArchiveError.PasswordRequired)
        }
        val total = chosen.sumOf { it.value.uncompressedSize.coerceAtLeast(0) }
        val run = Extraction(limits, file.length(), total, progress, isCancelled)
        translating(anyEncrypted, password) {
            for ((_, h) in chosen) {
                run.beginEntry()
                val segments = SafePath.segments(h.fileName)
                when {
                    segments == null -> run.skip(h.fileName, "unsafe path")
                    h.isDirectory -> run.directory(sink, segments)
                    isSymlink(h) -> run.skip(h.fileName, "symbolic link")
                    else -> zip.getInputStream(h).use { run.file(sink, segments, h.fileName, h.compressedSize, it) }
                }
            }
        }
        return run.result()
    }

    override fun close() = zip.close()

    private fun isSymlink(h: FileHeader): Boolean {
        val attrs = h.externalFileAttributes ?: return false
        if (attrs.size < 4) return false
        val unixMode = ((attrs[3].toInt() and 0xff) shl 8) or (attrs[2].toInt() and 0xff)
        return unixMode and 0xF000 == 0xA000
    }
}
