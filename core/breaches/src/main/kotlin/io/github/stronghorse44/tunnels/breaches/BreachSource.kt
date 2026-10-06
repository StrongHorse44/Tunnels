package io.github.stronghorse44.tunnels.breaches

/**
 * Where the breach list comes from: Have I Been Pwned's public catalogue of breaches, one host, HTTPS only
 * (specs/B11-linx.md section 9.1). The request carries a User-Agent and nothing else: no key, no account, no
 * address, no domain.
 */
object BreachSource {
    const val HOST = "haveibeenpwned.com"
    const val URL = "https://haveibeenpwned.com/api/v3/breaches"

    /** The one request header the client sends. */
    const val USER_AGENT = "Tunnels-breaches"

    /** Every hop, before any socket: HTTPS to exactly [HOST], or nothing. Subdomains and look-alikes are refused. */
    fun isAllowed(scheme: String, host: String): Boolean =
        asciiLower(scheme) == "https" && asciiLower(host) == HOST

    /** Lower-case ASCII letters only; null when [s] has any other kind of character (no Unicode case folding: U+0131 is not an `i`). */
    private fun asciiLower(s: String): String? {
        if (s.any { it.code > 0x7f }) return null
        return buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + 32 else c) }
    }
}
