package io.github.stronghorse44.tunnels.lan

/** Host, port and path of a plain http:// URL, as the SSDP LOCATION header gives it. */
data class HttpTarget(val host: String, val port: Int, val path: String) {
    val isIpv6: Boolean get() = host.contains(':')
}

/** A parsed HTTP response: status code, lowercase headers and the body (already capped by the caller). */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/**
 * The few pieces of HTTP the tunnel needs to fetch a UPnP description over a raw socket (which keeps the
 * app's cleartext policy and any HTTP stack out of it). Pure functions, no I/O.
 */
object HttpLite {
    const val MAX_BODY = 64 * 1024

    /** Parses "http://192.168.1.1:49152/igd.xml"; null for https, other schemes, or anything malformed. */
    fun parseUrl(url: String): HttpTarget? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        val rest = trimmed.substring(7)
        if (rest.isEmpty()) return null
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"
        if (authority.isEmpty() || authority.contains('@')) return null
        val host: String
        val portText: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(1, close)
            portText = authority.substring(close + 1).removePrefix(":").ifEmpty { null }
            if (authority.length > close + 1 && authority[close + 1] != ':') return null
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon >= 0) authority.substring(0, colon) else authority
            portText = if (colon >= 0) authority.substring(colon + 1) else null
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) 80 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return HttpTarget(host, port, path.takeWhile { it != '#' }.ifEmpty { "/" })
    }

    /** A minimal HTTP/1.0 GET that asks the server to close after the response. */
    fun getRequest(target: HttpTarget): String {
        val hostHeader = if (target.isIpv6) "[${target.host}]" else target.host
        val portSuffix = if (target.port == 80) "" else ":${target.port}"
        return "GET ${target.path} HTTP/1.0\r\n" +
            "Host: $hostHeader$portSuffix\r\n" +
            "User-Agent: Tunnels\r\n" +
            "Accept: text/xml, application/xml, */*\r\n" +
            "Connection: close\r\n" +
            "\r\n"
    }

    /** Splits raw response text into status, headers and body; null when there is no status line. */
    fun parseResponse(raw: String): HttpResponse? {
        val headerEnd = raw.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else raw.indexOf("\n\n") to 2 }
        val head = if (headerEnd.first >= 0) raw.substring(0, headerEnd.first) else raw
        val body = if (headerEnd.first >= 0) raw.substring(headerEnd.first + headerEnd.second) else ""
        val lines = head.split("\r\n", "\n")
        val status = lines.firstOrNull()?.trim()?.split(' ')?.takeIf { it.size >= 2 && it[0].startsWith("HTTP/", true) }
            ?.get(1)?.toIntOrNull() ?: return null
        return HttpResponse(status, Ssdp.parseHeaders(lines.drop(1)), body.take(MAX_BODY))
    }
}
