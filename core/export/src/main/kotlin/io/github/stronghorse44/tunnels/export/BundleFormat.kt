package io.github.stronghorse44.tunnels.export

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
 * `\t`, `\n`, `\r`, `\\`. Empty lines are ignored; a line starting with `#` is a comment.
 */
object BundleFormat {
    const val HEADER = "TSNAP1"
    private const val SNAPSHOT = "snapshot"
    private const val OBS = "obs"

    fun write(bundle: SnapshotBundle): String = buildString {
        append(HEADER).append('\n')
        for (s in bundle.snapshots) {
            append(SNAPSHOT).append('\t')
            append(s.localId).append('\t')
            append(s.takenAt).append('\t')
            append(if (s.pinned) '1' else '0').append('\t')
            append(escape(s.tunnelIds.joinToString(","))).append('\n')
        }
        for (o in bundle.observations) {
            append(OBS).append('\t')
            append(o.snapshotLocalId).append('\t')
            append(escape(o.tunnelId)).append('\t')
            append(escape(o.subject)).append('\t')
            append(escape(o.key)).append('\t')
            append(escape(o.value)).append('\n')
        }
    }

    /** Parses [text]; throws [BundleFormatException] on anything that is not a well-formed TSNAP1 document. */
    fun parse(text: String): SnapshotBundle {
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        val header = lines.firstOrNull { it.isNotEmpty() } ?: throw BundleFormatException("Empty file")
        if (header != HEADER) throw BundleFormatException("Not a Tunnels snapshot bundle (header \"${header.take(16)}\")")
        val snapshots = ArrayList<BundleSnapshot>()
        val observations = ArrayList<BundleObservation>()
        val ids = HashSet<Long>()
        var seenHeader = false
        lines.forEachIndexed { index, line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (!seenHeader) {
                seenHeader = true
                return@forEachIndexed
            }
            val lineNo = index + 1
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
                    val tunnels = unescape(fields[4], lineNo).split(',').filter { it.isNotEmpty() }
                    snapshots += BundleSnapshot(id, takenAt, pinned, tunnels)
                }
                OBS -> {
                    if (fields.size != 6) throw BundleFormatException("Line $lineNo: obs record needs 6 fields, got ${fields.size}")
                    val id = fields[1].toLongOrNull() ?: throw BundleFormatException("Line $lineNo: bad snapshot id")
                    if (id !in ids) throw BundleFormatException("Line $lineNo: observation refers to unknown snapshot $id")
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
