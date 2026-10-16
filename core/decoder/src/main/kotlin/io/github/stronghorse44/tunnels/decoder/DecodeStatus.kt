package io.github.stronghorse44.tunnels.decoder

/** The sealed decoder's protocol version; the host refuses a service that answers anything else. */
const val PROTOCOL_VERSION = 1

/**
 * How a decode ended. The first seven travel on the wire (service to host) with stable values; the rest are the host's
 * own and are never accepted from the wire. The service sends no text: what the user reads is the host's fixed wording.
 */
enum class DecodeStatus(val wire: Int, val fromService: Boolean) {
    OK(0, true),
    UNSUPPORTED(1, true),
    DAMAGED(2, true),
    TOO_LARGE(3, true),
    PASSWORD(4, true),
    BAD_REQUEST(5, true),
    INTERNAL(6, true),

    CRASHED(100, false),
    TIMEOUT(101, false),
    BAD_REPLY(102, false),
    UNAVAILABLE(103, false),
    REFUSED_FILE(104, false),
    PAUSED(105, false),
    CANCELLED(106, false),
    BUSY(107, false),
    ;

    /** True for the failures [FailurePolicy] counts: the ones a hostile file can cause again and again. */
    val counts: Boolean get() = this == CRASHED || this == TIMEOUT || this == BAD_REPLY

    companion object {
        private val byWire = entries.filter { it.fromService }.associateBy { it.wire }

        /** A status the service sent. A host-only value, or any value not listed, is itself [BAD_REPLY]. */
        fun fromWire(value: Int): DecodeStatus = byWire[value] ?: BAD_REPLY
    }
}

/** What the host asks the service to decode, with the MIME types the decoder's header must report for it. */
enum class ImageKind(val wire: Int, val mimeTypes: Set<String>) {
    PNG(1, setOf("image/png")),
    JPEG(2, setOf("image/jpeg")),
    WEBP(3, setOf("image/webp")),
    HEIC(4, setOf("image/heif", "image/heic")),
    ;

    /** True when the decoder's reported MIME type is what this kind says the file is. */
    fun matches(mimeType: String?): Boolean = mimeType != null && mimeType.lowercase() in mimeTypes

    companion object {
        fun fromWire(value: Int): ImageKind? = entries.firstOrNull { it.wire == value }
    }
}

/** The service's answer to selfCheck(): bit 1 ISOLATED, bit 2 NO_APP_DATA. */
object SelfCheckBits {
    /** `Process.isIsolated()` was true in the service. */
    const val ISOLATED = 1

    /** The service could neither list Tunnels' data directory nor see its databases folder. */
    const val NO_APP_DATA = 2

    /** Sealed means both bits and nothing else: an unknown bit is an answer the host does not understand. */
    fun sealed(bits: Int): Boolean = bits == ISOLATED or NO_APP_DATA

    fun isolated(bits: Int): Boolean = bits and ISOLATED != 0 && bits and (ISOLATED or NO_APP_DATA).inv() == 0

    fun noAppData(bits: Int): Boolean = bits and NO_APP_DATA != 0 && bits and (ISOLATED or NO_APP_DATA).inv() == 0
}
