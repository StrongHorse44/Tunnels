package io.github.stronghorse44.tunnels.lan

/** The headers of one SSDP response (or NOTIFY) that matter to the scan. */
data class SsdpResponse(
    val location: String?,
    val server: String?,
    val st: String?,
    val usn: String?,
) {
    /** "urn:schemas-upnp-org:device:MediaRenderer:1" -> "MediaRenderer"; "upnp:rootdevice" -> "rootdevice". */
    val shortType: String?
        get() = st?.let { Ssdp.shortType(it) }
}

/** SSDP (UPnP discovery) message builder and header parser. Pure functions, no sockets. */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MAX_LINES = 64
    const val MAX_LINE_LENGTH = 1024

    /** The M-SEARCH datagram: ssdp:all with the given MX (seconds devices may wait before answering). */
    fun mSearch(mx: Int = 2, st: String = "ssdp:all"): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: ${mx.coerceIn(1, 5)}\r\n" +
            "ST: $st\r\n" +
            "\r\n"

    /** Parses a unicast M-SEARCH response or multicast NOTIFY; null for anything that is not one. */
    fun parseResponse(text: String): SsdpResponse? {
        val lines = text.split("\r\n", "\n").take(MAX_LINES)
        val first = lines.firstOrNull()?.trim() ?: return null
        val ok = first.startsWith("HTTP/1.1 200", ignoreCase = true) || first.startsWith("HTTP/1.0 200", ignoreCase = true) ||
            first.startsWith("NOTIFY * HTTP/1.1", ignoreCase = true)
        if (!ok) return null
        val headers = parseHeaders(lines.drop(1))
        val st = headers["st"] ?: headers["nt"]
        if (headers["location"] == null && headers["server"] == null && st == null && headers["usn"] == null) return null
        return SsdpResponse(headers["location"], headers["server"], st, headers["usn"])
    }

    /** Lowercase header names to trimmed values; stops at the blank line, ignores malformed lines. */
    fun parseHeaders(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in lines.take(MAX_LINES)) {
            if (raw.isBlank()) break
            val line = if (raw.length > MAX_LINE_LENGTH) raw.substring(0, MAX_LINE_LENGTH) else raw
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isNotEmpty() && name !in out) out[name] = value
        }
        return out
    }

    /** The readable middle of a UPnP URN, or the last colon-separated token. */
    fun shortType(st: String): String {
        val parts = st.trim().split(':').filter { it.isNotEmpty() }
        if (parts.size >= 4 && parts[0].equals("urn", true)) return parts[parts.size - 2]
        return parts.lastOrNull() ?: st
    }

    private val UUID_BODY = Regex("[0-9a-f-]{8,64}")

    /**
     * The device UUID of a USN header ("uuid:1234abcd-...::upnp:rootdevice" gives "1234abcd-..."): the text after
     * "uuid:" up to "::", lowercased. Null for a USN that carries none (a bare urn) and for anything that is not
     * 8 to 64 hex digits and dashes, so a hostile reply cannot put free text into the census.
     */
    fun uuidOf(usn: String?): String? {
        val text = usn?.trim() ?: return null
        if (!text.startsWith("uuid:", ignoreCase = true)) return null
        val body = text.substring("uuid:".length).substringBefore("::").trim().lowercase()
        return body.takeIf { UUID_BODY.matches(it) }
    }

    /** Whether an ST/NT header or USN names an Internet Gateway Device or its WAN connection service. */
    fun isIgdType(type: String?): Boolean {
        val t = type ?: return false
        return t.contains("WANIPConnection", true) || t.contains("WANPPPConnection", true) || t.contains("InternetGatewayDevice", true)
    }
}
