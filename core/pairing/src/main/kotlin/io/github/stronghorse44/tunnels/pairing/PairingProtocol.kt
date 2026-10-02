package io.github.stronghorse44.tunnels.pairing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Two phones, two QR codes, no network. The verifier shows a [Challenge]; the phone being checked scans it, has its
 * keystore attest a key bound to that challenge, and shows a [Response] (split into [Parts] when it is large); the
 * verifier scans that and judges it ([PairingVerifier]).
 *
 * The first time, the attested key is a persistent identity key the verifier pins. Later audits attest a fresh key
 * for the new challenge and sign it with the identity key, so the verifier knows it is the same phone's hardware,
 * as GrapheneOS's Auditor does.
 */
object PairingProtocol {
    const val CHALLENGE_BYTES = 32
    const val VERIFIER_ID_BYTES = 16
    const val CHALLENGE_PREFIX = "TNLS-PAIR1:"
    const val PART_PREFIX = "TNLS-PART1:"
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1
    private const val MAX_CERTS = 10
    private const val MAX_FIELD = 8192
    private const val MAX_RESPONSE = 64 * 1024

    /** Domain-separated data the identity key signs in an audit. */
    fun auditSignedData(challenge: ByteArray, leafDer: ByteArray): ByteArray =
        "TNLS-AUDIT1".toByteArray() + challenge + leafDer

    class Challenge(val challenge: ByteArray, val verifierId: ByteArray) {
        init {
            require(challenge.size == CHALLENGE_BYTES && verifierId.size == VERIFIER_ID_BYTES)
        }

        fun encode(): String = CHALLENGE_PREFIX + Base45.encode(challenge + verifierId)

        /** Short tag the response parts carry, so parts of another exchange are not mixed in. */
        val tag: String get() = tagOf(challenge)

        companion object {
            /** Null for anything that is not a Tunnels pairing challenge. */
            fun decode(text: String): Challenge? {
                if (!text.startsWith(CHALLENGE_PREFIX)) return null
                val bytes = runCatching { Base45.decode(text.removePrefix(CHALLENGE_PREFIX)) }.getOrNull() ?: return null
                if (bytes.size != CHALLENGE_BYTES + VERIFIER_ID_BYTES) return null
                return Challenge(bytes.copyOfRange(0, CHALLENGE_BYTES), bytes.copyOfRange(CHALLENGE_BYTES, bytes.size))
            }
        }
    }

    fun tagOf(challenge: ByteArray): String = challenge.take(3).joinToString("") { "%02X".format(it) }

    enum class Kind(val code: Int) { PAIR(0), AUDIT(1) }

    /**
     * What the checked phone sends. [chain] is the attested key's certificate chain, leaf first, root left out (the
     * verifier holds the roots). For an [Kind.AUDIT], [identityKey] is the pinned key's SubjectPublicKeyInfo and
     * [signature] its SHA256withECDSA signature over [auditSignedData].
     */
    class Response(
        val kind: Kind,
        val verifierId: ByteArray,
        val strongBox: Boolean,
        val chain: List<ByteArray>,
        val identityKey: ByteArray? = null,
        val signature: ByteArray? = null,
    ) {
        fun encode(): ByteArray {
            val raw = ByteArrayOutputStream()
            DataOutputStream(raw).use { out ->
                out.write(MAGIC)
                out.writeByte(VERSION)
                out.writeByte(kind.code)
                out.write(verifierId)
                out.writeByte(if (strongBox) 1 else 0)
                out.writeByte(chain.size)
                chain.forEach { field(out, it) }
                if (kind == Kind.AUDIT) {
                    field(out, identityKey ?: error("an audit carries the identity key"))
                    field(out, signature ?: error("an audit carries a signature"))
                }
            }
            return deflate(raw.toByteArray())
        }

        companion object {
            /** Null when [bytes] are not a well-formed response; never throws. */
            fun decode(bytes: ByteArray): Response? = runCatching {
                DataInputStream(ByteArrayInputStream(inflate(bytes))).use { input ->
                    val magic = ByteArray(4).also(input::readFully)
                    if (!magic.contentEquals(MAGIC) || input.readUnsignedByte() != VERSION) return null
                    val kindCode = input.readUnsignedByte()
                    val kind = Kind.entries.firstOrNull { it.code == kindCode } ?: return null
                    val verifierId = ByteArray(VERIFIER_ID_BYTES).also(input::readFully)
                    val strongBox = input.readUnsignedByte() == 1
                    val count = input.readUnsignedByte()
                    if (count == 0 || count > MAX_CERTS) return null
                    val chain = List(count) { field(input) }
                    val (identity, signature) = if (kind == Kind.AUDIT) field(input) to field(input) else null to null
                    if (input.read() != -1) return null
                    Response(kind, verifierId, strongBox, chain, identity, signature)
                }
            }.getOrNull()

            private fun field(out: DataOutputStream, bytes: ByteArray) {
                require(bytes.size <= MAX_FIELD)
                out.writeShort(bytes.size)
                out.write(bytes)
            }

            private fun field(input: DataInputStream): ByteArray {
                val n = input.readUnsignedShort()
                require(n in 1..MAX_FIELD)
                return ByteArray(n).also(input::readFully)
            }
        }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(bytes)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) error("truncated response")
                out.write(buf, 0, n)
                if (out.size() > MAX_RESPONSE) error("response too large")
            }
        } finally {
            i.end()
        }
        return out.toByteArray()
    }
}

/**
 * A response cut into QR-sized parts, each `TNLS-PART1:<i>/<n>:<tag>:<base45>`. The screen cycles through them; the
 * scanner collects them in any order with [Collector].
 */
object Parts {
    /** Bytes per part: a version-25-or-so code at medium error correction, easy to scan off another phone's screen. */
    const val PART_BYTES = 700
    private const val MAX_PARTS = 16

    fun split(payload: ByteArray, tag: String, partBytes: Int = PART_BYTES): List<String> {
        val chunks = payload.toList().chunked(partBytes).map { it.toByteArray() }
        require(chunks.size <= MAX_PARTS) { "response needs ${chunks.size} parts" }
        return chunks.mapIndexed { i, c -> "${PairingProtocol.PART_PREFIX}${i + 1}/${chunks.size}:$tag:${Base45.encode(c)}" }
    }

    /** Gathers parts for one [tag]; [add] returns the whole payload once every part has arrived. */
    class Collector(private val tag: String) {
        private val parts = HashMap<Int, ByteArray>()
        var total: Int = 0
            private set
        val received: Int get() = parts.size

        /** Null until complete. Text that is not a part of this exchange is ignored. */
        fun add(text: String): ByteArray? {
            if (!text.startsWith(PairingProtocol.PART_PREFIX)) return null
            val fields = text.removePrefix(PairingProtocol.PART_PREFIX).split(':', limit = 3)
            if (fields.size != 3 || fields[1] != tag) return null
            val (i, n) = fields[0].split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
            if (n !in 1..MAX_PARTS || i !in 1..n || (total != 0 && n != total)) return null
            val bytes = runCatching { Base45.decode(fields[2]) }.getOrNull() ?: return null
            total = n
            parts[i] = bytes
            if (parts.size < n) return null
            return (1..n).fold(ByteArray(0)) { acc, k -> acc + parts.getValue(k) }
        }
    }
}
