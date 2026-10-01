package io.github.stronghorse44.tunnels.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** What one packet read from the TUN turned out to be. */
sealed interface IpPacket {
    data class Udp(
        val version: Int,
        val src: InetAddress,
        val dst: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
        /** True when the IPv4 header checksum verified (always true for IPv6, which has none). */
        val ipChecksumOk: Boolean,
        /** True when the UDP checksum verified or, for IPv4, was omitted. */
        val udpChecksumOk: Boolean,
    ) : IpPacket

    /** Any other transport. Ports are filled in for TCP so port 853 can be counted and attributed. */
    data class Other(
        val version: Int,
        val protocol: Int,
        val src: InetAddress,
        val dst: InetAddress,
        val srcPort: Int?,
        val dstPort: Int?,
    ) : IpPacket

    data class Malformed(val reason: String) : IpPacket
}

/** IPv4/IPv6 + UDP header parsing and building, with checksums. No allocation beyond the result. */
object IpPackets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val IPV4_HEADER = 20
    const val IPV6_HEADER = 40
    const val UDP_HEADER = 8
    private const val TTL = 64

    fun parse(packet: ByteArray, length: Int = packet.size): IpPacket {
        if (length < 0 || length > packet.size) return IpPacket.Malformed("bad length")
        if (length < 1) return IpPacket.Malformed("empty")
        return when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> parse4(packet, length)
            6 -> parse6(packet, length)
            else -> IpPacket.Malformed("not IP")
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun parse4(p: ByteArray, length: Int): IpPacket {
        if (length < IPV4_HEADER) return IpPacket.Malformed("IPv4 header truncated")
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER) return IpPacket.Malformed("IPv4 header length $ihl")
        val total = u16(p, 2)
        if (total < ihl) return IpPacket.Malformed("IPv4 total length $total below header")
        if (total > length || ihl > length) return IpPacket.Malformed("IPv4 packet truncated")
        val protocol = u8(p, 9)
        val ipOk = finish(sum(p, 0, ihl)) == 0
        val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
        return when (protocol) {
            PROTO_UDP -> udp(p, ihl, total, 4, src, dst, ipOk)
            PROTO_TCP -> tcp(p, ihl, total - ihl, 4, src, dst)
            else -> IpPacket.Other(4, protocol, src, dst, null, null)
        }
    }

    private fun parse6(p: ByteArray, length: Int): IpPacket {
        if (length < IPV6_HEADER) return IpPacket.Malformed("IPv6 header truncated")
        val payloadLength = u16(p, 4)
        val total = IPV6_HEADER + payloadLength
        if (total > length) return IpPacket.Malformed("IPv6 packet truncated")
        val next = u8(p, 6)
        val src = InetAddress.getByAddress(p.copyOfRange(8, 24))
        val dst = InetAddress.getByAddress(p.copyOfRange(24, 40))
        return when (next) {
            PROTO_UDP -> udp(p, IPV6_HEADER, total, 6, src, dst, ipOk = true)
            PROTO_TCP -> tcp(p, IPV6_HEADER, payloadLength, 6, src, dst)
            else -> IpPacket.Other(6, next, src, dst, null, null)
        }
    }

    private fun tcp(p: ByteArray, start: Int, available: Int, version: Int, src: InetAddress, dst: InetAddress): IpPacket {
        val ports = available >= 4
        return IpPacket.Other(version, PROTO_TCP, src, dst, if (ports) u16(p, start) else null, if (ports) u16(p, start + 2) else null)
    }

    private fun udp(p: ByteArray, start: Int, total: Int, version: Int, src: InetAddress, dst: InetAddress, ipOk: Boolean): IpPacket {
        if (total - start < UDP_HEADER) return IpPacket.Malformed("UDP header truncated")
        val udpLength = u16(p, start + 4)
        if (udpLength < UDP_HEADER) return IpPacket.Malformed("UDP length $udpLength")
        if (start + udpLength > total) return IpPacket.Malformed("UDP datagram truncated")
        val checksum = u16(p, start + 6)
        val udpOk = if (version == 4 && checksum == 0) {
            true
        } else {
            finish(sum(p, start, udpLength, pseudoHeaderSum(src, dst, udpLength))) == 0
        }
        val payload = p.copyOfRange(start + UDP_HEADER, start + udpLength)
        return IpPacket.Udp(version, src, dst, u16(p, start), u16(p, start + 2), payload, ipOk, udpOk)
    }

    /** A complete IP/UDP packet carrying [payload], ready to be written to the TUN. */
    fun buildUdp(src: InetAddress, dst: InetAddress, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        require(srcPort in 0..0xFFFF && dstPort in 0..0xFFFF) { "port out of range" }
        val udpLength = UDP_HEADER + payload.size
        require(udpLength <= 0xFFFF) { "payload too large" }
        val v6 = when {
            src is Inet4Address && dst is Inet4Address -> false
            src is Inet6Address && dst is Inet6Address -> true
            else -> throw IllegalArgumentException("mixed address families")
        }
        val ipHeader = if (v6) IPV6_HEADER else IPV4_HEADER
        val out = ByteArray(ipHeader + udpLength)
        fun put16(i: Int, v: Int) {
            out[i] = (v shr 8).toByte(); out[i + 1] = v.toByte()
        }
        if (v6) {
            out[0] = 0x60
            put16(4, udpLength)
            out[6] = PROTO_UDP.toByte()
            out[7] = TTL.toByte()
            src.address.copyInto(out, 8)
            dst.address.copyInto(out, 24)
        } else {
            out[0] = 0x45
            put16(2, out.size)
            put16(6, 0x4000) // don't fragment
            out[8] = TTL.toByte()
            out[9] = PROTO_UDP.toByte()
            src.address.copyInto(out, 12)
            dst.address.copyInto(out, 16)
            put16(10, finish(sum(out, 0, IPV4_HEADER)))
        }
        val u = ipHeader
        put16(u, srcPort)
        put16(u + 2, dstPort)
        put16(u + 4, udpLength)
        payload.copyInto(out, u + UDP_HEADER)
        val udpSum = finish(sum(out, u, udpLength, pseudoHeaderSum(src, dst, udpLength)))
        put16(u + 6, if (udpSum == 0) 0xFFFF else udpSum)
        return out
    }

    /** One's-complement sum of 16-bit big-endian words (odd trailing byte zero-padded), unfolded. */
    fun sum(data: ByteArray, offset: Int, length: Int, initial: Long = 0): Long {
        var s = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (data[i].toInt() and 0xFF) shl 8
        return s
    }

    /** Folds the carries and inverts: the value that goes into a checksum field. */
    fun finish(sum: Long): Int {
        var s = sum
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return s.toInt().inv() and 0xFFFF
    }

    private fun pseudoHeaderSum(src: InetAddress, dst: InetAddress, udpLength: Int): Long {
        val a = src.address
        val b = dst.address
        var s = sum(a, 0, a.size) + sum(b, 0, b.size)
        s += PROTO_UDP.toLong()
        s += udpLength.toLong()
        return s
    }
}
