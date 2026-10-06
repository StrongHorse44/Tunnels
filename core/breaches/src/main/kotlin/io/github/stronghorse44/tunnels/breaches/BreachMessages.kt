package io.github.stronghorse44.tunnels.breaches

/** The words the screen uses for what can go wrong, kept here so they are tested. */
object BreachMessages {
    /** An HTTP answer other than 200. */
    fun forHttp(code: Int): String = when {
        code == 429 -> "The service asked for fewer requests (429). Try again in a few minutes."
        code == 401 || code == 403 -> "The service refused the request ($code). The list may now need a key, which Tunnels does not send."
        code == 404 -> "The service answered Not Found (404). The list may have moved."
        code in 500..599 -> "The service had a problem ($code). Try again in a minute."
        else -> "The service answered $code."
    }

    const val REDIRECT = "The service tried to send Tunnels to another address. Tunnels does not follow that, so nothing was fetched."

    const val WRONG_TYPE = "The answer was not a JSON list, so nothing was kept."

    fun tooLarge(limitBytes: Long): String = "The answer is larger than ${limitBytes / (1024 * 1024)} MiB, so nothing was kept."

    /** No connection at all: on GrapheneOS most often the Network permission, otherwise no network. */
    fun noConnection(networkPermission: Boolean): String =
        if (!networkPermission) "Tunnels' Network permission is off, so it cannot reach the service. Turn it on for the fetch and off again afterwards."
        else "Could not reach the service. Check the connection (and any VPN or firewall in the way) and try again."

    const val LINX_PACKAGE = "io.github.stronghorse44.linx"
    const val LINX_MISSING = "Linx is not installed in this profile"
}
