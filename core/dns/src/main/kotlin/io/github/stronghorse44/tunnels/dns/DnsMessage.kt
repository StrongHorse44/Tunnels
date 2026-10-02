package io.github.stronghorse44.tunnels.dns

class DnsFormatException(message: String) : Exception(message)

data class DnsQuestion(val name: String, val type: Int, val clazz: Int) {
    val typeName: String get() = DnsMessage.typeName(type)
}

/**
 * The header and question section of a DNS message (RFC 1035). Answers are counted, not decoded:
 * the tunnel only needs to know which names were asked for and whether the reply was an error.
 */
data class DnsMessage(
    val id: Int,
    val isResponse: Boolean,
    val opcode: Int,
    val isTruncated: Boolean,
    val recursionDesired: Boolean,
    val rcode: Int,
    val questions: List<DnsQuestion>,
    val answerCount: Int,
    val authorityCount: Int,
    val additionalCount: Int,
) {
    val rcodeName: String get() = rcodeName(rcode)

    /** The first question's name, lowercased, or null for a message without questions. */
    val queryName: String? get() = questions.firstOrNull()?.name

    companion object {
        const val PORT = 53
        /** DNS over TLS: encrypted, so queries on it can only be counted, never read. */
        const val PORT_TLS = 853
        const val HEADER_LENGTH = 12
        const val MAX_NAME_LENGTH = 255
        const val MAX_LABEL_LENGTH = 63
        /** Real queries carry one question; anything claiming more than this is treated as garbage. */
        const val MAX_QUESTIONS = 16
        private const val MAX_POINTER_HOPS = 64

        const val TYPE_A = 1
        const val TYPE_NS = 2
        const val TYPE_CNAME = 5
        const val TYPE_SOA = 6
        const val TYPE_PTR = 12
        const val TYPE_MX = 15
        const val TYPE_TXT = 16
        const val TYPE_AAAA = 28
        const val TYPE_SRV = 33
        const val TYPE_HTTPS = 65
        const val TYPE_ANY = 255

        fun typeName(type: Int): String = when (type) {
            TYPE_A -> "A"
            TYPE_NS -> "NS"
            TYPE_CNAME -> "CNAME"
            TYPE_SOA -> "SOA"
            TYPE_PTR -> "PTR"
            TYPE_MX -> "MX"
            TYPE_TXT -> "TXT"
            TYPE_AAAA -> "AAAA"
            TYPE_SRV -> "SRV"
            TYPE_HTTPS -> "HTTPS"
            TYPE_ANY -> "ANY"
            else -> "TYPE$type"
        }

        fun rcodeName(rcode: Int): String = when (rcode) {
            0 -> "NOERROR"
            1 -> "FORMERR"
            2 -> "SERVFAIL"
            3 -> "NXDOMAIN"
            4 -> "NOTIMP"
            5 -> "REFUSED"
            else -> "RCODE$rcode"
        }

        /** Parses [length] bytes of [bytes] from [offset]. Throws [DnsFormatException] on anything malformed. */
        fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): DnsMessage {
            if (offset < 0 || length < 0 || offset + length > bytes.size) throw DnsFormatException("range outside buffer")
            if (length < HEADER_LENGTH) throw DnsFormatException("shorter than a header ($length bytes)")
            val end = offset + length
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

            val id = u16(offset)
            val flags = u16(offset + 2)
            val qdCount = u16(offset + 4)
            val anCount = u16(offset + 6)
            val nsCount = u16(offset + 8)
            val arCount = u16(offset + 10)
            if (qdCount > MAX_QUESTIONS) throw DnsFormatException("$qdCount questions")

            var pos = offset + HEADER_LENGTH
            val questions = ArrayList<DnsQuestion>(qdCount)
            repeat(qdCount) {
                val (name, next) = readName(bytes, pos, offset, end)
                if (next + 4 > end) throw DnsFormatException("question truncated")
                questions += DnsQuestion(name, u16(next), u16(next + 2))
                pos = next + 4
            }
            return DnsMessage(
                id = id,
                isResponse = (flags and 0x8000) != 0,
                opcode = (flags shr 11) and 0xF,
                isTruncated = (flags and 0x0200) != 0,
                recursionDesired = (flags and 0x0100) != 0,
                rcode = flags and 0xF,
                questions = questions,
                answerCount = anCount,
                authorityCount = nsCount,
                additionalCount = arCount,
            )
        }

        fun parseOrNull(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): DnsMessage? =
            try {
                parse(bytes, offset, length)
            } catch (_: DnsFormatException) {
                null
            }

        /**
         * Reads a possibly compressed name starting at [pos]. Returns the lowercased name and the position
         * right after the name's bytes in the sequential stream (a pointer ends the sequential part).
         */
        internal fun readName(bytes: ByteArray, pos: Int, messageStart: Int, end: Int): Pair<String, Int> {
            val out = StringBuilder()
            var p = pos
            var next = -1
            var hops = 0
            var total = 0
            while (true) {
                if (p >= end) throw DnsFormatException("name runs past the end")
                val len = bytes[p].toInt() and 0xFF
                when {
                    len == 0 -> {
                        p++
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (p + 1 >= end) throw DnsFormatException("pointer truncated")
                        val target = messageStart + (((len and 0x3F) shl 8) or (bytes[p + 1].toInt() and 0xFF))
                        if (target >= p) throw DnsFormatException("forward compression pointer")
                        if (++hops > MAX_POINTER_HOPS) throw DnsFormatException("compression pointer loop")
                        if (next < 0) next = p + 2
                        p = target
                    }
                    len and 0xC0 != 0 -> throw DnsFormatException("unsupported label type ${len shr 6}")
                    else -> {
                        if (len > MAX_LABEL_LENGTH) throw DnsFormatException("label of $len bytes")
                        if (p + 1 + len > end) throw DnsFormatException("label truncated")
                        total += len + 1
                        if (total > MAX_NAME_LENGTH) throw DnsFormatException("name longer than $MAX_NAME_LENGTH")
                        if (out.isNotEmpty()) out.append('.')
                        for (i in p + 1 until p + 1 + len) {
                            val c = bytes[i].toInt() and 0xFF
                            out.append(if (c in 0x21..0x7E) c.toChar().lowercaseChar() else '?')
                        }
                        p += 1 + len
                    }
                }
            }
            return out.toString() to (if (next >= 0) next else p)
        }

        /** RCODE of a name that does not exist. */
        const val RCODE_NXDOMAIN = 3

        /**
         * A "no such domain" answer to [length] bytes of [query] from [offset]: same id and first question, the response
         * and recursion-available bits set, recursion-desired copied, no records. Null for anything that is not a
         * standard query with a question. Used for lookups a session blocks; nothing else is synthesised.
         */
        fun nxdomain(query: ByteArray, offset: Int = 0, length: Int = query.size - offset): ByteArray? {
            val message = parseOrNull(query, offset, length) ?: return null
            if (message.isResponse || message.opcode != 0 || message.questions.isEmpty()) return null
            val end = offset + length
            val questionEnd = try {
                readName(query, offset + HEADER_LENGTH, offset, end).second + 4
            } catch (_: DnsFormatException) {
                return null
            }
            if (questionEnd > end) return null
            val out = query.copyOfRange(offset, questionEnd)
            val recursionDesired = out[2].toInt() and 0x01
            out[2] = (0x80 or recursionDesired).toByte() // QR=1, opcode 0, AA 0, TC 0, RD as asked
            out[3] = (0x80 or RCODE_NXDOMAIN).toByte() // RA=1, Z 0, RCODE 3
            out[4] = 0; out[5] = 1 // one question
            for (i in 6 until HEADER_LENGTH) out[i] = 0 // no answer, authority or additional records
            return out
        }

        /** Builds a standard recursive query for [name]: used by tests and by nothing on the wire. */
        fun query(id: Int, name: String, type: Int = TYPE_A): ByteArray {
            val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
            val out = ArrayList<Byte>(HEADER_LENGTH + name.length + 6)
            fun u16(v: Int) {
                out += (v shr 8).toByte(); out += v.toByte()
            }
            u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
            for (label in labels) {
                require(label.length <= MAX_LABEL_LENGTH) { "label too long: $label" }
                out += label.length.toByte()
                label.forEach { out += it.code.toByte() }
            }
            out += 0
            u16(type); u16(1)
            return out.toByteArray()
        }
    }
}
