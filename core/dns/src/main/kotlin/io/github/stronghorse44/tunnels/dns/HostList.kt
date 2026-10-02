package io.github.stronghorse44.tunnels.dns

/**
 * A blocklist of exact hostnames, as hosts files mean them: `ads.example.com` blocks that host only, not its
 * subdomains or its parent. Held as one sorted array, so a list of thousands of hosts costs little memory and a
 * lookup is a binary search.
 */
class HostList private constructor(private val hosts: Array<String>) {
    val size: Int get() = hosts.size

    operator fun contains(host: String): Boolean {
        val h = PublicSuffix.normalize(host)
        return h.isNotEmpty() && hosts.binarySearch(h) >= 0
    }

    companion object {
        /** Names hosts files map that are never ad hosts. */
        private val IGNORED = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        /**
         * Reads hosts-file lines (`0.0.0.0 host`, `127.0.0.1 host`, `::1 host`) or bare domain lines. Comments, IP
         * addresses as names, and names that are not valid hostnames are skipped.
         */
        fun parse(lines: Sequence<String>): HostList {
            val out = HashSet<String>()
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                val names = if (parts.size >= 2 && looksLikeAddress(parts[0])) parts.drop(1) else parts.take(1)
                for (name in names) {
                    val h = PublicSuffix.normalize(name)
                    if (h.isEmpty() || h in IGNORED || looksLikeAddress(h) || !validHost(h)) continue
                    out += h
                }
            }
            return HostList(out.toTypedArray().also { it.sort() })
        }

        fun parse(text: String): HostList = parse(text.lineSequence())

        val EMPTY = HostList(emptyArray())

        private fun looksLikeAddress(s: String) = s.contains(':') || s.all { it.isDigit() || it == '.' }

        private fun validHost(h: String): Boolean =
            h.length <= DnsMessage.MAX_NAME_LENGTH && h.contains('.') &&
                h.split('.').all { label -> label.isNotEmpty() && label.length <= DnsMessage.MAX_LABEL_LENGTH && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' } }
    }
}

/** A blocklist shipped inside the APK, refreshed by `scripts/update-blocklists.sh` before a release. */
data class BundledList(
    val id: String,
    val name: String,
    val description: String,
    /** Path under the traffic module's assets. */
    val asset: String,
    val license: String,
    val source: String,
)

object Blocklists {
    val ADAWAY = BundledList(
        id = "adaway",
        name = "AdAway",
        description = "mobile ad and analytics hosts",
        asset = "blocklists/adaway.txt",
        license = "CC BY 3.0, AdAway contributors",
        source = "https://github.com/AdAway/adaway.github.io",
    )

    val ALL: List<BundledList> = listOf(ADAWAY)

    fun byId(id: String): BundledList? = ALL.firstOrNull { it.id == id }
}
