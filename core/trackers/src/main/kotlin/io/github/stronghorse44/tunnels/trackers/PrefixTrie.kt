package io.github.stronghorse44.tunnels.trackers

/**
 * Byte trie over ASCII prefixes. Matching walks raw bytes, so a descriptor never has to become a
 * String; the deepest (most specific) terminal node on the path wins.
 */
class PrefixTrie<T : Any>(entries: Collection<Pair<String, T>>) {
    private class Node<T : Any> {
        var keys = ByteArray(0)
        var children = arrayOfNulls<Node<T>>(0)
        var value: T? = null

        fun child(b: Byte): Node<T>? {
            for (i in keys.indices) if (keys[i] == b) return children[i]
            return null
        }

        fun childOrAdd(b: Byte): Node<T> = child(b) ?: Node<T>().also {
            keys = keys.copyOf(keys.size + 1).apply { this[size - 1] = b }
            children = children.copyOf(children.size + 1).apply { this[size - 1] = it }
        }
    }

    private val root = Node<T>()
    val size: Int = entries.size

    init {
        for ((prefix, value) in entries) {
            require(prefix.isNotEmpty()) { "Empty prefix" }
            var node = root
            for (ch in prefix) {
                require(ch.code in 1..127) { "Prefix must be ASCII: $prefix" }
                node = node.childOrAdd(ch.code.toByte())
            }
            node.value = value
        }
    }

    /** Value of the longest prefix that [bytes] in [start, end) starts with, or null. */
    fun longestMatch(bytes: ByteArray, start: Int, end: Int): T? {
        var node = root
        var best: T? = null
        var i = start
        while (i < end) {
            node = node.child(bytes[i]) ?: break
            node.value?.let { best = it }
            i++
        }
        return best
    }

    fun longestMatch(text: String): T? {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        return longestMatch(b, 0, b.size)
    }
}
