package io.github.stronghorse44.tunnels.decoder

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * The host's side of IIsolatedDecoder, written by hand over the raw binder (audit M6 review, MEDIUM-1). The service
 * keeps the AIDL-generated Stub; the host does not use the generated Proxy, because that one calls
 * `Parcel.readException()` on every reply, and a compromised service could then make the host deserialize StrictMode
 * violation records or unparcel a boot-classpath Parcelable exception.
 *
 * Arguments are written exactly as the generated Proxy writes them, with the Stub's own transaction codes. Replies are
 * read with [ReplyParcel]: an exception code that must be 0, then ints only. A malformed reply throws [Malformed],
 * which the client turns into BAD_REPLY; a dead or failing binder throws RemoteException (CRASHED).
 */
internal class RawDecoder(private val binder: IBinder) {
    /** A reply the host does not read further. Carries no text. */
    class Malformed : Exception(null, null, false, false)

    fun protocolVersion(): Int = int(IIsolatedDecoder.Stub.TRANSACTION_protocolVersion) {}

    fun selfCheck(): Int = int(IIsolatedDecoder.Stub.TRANSACTION_selfCheck) {}

    fun canDecode(kind: Int): Int = int(IIsolatedDecoder.Stub.TRANSACTION_canDecode) { writeInt(kind) }

    fun decodeImage(source: ParcelFileDescriptor, kind: Int, frame: FrameRegion, maxPixels: Int, maxSide: Int): WireReply =
        reply(IIsolatedDecoder.Stub.TRANSACTION_decodeImage) {
            writeTyped(source)
            writeInt(kind)
            writeTyped(frame)
            writeInt(maxPixels)
            writeInt(maxSide)
        }

    fun openPdf(source: ParcelFileDescriptor): WireReply = reply(IIsolatedDecoder.Stub.TRANSACTION_openPdf) { writeTyped(source) }

    fun renderPdfPage(pageIndex: Int, dpi: Int, frame: FrameRegion, maxPixels: Int, maxSide: Int): WireReply =
        reply(IIsolatedDecoder.Stub.TRANSACTION_renderPdfPage) {
            writeInt(pageIndex)
            writeInt(dpi)
            writeTyped(frame)
            writeInt(maxPixels)
            writeInt(maxSide)
        }

    fun closePdf() {
        transact(IIsolatedDecoder.Stub.TRANSACTION_closePdf, {}) { if (!ReplyParcel.none(it)) throw Malformed() }
    }

    private fun int(code: Int, args: Parcel.() -> Unit): Int = transact(code, args) { ReplyParcel.int(it) ?: throw Malformed() }

    private fun reply(code: Int, args: Parcel.() -> Unit): WireReply = transact(code, args) { ReplyParcel.decodeReply(it) ?: throw Malformed() }

    private fun <T> transact(code: Int, args: Parcel.() -> Unit, read: (IntReader) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.args()
            if (!binder.transact(code, data, reply, 0)) throw Malformed()
            reply.setDataPosition(0)
            return read(Ints(reply))
        } finally {
            // Recycling also releases any descriptor or binder the service put in the reply; the host never reads them.
            reply.recycle()
            data.recycle()
        }
    }

    /** As the generated Proxy writes an `in` parcelable: a presence int, then the object. */
    private fun Parcel.writeTyped(value: Parcelable) {
        writeInt(1)
        value.writeToParcel(this, 0)
    }

    private class Ints(private val p: Parcel) : IntReader {
        override fun available(): Int = p.dataAvail()

        override fun readInt(): Int = p.readInt()
    }

    companion object {
        /** The interface descriptor the Stub enforces (DecoderClientTest checks it against the Stub's own). */
        const val DESCRIPTOR = "io.github.stronghorse44.tunnels.decoder.IIsolatedDecoder"
    }
}
