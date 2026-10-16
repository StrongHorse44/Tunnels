package io.github.stronghorse44.tunnels.lan

/**
 * The router's second identity factor (audit L8, RJ's OQ192). A confirmed network is recognised by its addresses
 * ([NetworkFingerprint]) **and** by its router: the UPnP root-device UUID(s) the gateway gives in its SSDP reply, and the
 * public key of its HTTPS admin page (SHA-256 of the leaf certificate's SubjectPublicKeyInfo). A café that happens to use
 * home's addresses has another router, so it no longer reads as confirmed. An attacker on a LAN can copy both factors
 * (they are public there): what that gains is only Tunnels scanning their own network, the limit the docs state.
 *
 * Plain Kotlin: the raw factors exist only in memory in tunnels/homenet's probe, which turns them into keyed tokens
 * ([RouterTokens]) and drops them; everything here works on tokens.
 */
enum class Purpose { SCAN, LOOK_CLOSER, CLOSE_IT }

/** How a stored network is recognised: by its router's identity, or (the user's choice, L8-Q2) by its addresses only. */
enum class RecordMode(val letter: Char) {
    ROUTER('r'),
    ADDRESS('a'),
    ;

    companion object {
        fun of(letter: Char): RecordMode? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * One confirmed network in `homenet.networks.v2`: its keyed network token, the mode, at most [MAX_UPNP] keyed UPnP tokens
 * (most recently seen first) and at most one keyed admin-page key token. Mode [RecordMode.ADDRESS] carries no factor;
 * mode [RecordMode.ROUTER] carries at least one.
 */
data class NetworkRecord(
    val net: String,
    val mode: RecordMode,
    val upnp: List<String> = emptyList(),
    val tls: String? = null,
) {
    val valid: Boolean
        get() = RouterTokens.isToken(net) && upnp.size <= MAX_UPNP && upnp.all(RouterTokens::isToken) && upnp.distinct().size == upnp.size &&
            (tls == null || RouterTokens.isToken(tls)) &&
            when (mode) {
                RecordMode.ROUTER -> upnp.isNotEmpty() || tls != null
                RecordMode.ADDRESS -> upnp.isEmpty() && tls == null
            }

    companion object {
        const val MAX_UPNP = 4
    }
}

/**
 * What the identity probe saw, as tokens. [probed] is false when nothing was sent (no IPv4 gateway inside the network);
 * [lost] is true when the Wi-Fi went away during the probe (a socket could not be bound to it).
 */
data class SeenTokens(
    val upnp: List<String> = emptyList(),
    val tls: String? = null,
    val probed: Boolean = true,
    val lost: Boolean = false,
) {
    /** True when the router identified itself in at least one way. */
    val any: Boolean get() = upnp.isNotEmpty() || tls != null
}

/** The gate's verdict on one admission, before anything other than the identity probe is sent. */
sealed interface Verdict {
    /** The LanKeys reason for a refusal; null for the two that allow. */
    val reason: String?

    data object NotConfirmed : Verdict { override val reason = LanKeys.REASON_NOT_CONFIRMED }

    /** Only an old (v1, address-only hash) confirmation matches: the card asks for one more confirmation (L8-Q3). */
    data object Reconfirm : Verdict { override val reason = LanKeys.REASON_RECONFIRM }

    /** The row was written under another key, so no record can match: confirm again. */
    data object KeyChanged : Verdict { override val reason = LanKeys.REASON_KEY_CHANGED }

    /** A stored factor was seen again: this is the confirmed router. */
    data object Match : Verdict { override val reason: String? = null }

    /** Nothing matched, and a factor both sides have differs: another router. */
    data object Mismatch : Verdict { override val reason = LanKeys.REASON_ROUTER_MISMATCH }

    /** Nothing comparable came back (the router stayed silent, or showed only a factor never stored): fail closed. */
    data object Silent : Verdict { override val reason = LanKeys.REASON_ROUTER_SILENT }

    /** The router could not be asked at all (no probe ran, or nothing was sent: no IPv4 gateway inside the network): fail closed. */
    data object NotAsked : Verdict { override val reason = LanKeys.REASON_ROUTER_NOT_ASKED }

    /** An address-only network, and the user answered "Is this your own Wi-Fi?" just now. */
    data object AddressOnly : Verdict { override val reason: String? = null }

    /** An address-only network, not asked this time. */
    data object Ask : Verdict { override val reason = LanKeys.REASON_ADDRESS_ONLY }

    /** Close it needs the router's UPnP identity itself, and it was not seen (a match by the admin key only, or address only). */
    data object NoUpnpMatch : Verdict { override val reason = LanKeys.REASON_NO_UPNP_MATCH }

    /** The Wi-Fi changed during the probe. */
    data object Lost : Verdict { override val reason = LanKeys.REASON_NETWORK_UNKNOWN }

    val allowed: Boolean get() = this == Match || this == AddressOnly
}

object RouterIdentity {
    /** True when [judge] needs a fresh probe: only a router-mode record of the current key is checked against its router. */
    fun needsProbe(record: NetworkRecord?, keyIdOk: Boolean): Boolean = keyIdOk && record?.mode == RecordMode.ROUTER

    /**
     * The decision table of spec 2.4. [record] is this network's v2 record (null when there is none), [legacy] whether a
     * v1 line holds this network's SHA-256, [keyIdOk] whether the row was written under the current key, [seen] the
     * probe's tokens (null when no probe ran; a router-mode record then reads as not asked), [askedNow] whether the user
     * answered "Is this your own Wi-Fi?" within the last minute for this network.
     */
    fun judge(record: NetworkRecord?, legacy: Boolean, keyIdOk: Boolean, seen: SeenTokens?, purpose: Purpose, askedNow: Boolean): Verdict {
        if (!keyIdOk) return Verdict.KeyChanged
        if (record == null) return if (legacy) Verdict.Reconfirm else Verdict.NotConfirmed
        if (record.mode == RecordMode.ADDRESS) {
            return when {
                purpose == Purpose.CLOSE_IT -> Verdict.NoUpnpMatch
                askedNow -> Verdict.AddressOnly
                else -> Verdict.Ask
            }
        }
        if (seen == null) return Verdict.NotAsked
        if (seen.lost) return Verdict.Lost
        if (!seen.probed) return Verdict.NotAsked
        val upnpMatch = seen.upnp.any { it in record.upnp }
        val tlsMatch = seen.tls != null && seen.tls == record.tls
        if (upnpMatch || tlsMatch) {
            return if (purpose == Purpose.CLOSE_IT && !upnpMatch) Verdict.NoUpnpMatch else Verdict.Match
        }
        val comparableDiffers = (seen.upnp.isNotEmpty() && record.upnp.isNotEmpty()) || (seen.tls != null && record.tls != null)
        return if (comparableDiffers) Verdict.Mismatch else Verdict.Silent
    }

    /**
     * The record after a match: the seen UPnP tokens first, then the record's others, at most [NetworkRecord.MAX_UPNP];
     * the seen admin key when there is one. A factor not seen this time is never removed, so a firmware update that
     * re-keys the admin page while the UUID stays (or the reverse) is followed.
     */
    fun refresh(record: NetworkRecord, seen: SeenTokens): NetworkRecord {
        if (record.mode != RecordMode.ROUTER) return record
        val upnp = (seen.upnp + record.upnp).distinct().take(NetworkRecord.MAX_UPNP)
        return record.copy(upnp = upnp, tls = seen.tls ?: record.tls)
    }

    /** Most root-device UUIDs one gateway's replies may give. */
    const val MAX_UUIDS = 4

    /**
     * The gateway's root-device UUIDs among SSDP [replies] (source address text, datagram text): only replies whose source
     * is exactly [gateway] (the same `hostAddress` text), that parse as an SSDP response, whose ST is `upnp:rootdevice` and
     * whose USN is `uuid:<x>::upnp:rootdevice` with a UUID [Ssdp.uuidOf] accepts. Distinct, sorted, at most [MAX_UUIDS].
     */
    fun uuidsFrom(gateway: String, replies: List<Pair<String, String>>): List<String> =
        replies.asSequence()
            .filter { (source, _) -> source == gateway }
            .mapNotNull { (_, text) -> Ssdp.parseResponse(text) }
            .filter { it.st?.trim().equals(ROOT_DEVICE, ignoreCase = true) }
            .filter { it.usn?.trim()?.endsWith("::$ROOT_DEVICE", ignoreCase = true) == true }
            .mapNotNull { Ssdp.uuidOf(it.usn) }
            .distinct()
            .sorted()
            .take(MAX_UUIDS)
            .toList()

    const val ROOT_DEVICE = "upnp:rootdevice"
}

/**
 * The keyed tokens of the gate (spec 3.1). [mac] is HMAC-SHA256 under the phone's homenet key, as 64 lowercase hex (on
 * Android the Keystore key of tunnels/homenet's HomenetKey; in tests a javax.crypto.Mac with a test key). A token is the
 * first 16 bytes (32 hex) of the tag of a domain-separated input; the key id the first 4 bytes (8 hex) of the tag of a
 * fixed text, which changes when the key is replaced and tells nothing about it. Inputs and tokens are never logged.
 */
class RouterTokens(private val mac: (String) -> String) {
    fun net(fingerprint: NetworkFingerprint): String = token(NET + fingerprint.canonical)

    fun upnp(uuid: String): String = token(UPNP + uuid)

    fun tls(spkiSha256Hex: String): String = token(TLS + spkiSha256Hex.lowercase())

    fun keyId(): String = tag(KEY_ID).take(8)

    /** What the probe saw, as tokens: raw values go in, only tokens come out. */
    fun seen(uuids: List<String>, spkiSha256Hex: String?, probed: Boolean, lost: Boolean): SeenTokens =
        SeenTokens(uuids.distinct().take(NetworkRecord.MAX_UPNP).map(::upnp).distinct(), spkiSha256Hex?.let(::tls), probed, lost)

    private fun token(input: String): String = tag(input).take(32)

    private fun tag(input: String): String {
        val out = mac(input)
        check(TAG.matches(out)) { "the mac function must give 64 lowercase hex" }
        return out
    }

    companion object {
        const val NET = "net:v2\n"
        const val UPNP = "upnp:v1\n"
        const val TLS = "tls:v1\n"
        const val KEY_ID = "kid:v1"

        private val TOKEN = Regex("[0-9a-f]{32}")
        private val TAG = Regex("[0-9a-f]{64}")

        fun isToken(text: String): Boolean = TOKEN.matches(text)
    }
}

/**
 * The one-shot answer to "Is this your own Wi-Fi?" for an address-only network: set by the dialog, valid [VALID_MS],
 * consumed by the next admission of that network. Thread-safe; in memory only.
 */
class AddressOnlyPermit(private val clock: () -> Long = System::currentTimeMillis) {
    private var net: String? = null
    private var at = 0L

    @Synchronized
    fun grant(netToken: String) {
        net = netToken
        at = clock()
    }

    /** True once, within [VALID_MS] of [grant] for [netToken]; any call clears the permit. */
    @Synchronized
    fun consume(netToken: String): Boolean {
        val ok = net == netToken && clock() - at in 0 until VALID_MS
        net = null
        return ok
    }

    companion object {
        const val VALID_MS = 60_000L
    }
}
