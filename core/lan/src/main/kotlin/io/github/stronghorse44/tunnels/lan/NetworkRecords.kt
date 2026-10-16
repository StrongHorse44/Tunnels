package io.github.stronghorse44.tunnels.lan

import kotlin.coroutines.cancellation.CancellationException

/** The v2 row as read: the key id it was written under (null for an empty row) and its records by network token. */
data class NetworkRows(val keyId: String?, val records: Map<String, NetworkRecord>) {
    companion object {
        val EMPTY = NetworkRows(null, emptyMap())
    }
}

/** The v2 row cannot be used: its key line is missing or malformed. Fail closed; "Forget all" clears it. */
class NetworkRowsMalformed : Exception("The confirmed-network row is malformed.")

/**
 * The text form of `homenet.networks.v2` in the encrypted settings table (spec 3.2), strict:
 * ```
 * k=<8 hex key id>
 * <net 32hex> <r|a> <u1,u2..|-> <t|->
 * ```
 * One line per network, sorted by network token; address-only lines carry `- -`. A malformed network line is skipped;
 * a missing or malformed key line makes the whole row unusable ([NetworkRowsMalformed]), never silently wiped. An
 * empty value is a row with no records (Forget all keeps the row, empty). Only keyed tokens: never a UUID, a
 * certificate, an address, a name or a time.
 */
object NetworkRecordCodec {
    const val KEY = "homenet.networks.v2"

    /** Most networks the row holds (spec 3.3). */
    const val MAX_NETWORKS = 32

    private val KEY_LINE = Regex("k=([0-9a-f]{8})")
    private val LINE = Regex("[0-9a-f]{32} (r (-|[0-9a-f]{32}(,[0-9a-f]{32}){0,3}) (-|[0-9a-f]{32})|a - -)")

    fun isKeyId(text: String): Boolean = Regex("[0-9a-f]{8}").matches(text)

    /** The row's records; throws [NetworkRowsMalformed] when it has content but no well-formed key line first. */
    fun decode(raw: String?): NetworkRows {
        if (raw.isNullOrEmpty()) return NetworkRows.EMPTY
        val lines = raw.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val keyId = lines.firstOrNull()?.let { KEY_LINE.matchEntire(it) }?.groupValues?.get(1) ?: throw NetworkRowsMalformed()
        val out = LinkedHashMap<String, NetworkRecord>()
        for (line in lines.drop(1).sorted()) {
            val record = parseLine(line) ?: continue
            if (record.net in out || out.size >= MAX_NETWORKS) continue
            out[record.net] = record
        }
        return NetworkRows(keyId, out)
    }

    /** One network line, or null when it is not exactly what [encodeLine] writes. */
    fun parseLine(line: String): NetworkRecord? {
        if (!LINE.matches(line)) return null
        val parts = line.split(' ')
        val mode = RecordMode.of(parts[1].single()) ?: return null
        val upnp = if (parts[2] == "-") emptyList() else parts[2].split(',')
        val tls = parts[3].takeIf { it != "-" }
        return NetworkRecord(parts[0], mode, upnp, tls).takeIf { it.valid }
    }

    fun encodeLine(record: NetworkRecord): String =
        "${record.net} ${record.mode.letter} ${record.upnp.joinToString(",").ifEmpty { "-" }} ${record.tls ?: "-"}"

    /** The row as stored: empty when there are no records, else the key line and the valid records sorted, each line ended by a newline. */
    fun encode(keyId: String, records: Collection<NetworkRecord>): String {
        require(isKeyId(keyId)) { "not a key id" }
        val valid = records.filter { it.valid }.distinctBy { it.net }.sortedBy { it.net }
        require(valid.size <= MAX_NETWORKS) { "more than $MAX_NETWORKS networks" }
        if (valid.isEmpty()) return ""
        return "k=$keyId\n" + valid.joinToString("") { encodeLine(it) + "\n" }
    }
}

/** What the gate knows about one network before probing: the v2 record, whether a v1 line matches, whether the key is the row's. */
data class GateRecord(val net: String, val record: NetworkRecord?, val legacy: Boolean, val keyIdOk: Boolean)

/** How a confirmation ended. */
enum class SaveResult { SAVED, FULL, FAILED }

/**
 * The confirmed networks, v2 (spec 3): one row of keyed records next to the old v1 row ([ConfirmedNetworkBook]), which is
 * now read only for the migration verdict ("confirm once more", L8-Q3) and changed only to remove a line. Plain Kotlin;
 * the Android adapters are the settings-table row and the Keystore HMAC.
 *
 * - **Fail closed.** A store, key or row failure reads as [ConfirmedNetworksUnavailable]: no scan opens. The key is
 *   never recreated over a failure, and a malformed row is never wiped except by [forgetAll].
 * - **Key changes.** A row written under another key holds records that can never match: the network reads as "key
 *   changed" until confirmed again, and that confirmation rewrites the row under the current key with only that record.
 * - Every change of the v2 row is one atomic [ConfirmedNetworkTable.update] under a process-wide lock. A confirmation
 *   removes the v1 line first and then writes the record, so a failure in between leaves nothing confirmed that was not
 *   before; a forget removes the record first. Every call blocks: call off the main thread.
 */
class NetworkRecordBook(
    private val table: ConfirmedNetworkTable,
    private val legacy: ConfirmedNetworkBook,
    mac: (String) -> String,
) {
    val tokens = RouterTokens(mac)

    /** The gate's view of [fingerprint]; throws [ConfirmedNetworksUnavailable] when the store, the key or the row cannot be used. */
    fun lookup(fingerprint: NetworkFingerprint): GateRecord = synchronized(LOCK) {
        guarded {
            val net = tokens.net(fingerprint)
            val rows = NetworkRecordCodec.decode(table.read())
            val keyIdOk = rows.records.isEmpty() || rows.keyId == tokens.keyId()
            val legacyHit = legacy.lookup(fingerprint.hash) ?: throw ConfirmedNetworksUnavailable()
            GateRecord(net, if (keyIdOk) rows.records[net] else null, legacyHit, keyIdOk)
        }
    }

    /**
     * Saves [fingerprint] as confirmed in [mode] with what the router showed ([seen]; ignored for address only),
     * replacing any record it had ("It's my new router"); its v1 line is removed first. A full row (another network's
     * record would be the 33rd) saves nothing and keeps the v1 line.
     */
    fun confirm(fingerprint: NetworkFingerprint, mode: RecordMode, seen: SeenTokens): SaveResult = synchronized(LOCK) {
        try {
            val keyId = tokens.keyId()
            val net = tokens.net(fingerprint)
            val record = when (mode) {
                RecordMode.ROUTER -> NetworkRecord(net, mode, seen.upnp.distinct().take(NetworkRecord.MAX_UPNP), seen.tls)
                RecordMode.ADDRESS -> NetworkRecord(net, mode)
            }
            if (!record.valid) return@synchronized SaveResult.FAILED
            // Records under another key can never match again: the row starts over under this key.
            fun kept(stored: String?): Map<String, NetworkRecord> = NetworkRecordCodec.decode(stored).let { if (it.keyId == keyId) it.records else emptyMap() }
            fun full(kept: Map<String, NetworkRecord>) = net !in kept && kept.size >= NetworkRecordCodec.MAX_NETWORKS
            if (full(kept(table.read()))) return@synchronized SaveResult.FULL
            // The old line goes first: if the save below then fails, nothing is confirmed that was not before (a lost v1
            // line only asked for the confirmation the user is making).
            if (!legacy.forget(fingerprint.hash)) return@synchronized SaveResult.FAILED
            var wasFull = false
            table.update { stored ->
                val now = kept(stored)
                if (full(now)) {
                    wasFull = true
                    stored.orEmpty()
                } else {
                    NetworkRecordCodec.encode(keyId, now.values.filter { it.net != net } + record)
                }
            }
            if (wasFull) return@synchronized SaveResult.FULL
            SaveResult.SAVED
        } catch (t: Throwable) {
            rethrowIfControl(t)
            SaveResult.FAILED
        }
    }

    /**
     * After a match: writes [RouterIdentity.refresh] of [record], and only when it changed (an unchanged record is not
     * written at all). False when it could not be written; the admission stands, since the match was made against the
     * stored record.
     */
    fun refresh(record: NetworkRecord, seen: SeenTokens): Boolean = synchronized(LOCK) {
        val fresh = RouterIdentity.refresh(record, seen)
        if (fresh == record) return@synchronized true
        try {
            val keyId = tokens.keyId()
            table.update { stored ->
                val rows = NetworkRecordCodec.decode(stored)
                // Written only over the record the match was made against: anything else changed in between wins.
                if (rows.keyId != keyId || rows.records[record.net] != record) return@update stored.orEmpty()
                NetworkRecordCodec.encode(keyId, rows.records.values.filter { it.net != record.net } + fresh)
            }
            true
        } catch (t: Throwable) {
            rethrowIfControl(t)
            false
        }
    }

    /**
     * Forgets [fingerprint]: its v2 record and its v1 line. A row written under another key is emptied (none of its
     * records can match again). False when either row could not be changed.
     */
    fun forget(fingerprint: NetworkFingerprint): Boolean = synchronized(LOCK) {
        try {
            val net = tokens.net(fingerprint)
            val keyId = tokens.keyId()
            table.update { stored ->
                val rows = NetworkRecordCodec.decode(stored)
                // A row under another key can never match again (the "key changed" state): forgetting empties it, as a
                // confirmation would start it over, so the network then reads as not confirmed (re-check R1).
                if (rows.records.isNotEmpty() && rows.keyId != keyId) return@update ""
                if (net !in rows.records) stored.orEmpty() else NetworkRecordCodec.encode(rows.keyId!!, rows.records.values.filter { it.net != net })
            }
            legacy.forget(fingerprint.hash)
        } catch (t: Throwable) {
            rethrowIfControl(t)
            false
        }
    }

    /** Empties both rows (each kept as an empty value). Also clears a malformed v2 row. */
    fun forgetAll(): Boolean = synchronized(LOCK) {
        try {
            table.update { "" }
            legacy.forgetAll()
        } catch (t: Throwable) {
            rethrowIfControl(t)
            false
        }
    }

    /** How many networks are confirmed in v2 (v1 lines wait for "confirm once more" and are not counted); null when unreadable. */
    fun count(): Int? = synchronized(LOCK) {
        try {
            NetworkRecordCodec.decode(table.read()).records.size
        } catch (t: Throwable) {
            rethrowIfControl(t)
            null
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: ConfirmedNetworksUnavailable) {
        throw e
    } catch (t: Throwable) {
        rethrowIfControl(t)
        throw ConfirmedNetworksUnavailable(t)
    }

    private fun rethrowIfControl(t: Throwable) {
        if (t is CancellationException || t is InterruptedException) throw t
    }

    private companion object {
        val LOCK = Any()
    }
}
