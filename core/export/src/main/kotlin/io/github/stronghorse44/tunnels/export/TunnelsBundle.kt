package io.github.stronghorse44.tunnels.export

import io.github.stronghorse44.tunnels.export.fwx.Fwx
import io.github.stronghorse44.tunnels.export.fwx.FwxError
import io.github.stronghorse44.tunnels.export.fwx.FwxException
import io.github.stronghorse44.tunnels.export.fwx.FwxReader
import io.github.stronghorse44.tunnels.export.fwx.FwxWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One entry of a bundle, ready to write: [length] bytes of [bytes] (the buffer may be longer). */
class EncodedEntry(val name: String, val bytes: ByteArray, val length: Int)

/** The next entry of a bundle being parsed; [stream] yields exactly [length] bytes and is read to its end by the parser. */
class SourceEntry(val name: String, val length: Long, val stream: InputStream)

/** Where [TunnelsBundle.parse] gets its entries: the FWX reader on import, memory when an export checks itself. */
fun interface EntrySource {
    /** The next entry, or null after the last one (for the FWX reader only once the whole file has verified). */
    fun next(): SourceEntry?
}

/** The header of a picked file, before any passphrase: what it is and, for FWX, what it says about itself (unverified). */
sealed interface Inspected {
    /** An FWX bundle of this app and a schema this build reads. [createdMs] and [schema] are unverified until the passphrase. */
    data class Fwx(val schema: Long, val createdMs: Long, val fileLength: Long?) : Inspected

    /** An export from an earlier Tunnels (TSNAPE1): snapshots only, no date inside. */
    data object Legacy : Inspected
}

/**
 * The Tunnels bundle, schema 1, in the FWX v1 container (docs/EXPORT.md). Entries, in this order:
 *
 * | name | holds |
 * |---|---|
 * | `manifest.json` | app, schema, app version and the count of everything below |
 * | `snapshots.tsnap1` | [BundleFormat] text: snapshots and observations |
 * | `settings.tsv` | the carried settings ([BundleSettings.KEYS]) |
 * | `pairing-pins.txt` | paired second phones (pinned identity keys) |
 * | `networks.txt` | SHA-256 of each confirmed Wi-Fi network, one per line |
 *
 * Import reads with [read]; an export checks what it is about to write with [check] (the same [parse]) and reads the
 * finished file back with [read], so an export cannot succeed on data an import would refuse.
 */
object TunnelsBundle {
    const val APP_ID = "tunnels"
    const val SCHEMA = 1L
    val SCHEMA_RANGE: LongRange = 1L..1L

    /** The reader's cap on the whole plaintext stream. */
    const val MAX_STREAM_BYTES = 64L shl 20

    /** 2020-01-01T00:00:00Z: no snapshot of this app is older. */
    const val MIN_TAKEN_AT = 1_577_836_800_000L

    /** How far ahead of the importing phone's clock a snapshot may be (one day, for clock skew). */
    const val MAX_FUTURE_MS = 24L * 60 * 60 * 1000

    /** Cap on each of the small entries. */
    const val MAX_SMALL_ENTRY_BYTES = 1 shl 20

    const val MANIFEST = "manifest.json"
    const val SNAPSHOTS = "snapshots.tsnap1"
    const val SETTINGS = "settings.tsv"
    const val PINS = "pairing-pins.txt"
    const val NETWORKS = "networks.txt"

    /** The entry names in the order they are written. */
    val NAMES: List<String> = listOf(MANIFEST, SNAPSHOTS, SETTINGS, PINS, NETWORKS)

    // Encoding

    /** [data] as the bundle's entries. TOO_LARGE when the snapshots alone would pass the stream cap. */
    fun encode(data: TunnelsData, appVersion: String): List<EncodedEntry> {
        val snapshots = ExposedBytes(MAX_STREAM_BYTES - 4 * MAX_SMALL_ENTRY_BYTES).also { sink ->
            OutputStreamWriter(sink, Charsets.UTF_8).use { BundleFormat.writeTo(data.snapshots, it) }
        }
        return listOf(
            text(MANIFEST, Manifest.write(data.counts, appVersion)),
            EncodedEntry(SNAPSHOTS, snapshots.buffer(), snapshots.length()),
            text(SETTINGS, BundleSettings.encodeSettings(data.settings)),
            text(PINS, data.pairingPins),
            text(NETWORKS, BundleSettings.encodeNetworks(data.networks)),
        )
    }

    /** Best effort: the entries hold the snapshot summaries in the clear. */
    fun wipe(entries: List<EncodedEntry>) = entries.forEach { it.bytes.fill(0) }

    private fun text(name: String, value: String): EncodedEntry {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SMALL_ENTRY_BYTES) throw FwxException(FwxError.TOO_LARGE, "an entry is over $MAX_SMALL_ENTRY_BYTES bytes")
        return EncodedEntry(name, bytes, bytes.size)
    }

    /** Writes [entries] to [out] as an FWX bundle. The caller owns [out] and [passphrase]. */
    fun write(out: OutputStream, passphrase: CharArray, entries: List<EncodedEntry>, createdMs: Long) {
        val writer = FwxWriter(out, APP_ID, SCHEMA, createdMs, passphrase)
        for (e in entries) writer.entry(e.name, e.length.toLong(), ByteArrayInputStream(e.bytes, 0, e.length))
        writer.finish()
    }

    /** Parses [entries] exactly as an import would. */
    fun check(entries: List<EncodedEntry>, now: Long = System.currentTimeMillis()): TunnelsData {
        val it = entries.iterator()
        return parse(now = now, source = {
            if (!it.hasNext()) null else it.next().let { e -> SourceEntry(e.name, e.length.toLong(), ByteArrayInputStream(e.bytes, 0, e.length)) }
        })
    }

    // Reading

    /**
     * Steps 1 to 6 of the container spec's section 5.1, no passphrase: is this an FWX bundle of this app, at a schema
     * this build reads, or a legacy export. Throws [FwxException] (NOT_AN_EXPORT, UNSUPPORTED_VERSION, MALFORMED_HEADER,
     * UNSUPPORTED_KDF, KDF_PARAMS, DAMAGED, WRONG_APP, SCHEMA_TOO_NEW, SCHEMA_TOO_OLD). Reads at most 202 bytes.
     */
    fun inspect(input: InputStream, fileLength: Long? = null): Inspected {
        val h = try {
            Fwx.readHeader(input, fileLength)
        } catch (e: FwxException) {
            if (e.code == FwxError.LEGACY) return Inspected.Legacy
            throw e
        }
        if (h.appId != APP_ID) throw FwxException(FwxError.WRONG_APP, "bundle is for another app", otherAppId = h.appId)
        if (h.schemaVersion > SCHEMA_RANGE.last) throw FwxException(FwxError.SCHEMA_TOO_NEW, "schema too new")
        if (h.schemaVersion < SCHEMA_RANGE.first) throw FwxException(FwxError.SCHEMA_TOO_OLD, "schema too old")
        return Inspected.Fwx(h.schemaVersion, h.createdMs, fileLength)
    }

    /**
     * Opens, verifies and parses a whole FWX bundle. Returns only after the container's verified end, so the data is
     * complete and authentic; every problem is an [FwxException]. Nothing is written anywhere.
     */
    fun read(input: InputStream, passphrase: CharArray): TunnelsData =
        FwxReader(input, passphrase, APP_ID, SCHEMA_RANGE, MAX_STREAM_BYTES).use { reader ->
            parse {
                reader.next()?.let { SourceEntry(it.name, it.length, it.stream) }
            }
        }

    /**
     * The bundle's entries, parsed with every bound: manifest first, each entry once, no unknown names, counts that
     * match the manifest. File problems are MALFORMED_PAYLOAD; whatever [source] throws passes through.
     */
    fun parse(now: Long = System.currentTimeMillis(), source: EntrySource): TunnelsData {
        var manifest: Manifest? = null
        var snapshots: SnapshotBundle? = null
        var settings: Map<String, String>? = null
        var pins: String? = null
        var networks: Set<String>? = null
        var first = true
        while (true) {
            val entry = source.next() ?: break
            if (first && entry.name != MANIFEST) payload("manifest.json must be the first entry")
            first = false
            try {
                when (entry.name) {
                    MANIFEST -> if (manifest != null) payload("duplicate entry") else manifest = Manifest.parse(small(entry))
                    SNAPSHOTS -> if (snapshots != null) payload("duplicate entry") else snapshots = parseSnapshots(entry)
                    SETTINGS -> if (settings != null) payload("duplicate entry") else settings = BundleSettings.parseSettings(small(entry))
                    PINS -> if (pins != null) payload("duplicate entry") else pins = BundleSettings.parsePins(small(entry))
                    NETWORKS -> if (networks != null) payload("duplicate entry") else networks = BundleSettings.parseNetworks(small(entry))
                    else -> payload("unknown entry")
                }
                drain(entry.stream)
            } catch (e: BundleFormatException) {
                payload(e.message ?: "invalid entry")
            } catch (e: CharacterCodingException) {
                payload("an entry is not valid UTF-8")
            }
        }
        if (manifest == null || snapshots == null || settings == null || pins == null || networks == null) {
            payload("an entry is missing")
        }
        checkDates(snapshots, now)
        val data = TunnelsData(snapshots, settings, pins, networks)
        if (data.counts != manifest.counts) payload("the manifest's counts do not match the entries")
        return data
    }

    /** Snapshot dates a real phone can have: after [MIN_TAKEN_AT] and at most a day ahead of the clock (skew). */
    fun checkDates(bundle: SnapshotBundle, now: Long) {
        val latest = now + MAX_FUTURE_MS
        for (s in bundle.snapshots) {
            if (s.takenAt < MIN_TAKEN_AT || s.takenAt > latest) payload("a snapshot's date is out of range")
        }
    }

    private fun parseSnapshots(entry: SourceEntry): SnapshotBundle =
        BundleFormat.parse(InputStreamReader(entry.stream, strictUtf8()), ParseLimits.IMPORT)

    private fun strictUtf8() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /** A small entry as text; over the cap is MALFORMED_PAYLOAD, invalid UTF-8 a [CharacterCodingException]. */
    private fun small(entry: SourceEntry): String {
        if (entry.length > MAX_SMALL_ENTRY_BYTES) payload("an entry is larger than $MAX_SMALL_ENTRY_BYTES bytes")
        val bytes = ByteArray(entry.length.toInt())
        try {
            var at = 0
            while (at < bytes.size) {
                val n = entry.stream.read(bytes, at, bytes.size - at)
                if (n < 0) payload("an entry ends early")
                at += n
            }
            return strictUtf8().decode(ByteBuffer.wrap(bytes)).toString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun drain(stream: InputStream) {
        val scratch = ByteArray(8192)
        while (stream.read(scratch) >= 0) Unit
    }

    private fun payload(detail: String): Nothing = throw FwxException(FwxError.MALFORMED_PAYLOAD, detail)

    /** The manifest's content: counts only. App and schema are checked against the header by the container. */
    internal class Manifest(val counts: BundleCounts) {
        companion object {
            fun write(counts: BundleCounts, appVersion: String): String {
                val version = appVersion.map { if (it.isLetterOrDigit() && it.code < 0x80 || it in "._+-") it else '_' }.joinToString("").take(64)
                return "{\"app\":\"$APP_ID\",\"schema\":$SCHEMA,\"app_version\":\"$version\"," +
                    "\"snapshots\":${counts.snapshots},\"observations\":${counts.observations},\"settings\":${counts.settings}," +
                    "\"pairing_pins\":${counts.pairingPins},\"networks\":${counts.networks}}"
            }

            /** Strict: a flat object with exactly the keys above, strings without escapes and plain non-negative integers. */
            fun parse(text: String): Manifest {
                if (text.length > 1024) throw BundleFormatException("manifest: too long")
                val m = Regex(
                    "\\{\"app\":\"([^\"\\\\\\u0000-\\u001f]*)\",\"schema\":(\\d{1,9}),\"app_version\":\"([^\"\\\\\\u0000-\\u001f]{0,64})\"," +
                        "\"snapshots\":(\\d{1,9}),\"observations\":(\\d{1,9}),\"settings\":(\\d{1,9})," +
                        "\"pairing_pins\":(\\d{1,9}),\"networks\":(\\d{1,9})\\}",
                ).matchEntire(text) ?: throw BundleFormatException("manifest: not the expected shape")
                val g = m.groupValues
                if (g[1] != APP_ID) throw BundleFormatException("manifest: wrong app")
                if (g[2].toLong() != SCHEMA) throw BundleFormatException("manifest: wrong schema")
                return Manifest(BundleCounts(g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt(), g[8].toInt()))
            }
        }
    }

    /** A byte sink that keeps its buffer for reuse without a copy and stops at [cap] bytes (TOO_LARGE). */
    private class ExposedBytes(private val cap: Long) : ByteArrayOutputStream() {
        fun buffer(): ByteArray = buf
        fun length(): Int = count

        override fun write(b: Int) {
            if (count + 1L > cap) tooLarge()
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len.toLong() > cap) tooLarge()
            super.write(b, off, len)
        }

        private fun tooLarge(): Nothing = throw FwxException(FwxError.TOO_LARGE, "the snapshots are larger than a bundle can hold")
    }
}
