package io.github.stronghorse44.tunnels.decoder

/** The service's check of a source image's header, before any pixel is allocated. */
object SourceCheck {
    /** Null when a [width] x [height] source may be decoded; DAMAGED for an empty size, TOO_LARGE over the source caps. */
    fun header(width: Int, height: Int): DecodeStatus? = when {
        width <= 0 || height <= 0 -> DecodeStatus.DAMAGED
        width > FrameLimits.MAX_SOURCE_SIDE || height > FrameLimits.MAX_SOURCE_SIDE -> DecodeStatus.TOO_LARGE
        width.toLong() * height > FrameLimits.MAX_SOURCE_PIXELS -> DecodeStatus.TOO_LARGE
        else -> null
    }
}
