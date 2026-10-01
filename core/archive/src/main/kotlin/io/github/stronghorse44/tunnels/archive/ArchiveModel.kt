package io.github.stronghorse44.tunnels.archive

import java.io.OutputStream

data class ArchiveEntry(
    /** Position in [ArchiveReader.entries]; used to select entries for extraction. */
    val index: Int,
    /** Path as stored in the archive. Not safe to use as a file path; see [SafePath]. */
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    /** Uncompressed size, or -1 when the format does not say. */
    val size: Long,
    /** Compressed size, or -1 when the format does not say. */
    val compressedSize: Long,
    val encrypted: Boolean,
) {
    val name: String get() = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}

/** Guards against archive bombs. Enforced on bytes actually written, not on header claims. */
data class ExtractLimits(
    val maxTotalBytes: Long = 16L shl 30,
    val maxEntries: Int = 200_000,
    /** Max expansion of written bytes over compressed bytes, checked once past [ratioFloorBytes]. */
    val maxRatio: Long = 1_000,
    val ratioFloorBytes: Long = 64L shl 20,
)

data class SkippedEntry(val path: String, val reason: String)

data class ExtractResult(
    val files: Int,
    val directories: Int,
    val bytes: Long,
    val skipped: List<SkippedEntry>,
)

/** Where extracted entries go. Segments are already sanitized by [SafePath]. */
interface ExtractSink {
    fun directory(segments: List<String>)
    fun file(segments: List<String>): OutputStream
}

fun interface ExtractProgress {
    /** [bytesTotal] is -1 when unknown. */
    fun onProgress(bytesDone: Long, bytesTotal: Long, current: String)

    companion object {
        val NONE = ExtractProgress { _, _, _ -> }
    }
}

sealed interface ArchiveError {
    data object PasswordRequired : ArchiveError
    data object WrongPassword : ArchiveError
    data class Unsupported(val what: String) : ArchiveError
    data class Corrupt(val detail: String) : ArchiveError
    data class LimitExceeded(val detail: String) : ArchiveError
    data object Cancelled : ArchiveError
}

class ArchiveException(val error: ArchiveError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
