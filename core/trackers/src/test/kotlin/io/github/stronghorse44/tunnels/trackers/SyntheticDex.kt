package io.github.stronghorse44.tunnels.trackers

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds a minimal dex: header, string_ids, type_ids and string data. Enough for the type reader. */
object SyntheticDex {
    fun build(
        descriptors: List<String>,
        version: String = "035",
        extraStrings: List<String> = emptyList(),
        endianTag: Int = 0x12345678,
        headerSize: Int = DexTypeReader.HEADER_SIZE,
    ): ByteArray {
        val strings = descriptors + extraStrings
        val stringIdsOff = DexTypeReader.HEADER_SIZE
        val typeIdsOff = stringIdsOff + 4 * strings.size
        val dataOff = typeIdsOff + 4 * descriptors.size

        val data = ByteArrayOutputStream()
        val stringOffsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            stringOffsets[i] = dataOff + data.size()
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeUleb128(data, s.length)
            data.write(bytes)
            data.write(0)
        }
        val fileSize = dataOff + data.size()
        val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n".toByteArray(Charsets.US_ASCII)).put(version.toByteArray(Charsets.US_ASCII)).put(0)
        buf.putInt(0)                         // checksum
        buf.put(ByteArray(20))                // signature
        buf.putInt(fileSize)
        buf.putInt(headerSize)
        buf.putInt(endianTag)
        buf.putInt(0).putInt(0)               // link
        buf.putInt(0)                         // map_off
        buf.putInt(strings.size).putInt(stringIdsOff)
        buf.putInt(descriptors.size).putInt(typeIdsOff)
        repeat(5) { buf.putInt(0).putInt(0) } // proto, field, method, class_defs, data
        check(buf.position() == DexTypeReader.HEADER_SIZE)
        for (off in stringOffsets) buf.putInt(off)
        for (i in descriptors.indices) buf.putInt(i)
        buf.put(data.toByteArray())
        return buf.array()
    }

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) { out.write(b); return }
            out.write(b or 0x80)
        }
    }
}
