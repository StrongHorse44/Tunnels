package io.github.stronghorse44.tunnels.decoder;

/** The service's answer: ints only, read by AIDL's generated reader. The host checks every field (core:decoder ReplyCheck). */
parcelable DecodeReply {
    int status;
    int width;
    int height;
    int stride;
    int colorSpace;
    int flags;
    int pageCount;
}
