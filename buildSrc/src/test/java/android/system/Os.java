package android.system;

import java.io.FileDescriptor;
import java.net.InetAddress;

/** Stand-in with the real android.system.Os signatures the egress tests call. Nothing here runs. */
public final class Os {
    private Os() {}
    public static FileDescriptor socket(int domain, int type, int protocol) { return new FileDescriptor(); }
    public static void connect(FileDescriptor fd, InetAddress address, int port) {}
    public static int sendto(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount, int flags, InetAddress inetAddress, int port) { return 0; }
    public static void setsockoptInt(FileDescriptor fd, int level, int option, int value) {}
    public static void execv(String filename, String[] argv) {}
    public static String strerror(int errno) { return ""; }
}
