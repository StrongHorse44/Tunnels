package io.github.stronghorse44.tunnels.backups

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.ZoneOffset

/** Hand-built FWX v1 files (spec section 1): a real header, a made-up MAC and a body of filler. The header is all B06 reads. */
object Fixtures {
    const val DAY = 86_400_000L
    val NOW: Long = LocalDate.of(2026, 10, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)

    fun header(appId: String, schema: Long = 1, createdMs: Long, iterations: Long = 600_000, bodyBytes: Int = 4096): ByteArray {
        val kdf = "pbkdf2-hmac-sha256".toByteArray()
        val app = appId.toByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(7) { 9 }
        val length = 42 + app.size + kdf.size + salt.size
        val h = java.io.ByteArrayOutputStream()
        h.write(byteArrayOf(0x89.toByte(), 0x46, 0x57, 0x58, 0x0D, 0x0A, 0x1A, 0x0A))
        h.write(u(1, 2)); h.write(u(length.toLong(), 2)); h.write(app.size); h.write(app)
        h.write(u(schema, 4)); h.write(u(createdMs, 8)); h.write(kdf.size); h.write(kdf)
        h.write(u(iterations, 4)); h.write(salt.size); h.write(salt); h.write(nonce); h.write(u(1 shl 20, 4))
        check(h.size() == length)
        h.write(ByteArray(32) { 7 })
        h.write(ByteArray(bodyBytes) { 5 })
        return h.toByteArray()
    }

    private fun u(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }

    fun legacy(): ByteArray = "TSNAPE1".toByteArray() + ByteArray(300) { 1 }

    /** An in-memory folder. Counts the bytes read from each file. */
    class Memory : FolderSource {
        class Node(val entry: FolderEntry, val bytes: ByteArray?, val children: MutableList<Node> = mutableListOf())

        val root = mutableListOf<Node>()
        val bytesRead = HashMap<String, Int>()
        val opened = mutableListOf<String>()
        var failRoot = false
        val failOpen = HashMap<String, Throwable>()
        val failList = HashMap<String, Throwable>()

        fun file(name: String, bytes: ByteArray, modified: Long = NOW, dir: Node? = null): Memory {
            val node = Node(FolderEntry((dir?.entry?.id ?: "") + "/" + name, name, false, modified, bytes.size.toLong()), bytes)
            (dir?.children ?: root).add(node)
            return this
        }

        fun dir(name: String, parent: Node? = null): Node {
            val node = Node(FolderEntry((parent?.entry?.id ?: "") + "/" + name, name, true), null)
            (parent?.children ?: root).add(node)
            return node
        }

        private fun find(id: String, nodes: List<Node> = root): Node? =
            nodes.firstNotNullOfOrNull { if (it.entry.id == id) it else find(id, it.children) }

        override fun list(dir: FolderEntry?): List<FolderEntry> {
            if (failRoot && dir == null) throw IOException("gone")
            dir?.let { d -> failList[d.name]?.let { throw it } }
            return (if (dir == null) root else find(dir.id)!!.children).map { it.entry }
        }

        override fun open(file: FolderEntry): InputStream {
            opened += file.name
            failOpen[file.name]?.let { throw it }
            val bytes = find(file.id)!!.bytes!!
            return object : ByteArrayInputStream(bytes) {
                override fun read(): Int = super.read().also { if (it >= 0) bytesRead.merge(file.name, 1, Int::plus) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) bytesRead.merge(file.name, it, Int::plus) }
            }
        }
    }
}
