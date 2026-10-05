// FWX codec tests 1.2.0-r1, copied from fieldwork codec/kotlin/src/test/kotlin/fwx/VectorGen.kt at f78f08e (package line changed)
package io.github.stronghorse44.tunnels.export.fwx

import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates the committed vector files (codec/README.md, "Regenerating vectors"). The tests regenerate the same
 * bytes in memory and compare them with the committed copies, so a codec change that alters output fails CI.
 */
object VectorGen {
    private val APP_NAMES = mapOf("tunnels" to "Tunnels", "lumen" to "Lumen")

    fun vectorJson(v: T.VectorSpec): String {
        val p = FwxKdf.passphraseBytes(v.passphrase.toCharArray(), forWriter = true)
        val ikm = FwxKdf.pbkdf2(p, v.salt, v.iterations, 32)
        val prk = FwxKdf.hkdfExtract("FWX v1 extract".toByteArray(), ikm)
        val kHdr = FwxKdf.hkdfExpand(prk, "FWX v1 header key".toByteArray(), 32)
        val kEnc = FwxKdf.hkdfExpand(prk, "FWX v1 chunk key".toByteArray(), 32)
        val file = T.pack(v)
        val headerLength = Fwx.u16(file, 10)
        val stream = T.streamOf(v)
        val n = (stream.size + v.chunkSize - 1) / v.chunkSize
        val entries = v.entries.map { Triple(it.length, T.sha256(it.bytes()), it.name) }
        val codePoints = v.passphrase.codePoints().toArray().joinToString(", ") { "\"U+%04X\"".format(it) }

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": \"${v.id}\",\n")
        sb.append("  \"description\": ${T.jsonString(v.description)},\n")
        sb.append("  \"inputs\": {\n")
        sb.append("    \"passphrase_code_points\": [$codePoints],\n")
        sb.append("    \"passphrase_utf8_hex\": \"${T.hex(v.passphrase.toByteArray(Charsets.UTF_8))}\",\n")
        sb.append("    \"app_id\": \"${v.appId}\",\n")
        sb.append("    \"schema_version\": ${v.schema},\n")
        sb.append("    \"created_ms\": ${T.CREATED_MS},\n")
        sb.append("    \"kdf_iterations\": ${v.iterations},\n")
        sb.append("    \"salt_hex\": \"${T.hex(v.salt)}\",\n")
        sb.append("    \"nonce_prefix_hex\": \"${T.hex(T.NONCE)}\",\n")
        sb.append("    \"chunk_size\": ${v.chunkSize},\n")
        sb.append("    \"entries\": [")
        sb.append(v.entries.joinToString(",") {
            "\n      {\"name\": \"${it.name}\", \"length\": ${it.length}, \"data\": ${it.json()}, " +
                "\"sha256\": \"${T.sha256(it.bytes())}\"}"
        })
        sb.append(if (v.entries.isEmpty()) "]\n" else "\n    ]\n")
        sb.append("  },\n")
        sb.append("  \"outputs\": {\n")
        sb.append("    \"P_hex\": \"${T.hex(p)}\",\n")
        sb.append("    \"ikm_hex\": \"${T.hex(ikm)}\",\n")
        sb.append("    \"prk_hex\": \"${T.hex(prk)}\",\n")
        sb.append("    \"k_hdr_hex\": \"${T.hex(kHdr)}\",\n")
        sb.append("    \"k_enc_hex\": \"${T.hex(kEnc)}\",\n")
        sb.append("    \"header_length\": $headerLength,\n")
        sb.append("    \"header_hex\": \"${T.hex(file.copyOfRange(0, headerLength))}\",\n")
        sb.append("    \"header_mac_hex\": \"${T.hex(file.copyOfRange(headerLength, headerLength + 32))}\",\n")
        sb.append("    \"plaintext_stream_length\": ${stream.size},\n")
        sb.append("    \"plaintext_stream_sha256\": \"${T.sha256(stream)}\",\n")
        sb.append("    \"chunk_count\": $n,\n")
        sb.append("    \"chunks\": [")
        var offset = headerLength + 32
        sb.append((0 until n).joinToString(",") { i ->
            val plainLength = minOf(v.chunkSize, stream.size - i * v.chunkSize)
            val nonce = T.NONCE + ByteArray(4).also { Fwx.putU32(it, 0, i.toLong()) } + byteArrayOf(if (i == n - 1) 1 else 0)
            val sealed = file.copyOfRange(offset, offset + plainLength + 16)
            val line = "\n      {\"index\": $i, \"offset\": $offset, \"plaintext_length\": $plainLength, " +
                "\"nonce_hex\": \"${T.hex(nonce)}\", \"sealed_sha256\": \"${T.sha256(sealed)}\"}"
            offset += plainLength + 16
            line
        })
        sb.append("\n    ],\n")
        sb.append("    \"file_length\": ${file.size},\n")
        sb.append("    \"file_sha256\": \"${T.sha256(file)}\",\n")
        sb.append("    \"file\": ${if (v.commitFile) "\"${v.id}.fwx\"" else "null"},\n")
        val list = T.listOutput(
            v.appId, APP_NAMES.getValue(v.appId), v.schema, v.iterations, v.salt.size, v.chunkSize, entries,
            stream.size.toLong(),
        )
        sb.append("    \"list_output\": ${T.jsonString(list)}\n")
        sb.append("  }\n")
        sb.append("}\n")
        for (b in listOf(p, ikm, prk, kHdr, kEnc)) b.fill(0)
        return sb.toString()
    }

    const val LEGACY_PASSPHRASE = "correct horse battery staple"
    val LEGACY_PAYLOAD = "TSNAP1 test fixture for fwx.py; not a real Tunnels bundle\n".toByteArray()

    /**
     * A legacy TSNAPE1 file made exactly as Tunnels' EncryptedFile.seal does (desktop JDK provider), with a fixed
     * salt and IV. Test-only: the spec keeps the legacy sealer for producing fixtures, and FWX never uses it.
     */
    fun legacyFixture(): ByteArray {
        val magic = "TSNAPE1".toByteArray(Charsets.US_ASCII)
        val salt = ByteArray(16) { (0x10 + it).toByte() }
        val iv = ByteArray(12) { (0x20 + it).toByte() }
        val spec = PBEKeySpec(LEGACY_PASSPHRASE.toCharArray(), salt, 310_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(magic + salt)
        return magic + salt + iv + cipher.doFinal(LEGACY_PAYLOAD)
    }

    fun legacyJson(): String =
        "{\n  \"file\": \"legacy-TSNAPE1.bin\",\n  \"passphrase\": \"$LEGACY_PASSPHRASE\",\n" +
            "  \"entry_name\": \"snapshots.tsnap1\",\n  \"payload_length\": ${LEGACY_PAYLOAD.size},\n" +
            "  \"payload_sha256\": \"${T.sha256(LEGACY_PAYLOAD)}\"\n}\n"

    /** Every generated file, by name. */
    fun all(): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (v in T.VECTORS) {
            files["${v.id}.json"] = vectorJson(v).toByteArray()
            if (v.commitFile) files["${v.id}.fwx"] = T.pack(v)
        }
        for ((id, bytes) in T.crafted()) {
            if (id in T.UNCOMMITTED) {
                files["$id.json"] = ("{\n  \"id\": \"$id\",\n  \"file\": null,\n  \"file_length\": ${bytes.size},\n" +
                    "  \"file_sha256\": \"${T.sha256(bytes)}\"\n}\n").toByteArray()
            } else {
                files["$id.fwx"] = bytes
            }
        }
        files["legacy-TSNAPE1.bin"] = legacyFixture()
        files["legacy.json"] = legacyJson().toByteArray()
        return files
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.single())
        dir.mkdirs()
        for ((name, bytes) in all()) File(dir, name).writeBytes(bytes)
    }
}
