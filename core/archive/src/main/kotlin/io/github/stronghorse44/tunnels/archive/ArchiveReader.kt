package io.github.stronghorse44.tunnels.archive

import java.io.Closeable
import java.io.File

interface ArchiveReader : Closeable {
    val format: ArchiveFormat

    /** All entries in archive order. Indices are stable for the life of the reader. */
    fun entries(): List<ArchiveEntry>

    /**
     * Extracts [selection] (entry indices; null = everything) into [sink]. Unsafe paths, symlinks and
     * special files are skipped and reported. Throws [ArchiveException].
     */
    fun extract(
        selection: Set<Int>?,
        sink: ExtractSink,
        limits: ExtractLimits = ExtractLimits(),
        progress: ExtractProgress = ExtractProgress.NONE,
        isCancelled: () -> Boolean = { false },
    ): ExtractResult
}

object Archives {
    /** Opens [file] by its content. [displayName] names the output of single-file formats like .gz. */
    fun open(file: File, displayName: String, password: CharArray? = null): ArchiveReader =
        when (val format = ArchiveFormat.detect(file)) {
            ArchiveFormat.ZIP -> ZipReader(file, password)
            ArchiveFormat.SEVEN_Z -> SevenZReader(file, password)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2 -> TarReader(file, format)
            ArchiveFormat.GZ, ArchiveFormat.XZ, ArchiveFormat.BZ2 -> SingleStreamReader(file, format, displayName)
            ArchiveFormat.PMTILES -> PmTilesReader(file)
            ArchiveFormat.RAR -> throw ArchiveException(ArchiveError.Unsupported("RAR archives"))
            ArchiveFormat.UNKNOWN -> throw ArchiveException(ArchiveError.Unsupported("this file type"))
        }
}
