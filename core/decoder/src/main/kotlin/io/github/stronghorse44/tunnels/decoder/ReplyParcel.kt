package io.github.stronghorse44.tunnels.decoder

/** The ints of a binder reply, read in order. The Android side wraps a Parcel; tests wrap an array. */
interface IntReader {
    /** Bytes left to read. */
    fun available(): Int

    fun readInt(): Int
}

/**
 * Reads a sealed decoder's binder reply by hand (audit M6 review, MEDIUM-1). The AIDL-generated proxy would call
 * `Parcel.readException()`, which acts on headers the service chooses: a StrictMode reply header makes the host
 * deserialize violation records, and an EX_PARCELABLE exception makes it unparcel a boot-classpath Parcelable. So the
 * host never calls it. It reads only:
 * - the exception code, which must be 0 (no exception and no reply header of any kind);
 * - then one int, or for a DecodeReply its presence int (1), its size (exactly [DECODE_REPLY_SIZE]) and its 7 ints.
 * Anything short of that is malformed (null). Data after what is read is ignored: it is never looked at, and the
 * Parcel is recycled with it.
 */
object ReplyParcel {
    /** A structured parcelable's size int counts itself and its 7 int fields. */
    const val DECODE_REPLY_SIZE = 4 + 7 * 4

    /** The reply to a void call: only the exception code, 0. */
    fun none(r: IntReader): Boolean = header(r)

    /** The reply to a call returning int. */
    fun int(r: IntReader): Int? {
        if (!header(r) || r.available() < 4) return null
        return r.readInt()
    }

    /** The reply to a call returning DecodeReply. */
    fun decodeReply(r: IntReader): WireReply? {
        if (!header(r) || r.available() < 4 + DECODE_REPLY_SIZE) return null
        if (r.readInt() != 1) return null
        if (r.readInt() != DECODE_REPLY_SIZE) return null
        return WireReply(r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt())
    }

    private fun header(r: IntReader): Boolean = r.available() >= 4 && r.readInt() == 0
}
