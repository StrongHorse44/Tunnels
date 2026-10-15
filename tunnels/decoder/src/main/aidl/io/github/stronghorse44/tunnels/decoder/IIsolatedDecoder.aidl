package io.github.stronghorse44.tunnels.decoder;

import android.os.ParcelFileDescriptor;
import io.github.stronghorse44.tunnels.decoder.DecodeReply;
import io.github.stronghorse44.tunnels.decoder.FrameRegion;

/** Tunnels' sealed decoder (isolatedProcess). The host calls; the service never calls back. */
interface IIsolatedDecoder {
    int protocolVersion() = 1;
    /** Bit 1 ISOLATED (Process.isIsolated()), bit 2 NO_APP_DATA (the app's data directory cannot be listed). */
    int selfCheck() = 2;
    /** OK when this phone can decode [kind] in this process (HEIC: decodes the built-in sample), else UNSUPPORTED. */
    int canDecode(int kind) = 3;
    DecodeReply decodeImage(in ParcelFileDescriptor source, int kind, in FrameRegion frame, int maxPixels, int maxSide) = 4;
    DecodeReply openPdf(in ParcelFileDescriptor source) = 5;
    DecodeReply renderPdfPage(int pageIndex, int dpi, in FrameRegion frame, int maxPixels, int maxSide) = 6;
    void closePdf() = 7;
}
