package io.github.stronghorse44.tunnels.decoder

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants

/** Sealed like the real decoder (right version, an honest self check), so the client sends it a file. */
abstract class TestDecoderStub : IIsolatedDecoder.Stub() {
    override fun protocolVersion(): Int = PROTOCOL_VERSION

    override fun selfCheck(): Int = if (Process.isIsolated()) SelfCheckBits.ISOLATED or SelfCheckBits.NO_APP_DATA else 0

    override fun canDecode(kind: Int): Int = DecodeStatus.OK.wire

    override fun openPdf(source: ParcelFileDescriptor?): DecodeReply = reply(DecodeStatus.BAD_REQUEST)

    override fun renderPdfPage(pageIndex: Int, dpi: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply = reply(DecodeStatus.BAD_REQUEST)

    override fun closePdf() {}

    protected fun reply(status: DecodeStatus, width: Int = 0, height: Int = 0, stride: Int = 0) = DecodeReply().also {
        it.status = status.wire
        it.width = width
        it.height = height
        it.stride = stride
    }
}

/** Kills its own process inside decodeImage, as a decoder memory bug would. */
class CrashingDecoderService : Service() {
    override fun onBind(intent: Intent?): IBinder = object : TestDecoderStub() {
        override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply {
            Process.killProcess(Process.myPid())
            return reply(DecodeStatus.INTERNAL)
        }
    }
}

/** Never answers decodeImage (two minutes), as a decoder stuck in a loop would. */
class HangingDecoderService : Service() {
    override fun onBind(intent: Intent?): IBinder = object : TestDecoderStub() {
        override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply {
            Thread.sleep(120_000)
            return reply(DecodeStatus.INTERNAL)
        }
    }
}

/**
 * Lies about the frame, one way per kind (each job is a fresh process, so it cannot count calls): PNG claims a 20000 x
 * 20000 frame, JPEG a stride that is not width x 4, WEBP takes away the region's protection and then reports a valid
 * 10 x 10 frame (INTERNAL when this phone did not let it change the protection).
 */
class LyingDecoderService : Service() {
    override fun onBind(intent: Intent?): IBinder = object : TestDecoderStub() {
        override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply =
            when (ImageKind.fromWire(kind)) {
                ImageKind.PNG -> reply(DecodeStatus.OK, 20_000, 20_000, 80_000)
                ImageKind.JPEG -> reply(DecodeStatus.OK, 100, 100, 404)
                ImageKind.WEBP -> {
                    val changed = runCatching { frame!!.memory.setProtect(OsConstants.PROT_NONE) }.getOrDefault(false)
                    if (changed) reply(DecodeStatus.OK, 10, 10, 40) else reply(DecodeStatus.INTERNAL)
                }
                else -> reply(DecodeStatus.BAD_REQUEST)
            }
    }
}

/**
 * Answers decodeImage with a reply header the AIDL-generated proxy would act on (audit M6 review, MEDIUM-1): PNG gets
 * an EX_PARCELABLE exception naming a boot-classpath Parcelable, JPEG a StrictMode reply header with violation records
 * followed by an otherwise valid reply. The host must read the first int only and answer BAD_REPLY.
 */
class ExceptionHeaderDecoderService : Service() {
    override fun onBind(intent: Intent?): IBinder = object : TestDecoderStub() {
        override fun decodeImage(source: ParcelFileDescriptor?, kind: Int, frame: FrameRegion?, maxPixels: Int, maxSide: Int): DecodeReply =
            reply(DecodeStatus.BAD_REQUEST)

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IIsolatedDecoder.Stub.TRANSACTION_decodeImage || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(RawDecoder.DESCRIPTOR)
            if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data).close()
            when (ImageKind.fromWire(data.readInt())) {
                ImageKind.PNG -> {
                    reply.writeInt(EX_PARCELABLE)
                    reply.writeString("not for the host")
                    reply.writeInt(0)
                    reply.writeInt(0)
                    reply.writeString("android.app.RecoverableSecurityException")
                }
                else -> {
                    reply.writeInt(EX_HAS_STRICTMODE_REPLY_HEADER)
                    reply.writeInt(16)
                    reply.writeInt(1)
                    reply.writeString("java.lang.Throwable")
                    reply.writeInt(0)
                    // What would follow the header: no exception and a well-formed 10 x 10 frame.
                    reply.writeInt(0)
                    reply.writeInt(1)
                    reply.writeInt(ReplyParcel.DECODE_REPLY_SIZE)
                    for (v in intArrayOf(DecodeStatus.OK.wire, 10, 10, 40, 0, 0, 0)) reply.writeInt(v)
                }
            }
            return true
        }
    }

    private companion object {
        // Parcel's values (hidden constants).
        const val EX_PARCELABLE = -9
        const val EX_HAS_STRICTMODE_REPLY_HEADER = -128
    }
}
