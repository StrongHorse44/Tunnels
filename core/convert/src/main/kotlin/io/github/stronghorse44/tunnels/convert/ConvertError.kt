package io.github.stronghorse44.tunnels.convert

sealed interface ConvertError {
    data class Unsupported(val detail: String) : ConvertError
    data class Damaged(val detail: String) : ConvertError
    data class LimitExceeded(val detail: String) : ConvertError
}

class ConvertException(val error: ConvertError) : Exception(
    when (error) {
        is ConvertError.Unsupported -> error.detail
        is ConvertError.Damaged -> error.detail
        is ConvertError.LimitExceeded -> error.detail
    },
)

/** Safety limits for untrusted input. A file over any of them is refused, not truncated. */
data class ConvertLimits(
    val maxInputBytes: Long = 64L * 1024 * 1024,
    /** Uncompressed size of the one XML part read from a .docx or .odt. */
    val maxXmlBytes: Long = 96L * 1024 * 1024,
    val maxZipEntries: Int = 20_000,
    val maxChars: Int = 20_000_000,
    val maxDepth: Int = 1_000,
) {
    companion object {
        val DEFAULT = ConvertLimits()
    }
}
