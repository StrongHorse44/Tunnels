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
        scheme.equals("https", ignoreCase = true) && host.equals(HOST, ignoreCase = true)
}
