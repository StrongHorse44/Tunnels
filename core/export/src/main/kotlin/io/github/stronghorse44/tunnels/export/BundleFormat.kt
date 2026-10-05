package io.github.stronghorse44.tunnels.export

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

/**
 * What a parse accepts, counted as it reads: a hostile or damaged file stops at the first line past a cap instead of
 * after it has filled memory. [UNBOUNDED] is for tests.
 */
data class ParseLimits(val maxSnapshots: Int, val maxObservations: Int, val maxLineChars: Int) {
    companion object {
        val UNBOUNDED = ParseLimits(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)

        /** Far above a real phone (12 unpinned snapshots plus what the user pinned, ~100 000 observations at most). */
        val IMPORT = ParseLimits(maxSnapshots = 5_000, maxObservations = 500_000, maxLineChars = 1 shl 20)
    }
}

/** The bundle text could not be parsed: wrong header, malformed record, or a reference to a missing snapshot. */
class BundleFormatException(message: String) : Exception(message)

/**
 * TSNAP1: a line-based, tab-separated text format.
 *
 * ```
 * TSNAP1
 * snapshot<TAB>localId<TAB>takenAtMillis<TAB>0|1 pinned<TAB>tunnelId,tunnelId,...
 * obs<TAB>snapshotLocalId<TAB>tunnelId<TAB>subject<TAB>key<TAB>value
 * ```
 *
 * Every field is escaped so that tabs, newlines, carriage returns and backslashes inside values survive:
 * `\t`, `\n`, `\r`, `\\`. Empty lines are ignored and a line starting with `#` is a comment, before the header too:
 * the header is the first line that is neither. Tunnel ids are joined with `,`, so an id may not contain one.
 */
object BundleFormat {
    const val HEADER = "TSNAP1"
    private const val SNAPSHOT = "snapshot"
    private const val OBS = "obs"

    /** Throws IllegalArgumentException for a tunnel id containing `,`, which the record could not carry. */
    fun write(bundle: SnapshotBundle): String = buildString { writeTo(bundle, this) }

    /** [write] into any [Appendable], so a large bundle can go straight into a byte buffer without a String copy. */
    fun writeTo(bundle: SnapshotBundle, out: Appendable): Unit = with(out) {
        append(HEADER).append('\n')
        for (s in bundle.snapshots) {
            s.tunnelIds.forEach { require(',' !in it) { "Tunnel id \"$it\" contains a comma" } }
            append(SNAPSHOT).append('\t')
            append(s.localId.toString()).append('\t')
            append(s.takenAt.toString()).append('\t')
            append(if (s.pinned) '1' else '0').append('\t')
            append(escape(s.tunnelIds.joinToString(","))).append('\n')
        }
        for (o in bundle.observations) {
            append(OBS).append('\t')
            append(o.snapshotLocalId.toString()).append('\t')
            append(escape(o.tunnelId)).append('\t')
            append(escape(o.subject)).append('\t')
            append(escape(o.key)).append('\t')
            append(escape(o.value)).append('\n')
        }
    }

    /** Parses [text]; throws [BundleFormatException] on anything that is not a well-formed TSNAP1 document. */
    fun parse(text: String): SnapshotBundle = parse(StringReader(text))

    fun parse(reader: Reader): SnapshotBundle = parse(reader, ParseLimits.UNBOUNDED)

    /**
     * Parses line by line from [reader], which is closed afterwards, so a large bundle never needs a second copy
     * of itself in memory. Throws [BundleFormatException] on anything that is not a well-formed TSNAP1 document, or
     * that holds more than [limits] allow (a line, a snapshot count, an observation count), at the first record over.
     */
    fun parse(reader: Reader, limits: ParseLimits): SnapshotBundle {
        val snapshots = ArrayList<BundleSnapshot>()
        val observations = ArrayList<BundleObservation>()
        val ids = HashSet<Long>()
        var seenHeader = false
        var lineNo = 0
        (BufferedReader(LineCap(reader, limits.maxLineChars))).use { lines ->
            while (true) {
                val raw = lines.readLine() ?: break
                lineNo++
                val line = raw.removeSuffix("\r")
                if (line.isEmpty() || line.startsWith("#")) continue
                if (!seenHeader) {
                    if (line != HEADER) throw BundleFormatException("Not a Tunnels snapshot bundle (header \"${line.take(16)}\")")
                    seenHeader = true
                    continue
                }
                val fields = line.split('\t')
                when (fields[0]) {
                    SNAPSHOT -> {
                        if (fields.size != 5) throw BundleFormatException("Line $lineNo: snapshot record needs 5 fields, got ${fields.size}")
                        val id = fields[1].toLongOrNull() ?: throw BundleFormatException("Line $lineNo: bad snapshot id")
                        val takenAt = fields[2].toLongOrNull() ?: throw BundleFormatException("Line $lineNo: bad timestamp")
                        val pinned = when (fields[3]) {
                            "1" -> true
                            "0" -> false
                            else -> throw BundleFormatException("Line $lineNo: pinned must be 0 or 1")
                        }
                        if (!ids.add(id)) throw BundleFormatException("Line $lineNo: duplicate snapshot id $id")
                        if (ids.size > limits.maxSnapshots) throw BundleFormatException("More than ${limits.maxSnapshots} snapshots")
                        val tunnels = unescape(fields[4], lineNo).split(',').filter { it.isNotEmpty() }
                        snapshots += BundleSnapshot(id, takenAt, pinned, tunnels)
                    }
                    OBS -> {
                        if (fields.size != 6) throw BundleFormatException("Line $lineNo: obs record needs 6 fields, got ${fields.size}")
                        val id = fields[1].toLongOrNull() ?: throw BundleFormatException("Line $lineNo: bad snapshot id")
                        if (id !in ids) throw BundleFormatException("Line $lineNo: observation refers to unknown snapshot $id")
                        if (observations.size >= limits.maxObservations) throw BundleFormatException("More than ${limits.maxObservations} observations")
                        observations += BundleObservation(
                            id,
                            unescape(fields[2], lineNo),
                            unescape(fields[3], lineNo),
                            unescape(fields[4], lineNo),
                            unescape(fields[5], lineNo),
                        )
                    }
                    else -> throw BundleFormatException("Line $lineNo: unknown record \"${fields[0].take(16)}\"")
                }
            }
        }
        if (!seenHeader) throw BundleFormatException("Empty file")
        return SnapshotBundle(snapshots, observations)
    }

    internal fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    internal fun unescape(s: String, lineNo: Int = 0): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) throw BundleFormatException("Line $lineNo: dangling backslash")
            when (s[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> throw BundleFormatException("Line $lineNo: bad escape \\${s[i + 1]}")
            }
            i += 2
        }
        return out.toString()
    }
}

/**
 * Passes [source] through and throws as soon as a line (up to `\n` or `\r`) runs past [cap] characters, so a file
 * with no line breaks cannot make `readLine` build one string as large as the file.
 */
private class LineCap(private val source: Reader, private val cap: Int) : Reader() {
    private var run = 0

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = source.read(cbuf, off, len)
        for (i in off until off + maxOf(n, 0)) {
            if (cbuf[i] == '\n' || cbuf[i] == '\r') run = 0 else if (++run > cap) throw BundleFormatException("A line is longer than $cap characters")
        }
        return n
    }

    override fun close() = source.close()
}
