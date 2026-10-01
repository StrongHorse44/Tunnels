package io.github.stronghorse44.tunnels.archive

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

enum class ArchiveFormat(val label: String, val supported: Boolean = true) {
    ZIP("ZIP"),
    SEVEN_Z("7z"),
    TAR("tar"),
    TAR_GZ("tar.gz"),
    TAR_XZ("tar.xz"),
    TAR_BZ2("tar.bz2"),
    GZ("gzip"),
    XZ("xz"),
    BZ2("bzip2"),
    RAR("RAR", supported = false),
    UNKNOWN("unknown", supported = false);

    companion object {
        /** Detects by content, never by file name. */
        fun detect(file: File): ArchiveFormat {
            val head = ByteArray(512)
            val n = FileInputStream(file).use { readFully(it, head) }
            fun at(offset: Int, vararg bytes: Int) =
                n >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xff == bytes[it] }
            return when {
                at(0, 0x50, 0x4b, 0x03, 0x04) || at(0, 0x50, 0x4b, 0x05, 0x06) || at(0, 0x50, 0x4b, 0x07, 0x08) -> ZIP
                at(0, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) -> SEVEN_Z
                at(0, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07) -> RAR
                at(0, 0x1f, 0x8b) -> if (innerIsTar(file, ::gzip)) TAR_GZ else GZ
                at(0, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00) -> if (innerIsTar(file, ::xz)) TAR_XZ else XZ
                at(0, 0x42, 0x5a, 0x68) -> if (innerIsTar(file, ::bzip2)) TAR_BZ2 else BZ2
                isTarHeader(head, n) -> TAR
                else -> UNKNOWN
            }
        }

        internal fun gzip(input: InputStream): InputStream = GzipCompressorInputStream(input, true)
        internal fun xz(input: InputStream): InputStream = XZCompressorInputStream(input, true)
        internal fun bzip2(input: InputStream): InputStream = BZip2CompressorInputStream(input, true)

        private fun innerIsTar(file: File, decompress: (InputStream) -> InputStream): Boolean = try {
            decompress(BufferedInputStream(FileInputStream(file))).use { input ->
                val block = ByteArray(512)
                isTarHeader(block, readFully(input, block))
            }
        } catch (_: Exception) {
            false
        }

        /** A ustar magic, or a valid v7 header checksum. */
        internal fun isTarHeader(block: ByteArray, length: Int): Boolean {
            if (length < 512) return false
            if (String(block, 257, 5, Charsets.US_ASCII) == "ustar") return true
            if (block.all { it.toInt() == 0 }) return false
            val stored = String(block, 148, 8, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
            val expected = stored.toLongOrNull(8) ?: return false
            var sum = 0L
            for (i in 0 until 512) sum += if (i in 148..155) 0x20 else block[i].toInt() and 0xff
            return sum == expected
        }

        internal fun readFully(input: InputStream, buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val r = input.read(buf, total, buf.size - total)
                if (r < 0) break
                total += r
            }
            return total
        }
    }
}
