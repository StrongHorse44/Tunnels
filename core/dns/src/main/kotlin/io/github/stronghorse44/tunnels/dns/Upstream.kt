package io.github.stronghorse44.tunnels.dns

import java.net.URI

/**
 * Where a Traffic session sends the lookups it forwards. [NETWORK] is the network's own resolver over plain UDP, as
 * before; any other choice sends each lookup over DNS-over-HTTPS (RFC 8484) to [url], so what leaves the phone during a
 * session is encrypted. Never falls back to plain DNS on its own: a lookup the provider does not answer fails, and the
 * app retries.
 */
data class Upstream(val provider: String = NETWORK, val url: String? = null) {
    val encrypted: Boolean get() = provider != NETWORK && url != null

    /** "Quad9 (encrypted)", "dns.example.net (encrypted)" or "your network's resolver (unencrypted)". */
    val label: String
        get() = when {
            !encrypted -> "your network's resolver (unencrypted)"
            else -> (PRESETS.firstOrNull { it.id == provider }?.name ?: hostOf(url)) + " (encrypted)"
        }

    fun encode(): String = Fields.encode(listOf("provider" to provider, "url" to url.orEmpty()))

    data class Preset(val id: String, val name: String, val url: String, val note: String)

    companion object {
        /** Settings key in the encrypted store. */
        const val KEY = "traffic.upstream"
        const val NETWORK = "network"
        const val CUSTOM = "custom"

        /** DoH endpoints of resolvers that publish a no-logging policy. Picked by the user; none is the default. */
        val PRESETS: List<Preset> = listOf(
            Preset("quad9", "Quad9", "https://dns.quad9.net/dns-query", "Swiss non-profit; also refuses known malware domains"),
            Preset("mullvad", "Mullvad", "https://dns.mullvad.net/dns-query", "Swedish VPN provider; plain resolver, no filtering"),
            Preset("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", "US company; fast, audited no-logging claims"),
        )

        fun preset(id: String): Upstream? = PRESETS.firstOrNull { it.id == id }?.let { Upstream(it.id, it.url) }

        /** A custom endpoint, or null when [input] is not an https URL [validUrl] accepts. */
        fun custom(input: String): Upstream? = validUrl(input)?.let { Upstream(CUSTOM, it) }

        /**
         * The DoH endpoint most providers serve next to a Private DNS hostname (`https://<host>/dns-query`), so a
         * phone already set up for Private DNS can keep the same provider encrypted during sessions.
         */
        fun fromPrivateDns(host: String): Upstream? = custom("https://${host.trim().trimEnd('.')}/dns-query")

        /**
         * [input] as a normalised https URL, or null. Hosts must be names or addresses, no credentials, query or
         * fragment, and none of the characters the settings encoding reserves.
         */
        fun validUrl(input: String): String? {
            val text = input.trim()
            if (text.isEmpty() || text.length > 300 || text.any { it == ';' || it == '=' || it.isWhitespace() }) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            if (host.isBlank() || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().ifEmpty { "/dns-query" }
            return "https://${host.lowercase()}$port$path"
        }

        fun hostOf(url: String?): String = url?.let { runCatching { URI(it).host }.getOrNull() } ?: "custom resolver"

        /** Missing or damaged values mean the network's resolver. */
        fun decode(s: String?): Upstream {
            if (s.isNullOrBlank()) return Upstream()
            val f = Fields.parse(s)
            val provider = f["provider"] ?: return Upstream()
            if (provider == NETWORK) return Upstream()
            preset(provider)?.let { return it }
            if (provider == CUSTOM) return f["url"]?.let(::custom) ?: Upstream()
            return Upstream()
        }
    }
}

/** RFC 8484 framing around a plain DNS message. */
object Doh {
    const val CONTENT_TYPE = "application/dns-message"
    const val MAX_RESPONSE = 65_535

    /** [query] with its id set to 0, as RFC 8484 asks so HTTP caches can share answers. Null when it is not a query. */
    fun request(query: ByteArray): ByteArray? {
        val message = DnsMessage.parseOrNull(query) ?: return null
        if (message.isResponse) return null
        return query.copyOf().also { it[0] = 0; it[1] = 0 }
    }

    /**
     * [reply] with [query]'s id restored, or null when the reply is not an answer to that question (a confused or
     * hostile server must not be able to answer a different name).
     */
    fun response(reply: ByteArray, query: ByteArray): ByteArray? {
        if (reply.size > MAX_RESPONSE) return null
        val answer = DnsMessage.parseOrNull(reply) ?: return null
        val asked = DnsMessage.parseOrNull(query) ?: return null
        if (!answer.isResponse) return null
        val q = asked.questions.firstOrNull()
        val a = answer.questions.firstOrNull()
        if (q != null && (a == null || !a.name.equals(q.name, ignoreCase = true) || a.type != q.type)) return null
        return reply.copyOf().also { it[0] = query[0]; it[1] = query[1] }
    }
}
