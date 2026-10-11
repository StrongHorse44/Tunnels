package io.github.stronghorse44.tunnels.lan

import java.util.Locale

/**
 * What the device census recognises a host by: up to [MAX_TOKENS] tokens made from what the host announced. A token is
 * its type letter plus the first 16 hex chars of SHA-256 over a domain tag, the full hash of the confirmed network, the
 * type and the value, so the same device has different tokens on different networks and a token never spells out the
 * UUID or name it was made from. Not keyed, on purpose: every input is already stored in clear in the same snapshot
 * (host name, services, vendor), all inside the encrypted store, so a key would protect nothing the store does not
 * hold, and an unkeyed token survives export and restore, so the list keeps working on a restored phone.
 *
 * Types, in this order (the first token is the host's primary one):
 *  - `u`: the UUID of an SSDP USN ([Ssdp.uuidOf]), at most 2.
 *  - `n`: an mDNS instance name, normalised ([normalizeName]), at most 4, sorted.
 *  - `s`: a shape of kind, vendor, models and services, only when there is no `u` and no `n`, and only when the shape
 *    says something (a vendor, a model or a service). Open ports are never part of an identity: timeouts make them flicker.
 *  - `h`: the name the router gave for the host's address ([ReverseDns.deviceName]), normalised like an mDNS name, at most
 *    1. Last, so a device already on the list keeps its primary token; for a host that announced nothing (found by the
 *    sweep, [LanSweep]) it is the only token, and makes it one that can be added to the list.
 *  - `c`: a name the device gave when asked directly ([LanHostRecord.directNames]: its Windows name or its own mDNS
 *    answer), normalised like an mDNS name, at most 2. Asked only of devices the user looked closer at (RJ, 2026-10-11),
 *    so a device that announces nothing and that the router does not name can still go on the list. Last of all, so it
 *    never moves a primary token that was there before.
 *
 * A host with no token has no identity: the census judges it unknown. Plain JVM, unit-tested.
 */
object DeviceIdentity {
    const val DOMAIN = "tunnels.census.v1"
    const val MAX_TOKENS = 6
    const val MAX_UUID_TOKENS = 2
    const val MAX_NAME_TOKENS = 4
    const val MAX_DIRECT_TOKENS = 2
    private const val HEX_LENGTH = 16

    /** `u`, `n`, `s`, `h` or `c`, then 16 lowercase hex digits. */
    val TOKEN = Regex("[unshc][0-9a-f]{$HEX_LENGTH}")

    private val conflictSuffix = Regex("\\s\\(\\d{1,3}\\)$")
    private val whitespace = Regex("\\s+")

    fun isToken(text: String): Boolean = TOKEN.matches(text)

    /** One token: [type] ("u", "n", "s", "h" or "c") plus 16 hex chars of the salted hash of [value] on the network [networkHash]. */
    fun token(networkHash: String, type: String, value: String): String =
        type + NetworkFingerprint.sha256Hex("$DOMAIN\n$networkHash\n$type\n$value").take(HEX_LENGTH)

    /**
     * An mDNS instance name as the census compares it: trimmed, inner whitespace collapsed to one space, lowercase, one
     * trailing " (2)"-style conflict suffix removed (mDNS renames a second device with the same name that way). Null
     * when nothing is left.
     */
    fun normalizeName(raw: String): String? {
        val collapsed = raw.trim().replace(whitespace, " ").lowercase(Locale.ROOT)
        return collapsed.replace(conflictSuffix, "").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The shape text, or null when it carries nothing but the kind (every featureless host would then share one
     * identity, and acknowledging one would acknowledge them all).
     */
    fun shape(kind: HostKind, vendor: String?, models: Collection<String>, services: Collection<String>): String? {
        val modelText = models.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
        val serviceText = services.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("|")
        val vendorText = vendor?.trim()?.takeIf { it.isNotEmpty() }
        if (vendorText == null && modelText.isEmpty() && serviceText.isEmpty()) return null
        return "kind=${kind.label};vendor=${vendorText ?: "-"};models=$modelText;services=$serviceText"
    }

    /**
     * The host's tokens on the network [networkHash]. [kind] must be the kind inferred without the host's open ports
     * (the ports are not part of an identity, and the kind would otherwise carry them in); [vendor] is the vendor hint.
     */
    fun tokens(networkHash: String, record: LanHostRecord, kind: HostKind, vendor: String?): List<String> {
        val out = ArrayList<String>(MAX_TOKENS)
        val uuids = record.ssdpUuids.distinct().sorted().take(MAX_UUID_TOKENS)
        uuids.forEach { out += token(networkHash, "u", it) }
        val names = record.names.toList().mapNotNull(::normalizeName).distinct().sorted().take(MAX_NAME_TOKENS)
        names.forEach { out += token(networkHash, "n", it) }
        if (uuids.isEmpty() && names.isEmpty()) {
            val services = record.mdnsTypes.toList().map(MdnsTypes::shortName) + record.ssdpTypes.toList().map(Ssdp::shortType)
            shape(kind, vendor, record.models.toList(), services)?.let { out += token(networkHash, "s", it) }
        }
        record.routerName?.let(::normalizeName)?.let { out += token(networkHash, "h", it) }
        record.directNames.toList().mapNotNull(::normalizeName).distinct().sorted().take(MAX_DIRECT_TOKENS)
            .forEach { out += token(networkHash, "c", it) }
        return out.take(MAX_TOKENS)
    }

    /**
     * The name a host is shown and titled by: the one whose normalised form sorts first (ties by the text itself), so a
     * device that announces several names, in whatever order, keeps one title. It is the name behind the first `n` token.
     */
    fun canonicalName(names: Collection<String>): String? =
        names.filter { normalizeName(it) != null }.minWithOrNull(compareBy<String> { normalizeName(it) }.thenBy { it })?.trim()

    /** The primary token of a host's [ids] (the first one), or null for a host with no identity. */
    fun primaryOf(ids: List<String>): String? = ids.firstOrNull()?.takeIf(::isToken)
}
