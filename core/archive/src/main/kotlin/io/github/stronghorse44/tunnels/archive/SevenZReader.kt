package io.github.stronghorse44.tunnels.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import java.io.File
import java.io.InputStream

internal class SevenZReader(private val file: File, private val password: CharArray?) : ArchiveReader {
    override val format = ArchiveFormat.SEVEN_Z

    private val listing: List<SevenZArchiveEntry> =
        translating(true, password) { openFile().use { it.entries.toList() } }
    private val anyEncrypted = listing.any { isEncrypted(it) }

    override fun entries(): List<ArchiveEntry> = listing.mapIndexed { i, e ->
        ArchiveEntry(
            index = i,
            path = e.name ?: "file$i",
            isDirectory = e.isDirectory,
            isSymlink = isSymlink(e),
            size = if (e.hasStream()) e.size else 0,
            compressedSize = -1,
            encrypted = isEncrypted(e),
        )
    }

    override fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits,
        progress: ExtractProgress,
        isCancelled: () -> Boolean,
    ): ExtractResult {
        val chosen = listing.withIndex().filter { selection == null || it.index in selection }
        if (password == null && chosen.any { isEncrypted(it.value) }) {
            throw ArchiveException(ArchiveError.PasswordRequired)
        }
        val total = chosen.sumOf { if (it.value.hasStream()) it.value.size else 0 }
        val run = Extraction(limits, file.length(), total, progress, isCancelled)
        translating(anyEncrypted, password) {
            openFile().use { sz ->
                var index = -1
                while (true) {
                    val e = sz.nextEntry ?: break
                    index++
                    if (selection != null && index !in selection) continue
                    run.beginEntry()
                    val name = e.name ?: "file$index"
                    val segments = SafePath.segments(name)
                    when {
                        segments == null -> run.skip(name, "unsafe path")
                        e.isAntiItem -> run.skip(name, "deletion marker")
                        e.isDirectory -> run.directory(sink, segments)
                        isSymlink(e) -> run.skip(name, "symbolic link")
                        else -> run.file(sink, segments, name, -1, CurrentEntryStream(sz))
                    }
                }
            }
        }
        return run.result()
    }

    override fun close() = Unit

    private fun openFile(): SevenZFile =
        SevenZFile.builder().setFile(file).apply { if (password != null) setPassword(password) }.get()

    private fun isEncrypted(e: SevenZArchiveEntry): Boolean =
        e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true

    private fun isSymlink(e: SevenZArchiveEntry): Boolean {
        if (!e.hasWindowsAttributes) return false
        val attrs = e.windowsAttributes
        val unixExtension = attrs and 0x8000 != 0
        return unixExtension && (attrs ushr 16) and 0xF000 == 0xA000
    }

    /** Reads the entry the [SevenZFile] is positioned at. */
    private class CurrentEntryStream(private val sz: SevenZFile) : InputStream() {
        override fun read(): Int = sz.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
    }
}
