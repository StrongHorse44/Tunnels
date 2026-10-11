package io.github.stronghorse44.tunnels.lan

import java.util.Locale

/** One port a device asked the router to open to the internet (a UPnP IGD port mapping), as the router lists it. */
data class PortMapping(
    /** "TCP" or "UDP". */
    val protocol: String,
    /** The port open on the internet side. */
    val externalPort: Int,
    /** The device inside the network the router forwards it to, as the router names it (an address). */
    val client: String,
    /** The port on that device. */
    val internalPort: Int,
    val enabled: Boolean,
    /** The one internet address the opening is limited to; empty for anyone (the usual case). */
    val remoteHost: String,
    /** The label the app that asked gave it ("Plex Media Server"), cleaned and capped; null when it gave none. */
    val description: String?,
)

/** Where the router takes port-mapping questions: the control URL of its WAN connection service, and that service's type. */
data class IgdControl(val url: String, val serviceType: String)

/**
 * Ports devices opened to the internet through the router (RJ, 2026-10-11): reading the router's UPnP port-mapping list,
 * one entry per question, and asking it to remove one when the user taps Close it and confirms. Only ever sent to the
 * confirmed network's gateway. Requests and parsers only, plain JVM, unit-tested; the scanner does the I/O.
 */
object PortMappings {
    /** The most entries read per scan; a router with more is reported as having more. */
    const val MAX_MAPPINGS = 64
    const val MAX_DESCRIPTION = 40

    /** UPnP errors that mean "no entry at that index": the end of the list. 402 is what some routers answer instead. */
    private val END_OF_LIST = setOf(713, 714, 402)

    /** Ports an opening to which is a warning: remote logins, file sharing, Android debug, camera and recorder protocols. */
    val loudPorts: Set<Int> = PortCatalog.risky + setOf(22, 548, 554, 2049, 8554, 37777, 8000)

    private val serviceBlock = Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val serviceType = Regex("<serviceType[^>]*>\\s*([^<\\s]{1,200})\\s*</serviceType>", RegexOption.IGNORE_CASE)
    private val controlUrl = Regex("<controlURL[^>]*>\\s*([^<\\s]{1,300})\\s*</controlURL>", RegexOption.IGNORE_CASE)
    private val urlBase = Regex("<URLBase[^>]*>\\s*([^<\\s]{1,300})\\s*</URLBase>", RegexOption.IGNORE_CASE)
    private val errorCode = Regex("<(?:[A-Za-z0-9_]+:)?errorCode[^>]*>\\s*(\\d{1,4})\\s*</", RegexOption.IGNORE_CASE)

    /**
     * The port-mapping services a description at [location] offers (WANIPConnection first, then WANPPPConnection), their
     * control URLs made absolute. Null-free and empty when there are none.
     */
    fun controlsOf(xml: String, location: String): List<IgdControl> {
        val text = if (xml.length > Upnp.MAX_CHARS) xml.substring(0, Upnp.MAX_CHARS) else xml
        val base = urlBase.find(text)?.groupValues?.get(1) ?: location
        return serviceBlock.findAll(text).mapNotNull { block ->
            val type = serviceType.find(block.groupValues[1])?.groupValues?.get(1)?.takeIf(Upnp::isIgdService) ?: return@mapNotNull null
            val url = controlUrl.find(block.groupValues[1])?.groupValues?.get(1)?.let { resolve(base, it) } ?: return@mapNotNull null
            IgdControl(url, type)
        }.distinct().sortedBy { if (it.serviceType.contains("WANIPConnection", true)) 0 else 1 }.take(4).toList()
    }

    /** [ref] made absolute against [base]: an absolute http URL, a path from the root, or one relative to [base]'s folder. */
    fun resolve(base: String, ref: String): String? {
        val r = ref.replace("&amp;", "&")
        if (r.startsWith("http://", ignoreCase = true)) return HttpLite.parseUrl(r)?.let { r }
        if (r.contains("://")) return null
        val b = HttpLite.parseUrl(base) ?: return null
        val host = if (b.isIpv6) "[${b.host}]" else b.host
        val path = if (r.startsWith("/")) r else b.path.substringBeforeLast('/', "") + "/" + r
        return "http://$host:${b.port}$path"
    }

    private fun xml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A SOAP request for [action] of [serviceType] at [target], HTTP/1.0 and closed after the answer. */
    fun soapRequest(target: HttpTarget, serviceType: String, action: String, args: List<Pair<String, String>>): String {
        val body = "<?xml version=\"1.0\"?>\r\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${xml(serviceType)}\">" +
            args.joinToString("") { (k, v) -> "<$k>${xml(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>\r\n"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val hostHeader = (if (target.isIpv6) "[${target.host}]" else target.host) + (if (target.port == 80) "" else ":${target.port}")
        return "POST ${target.path} HTTP/1.0\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: Tunnels\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
            "SOAPAction: \"$serviceType#$action\"\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" + body
    }

    /** The question for the [index]th entry of the list. */
    fun entryRequest(target: HttpTarget, serviceType: String, index: Int): String =
        soapRequest(target, serviceType, "GetGenericPortMappingEntry", listOf("NewPortMappingIndex" to index.toString()))

    /** The request that removes [m]: the router stops forwarding that port. */
    fun deleteRequest(target: HttpTarget, serviceType: String, m: PortMapping): String =
        soapRequest(
            target, serviceType, "DeletePortMapping",
            listOf("NewRemoteHost" to m.remoteHost, "NewExternalPort" to m.externalPort.toString(), "NewProtocol" to m.protocol),
        )

    private fun field(body: String, name: String): String? =
        Regex("<(?:[A-Za-z0-9_]+:)?$name\\b[^>]*>([^<]*)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&amp;", "&")?.trim()

    /** One entry from a GetGenericPortMappingEntry answer, or null when it is not a well-formed one. */
    fun parseEntry(body: String): PortMapping? {
        val protocol = field(body, "NewProtocol")?.uppercase(Locale.ROOT)?.takeIf { it == "TCP" || it == "UDP" } ?: return null
        val external = field(body, "NewExternalPort")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val internal = field(body, "NewInternalPort")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val client = field(body, "NewInternalClient")?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isWhitespace) } ?: return null
        return PortMapping(
            protocol = protocol,
            externalPort = external,
            client = client,
            internalPort = internal,
            enabled = field(body, "NewEnabled")?.let { it == "1" || it.equals("true", true) } ?: true,
            remoteHost = field(body, "NewRemoteHost")?.take(64)?.takeIf { it.none(Char::isWhitespace) }.orEmpty(),
            description = Greetings.clean(field(body, "NewPortMappingDescription"), MAX_DESCRIPTION),
        )
    }

    /** The UPnP error code in a fault answer, or null. */
    fun errorCode(body: String): Int? = errorCode.find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** True when an answer with [status] and [code] means the list has no entry at that index (its end), not a failure. */
    fun endsList(status: Int, code: Int?): Boolean = status != 200 && code in END_OF_LIST

    /** What reading the list gave: the entries, and whether it stopped before the end ([MAX_MAPPINGS] reached). */
    data class Listing(val mappings: List<PortMapping>, val more: Boolean)

    /** A warning when it opens a risky door ([loudPorts]) or reaches a camera; a notice otherwise. */
    fun isLoud(m: PortMapping, clientKind: HostKind?): Boolean =
        m.internalPort in loudPorts || m.externalPort in loudPorts || clientKind == HostKind.CAMERA

    /** "TCP 8443 → its port 443", or "TCP 32400" when both ports match. */
    fun ports(m: PortMapping): String = "${m.protocol} ${m.externalPort}" + if (m.internalPort != m.externalPort) " → its port ${m.internalPort}" else ""

    // ---- Observations: one comma list on the router, each entry's fields escaped -----------------

    fun encode(mappings: List<PortMapping>): String = if (mappings.isEmpty()) LanKeys.NONE else mappings.take(MAX_MAPPINGS).joinToString(";") { m ->
        listOf(m.protocol, m.externalPort.toString(), m.client, m.internalPort.toString(), if (m.enabled) "1" else "0", m.remoteHost, m.description.orEmpty())
            .joinToString(",") { LookCloser.escape(it) }
    }

    fun parse(value: String?): List<PortMapping> {
        if (value.isNullOrEmpty() || value == LanKeys.NONE) return emptyList()
        return value.split(';').mapNotNull { e ->
            val f = e.split(',').map { LookCloser.unescape(it) ?: return@mapNotNull null }
            if (f.size != 7) return@mapNotNull null
            PortMapping(
                protocol = f[0].takeIf { it == "TCP" || it == "UDP" } ?: return@mapNotNull null,
                externalPort = f[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null,
                client = f[2].takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                internalPort = f[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null,
                enabled = f[4] == "1",
                remoteHost = f[5],
                description = f[6].takeIf { it.isNotEmpty() },
            )
        }.take(MAX_MAPPINGS)
    }

    // ---- Finding subjects ------------------------------------------------------------------------

    private const val JOIN = " · "
    private val subjectShape = Regex("(.+) · (TCP|UDP) (\\d{1,5}) to the internet")

    /** `<device address> · TCP 8080 to the internet`: one finding per opening, stable while the opening stays. */
    fun subject(m: PortMapping): String = "${m.client}$JOIN${m.protocol} ${m.externalPort} to the internet"

    /** The device address, protocol and external port in a [subject], or null when it is not one. */
    fun parseSubject(subject: String): Triple<String, String, Int>? {
        val g = subjectShape.matchEntire(subject)?.groupValues ?: return null
        return Triple(g[1], g[2], g[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null)
    }

    /** The one-line result of asking the router to remove an opening, by the UPnP error it answered (null: it did it). */
    fun closeResult(m: PortMapping, status: Int?, code: Int?): String = when {
        status == 200 -> "Closed: the router no longer forwards ${m.protocol} ${m.externalPort} to ${m.client}. The finding clears at your next scan. " +
            "The device can open it again; switching UPnP off in the router stops that."
        code == 714 -> "The router has no such opening any more: it was already closed. The finding clears at your next scan."
        code == 606 || code == 401 -> "The router refused: it does not let other devices remove openings. Remove it on the router's admin page."
        status == null -> "The router did not answer. Check you are on your Wi-Fi and try again, or use the router's admin page."
        else -> "The router did not remove it (error ${code ?: status}). Remove it on the router's admin page."
    }
}
