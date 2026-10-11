package io.github.stronghorse44.tunnels.lan

import java.util.Locale

/**
 * What a device says about itself when asked directly: the plain-words pieces "Look closer" reads out of a web page, a
 * certificate, an SSH or camera greeting and a login banner. Shown live on the screen; only the summary in
 * [LookCloser.Summary] is kept (RJ, 2026-10-11). Pure functions, plain JVM, unit-tested.
 */
object Greetings {
    /** Longest text kept or shown from any one greeting. */
    const val MAX_TEXT = 80

    /** Bytes read from any one service before the connection is closed. */
    const val MAX_READ = 16 * 1024

    /** Printable text only, whitespace collapsed, at most [max] characters; null when nothing is left. */
    fun clean(text: String?, max: Int = MAX_TEXT): String? {
        if (text == null) return null
        val out = StringBuilder()
        for (c in text) {
            when {
                c == '\t' || c == '\n' || c == '\r' -> out.append(' ')
                c.code < 0x20 || c.code == 0x7f || c in '\u0080'..'\u009f' -> {}
                else -> out.append(c)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().take(max).trim().ifEmpty { null }
    }

    private val TITLE = Regex("<title[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")

    /** The text of a page's `<title>`, entities decoded; null when there is none or it is empty. */
    fun httpTitle(body: String): String? {
        var title = TITLE.find(body)?.groupValues?.get(1) ?: return null
        title = title.replace(Regex("<[^>]*>"), " ")
        for ((entity, char) in ENTITIES) title = title.replace(entity, char, ignoreCase = true)
        title = title.replace(Regex("&#(\\d{1,5});")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0xFFFF }?.toChar()?.toString().orEmpty() }
        return clean(title)
    }

    private val REALM = Regex("realm\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /** The realm of a `WWW-Authenticate` header: cameras and routers often put their model there. */
    fun authRealm(headers: Map<String, String>): String? = headers["www-authenticate"]?.let { REALM.find(it)?.groupValues?.get(1) }?.let(::clean)

    /** Where a redirect points, when it points to another place on the same device (`https://…` or a path). */
    fun redirect(status: Int, headers: Map<String, String>): String? =
        if (status in 300..399) clean(headers["location"]) else null

    /** An SSH server's first line, read out: "OpenSSH 8.9p1 (Ubuntu)", "dropbear 2019.78"; null for anything else. */
    fun sshSoftware(line: String): String? {
        val text = clean(line, 200) ?: return null
        if (!text.startsWith("SSH-")) return null
        val rest = text.substringAfter('-').substringAfter('-', "")
        if (rest.isEmpty()) return null
        val software = rest.substringBefore(' ').replace('_', ' ')
        val comment = rest.substringAfter(' ', "").substringBefore('-').trim()
        return clean(if (comment.isEmpty()) software else "$software ($comment)")
    }

    /** What an SSH server's software says about the device: a small Linux gadget, a Linux computer, a router. */
    fun sshHint(software: String?): String? {
        val s = software?.lowercase(Locale.ROOT) ?: return null
        return when {
            "dropbear" in s -> "a small Linux device (router, camera, NAS or smart gadget)"
            "rosssh" in s -> "a MikroTik router"
            "cisco" in s -> "Cisco network equipment"
            listOf("ubuntu", "debian", "raspbian", "fedora").any { it in s } -> "a Linux computer"
            "openssh" in s -> "a computer or NAS running OpenSSH"
            else -> null
        }
    }

    /** An RTSP OPTIONS request: a camera answers with its server name without any login. */
    fun rtspOptions(): String = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: Tunnels\r\n\r\n"

    /** The `Server` header of an RTSP reply; null when the reply is not RTSP. */
    fun rtspServer(raw: String): String? {
        val lines = raw.split("\r\n", "\n")
        if (lines.firstOrNull()?.startsWith("RTSP/", ignoreCase = true) != true) return null
        return clean(Ssdp.parseHeaders(lines.drop(1))["server"])
    }

    /**
     * The first line of a login banner (FTP, telnet), with telnet's option negotiation (IAC sequences) removed; null
     * when nothing printable came.
     */
    fun bannerLine(bytes: ByteArray, length: Int = bytes.size): String? {
        val text = StringBuilder()
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xFF) {
                // IAC: a two-byte command, three with WILL/WONT/DO/DONT, longer for a subnegotiation (SB … IAC SE).
                val cmd = if (i + 1 < length) bytes[i + 1].toInt() and 0xFF else 0
                i += when (cmd) {
                    in 0xFB..0xFE -> 3
                    0xFA -> {
                        var j = i + 2
                        while (j + 1 < length && !((bytes[j].toInt() and 0xFF) == 0xFF && (bytes[j + 1].toInt() and 0xFF) == 0xF0)) j++
                        j + 2 - i
                    }
                    else -> 2
                }
                continue
            }
            if (b == '\n'.code && text.isNotBlank()) break
            text.append(if (b < 0x80) b.toChar() else '?')
            i++
        }
        return clean(text.toString())
    }
}

/** The names on a TLS certificate, as "Look closer" reads them: whose it says it is, who signed it, and its other names. */
data class CertFacts(val commonName: String?, val organization: String?, val issuer: String?, val selfSigned: Boolean, val altNames: List<String>) {
    /** "DS-2CD2143G2 (Hikvision), self-signed": the names worth showing, in one line. */
    val line: String?
        get() = listOfNotNull(
            listOfNotNull(commonName, organization?.takeIf { it != commonName }?.let { "($it)" }).joinToString(" ").ifEmpty { null },
            if (selfSigned) "self-signed" else issuer?.let { "signed by $it" },
        ).joinToString(", ").ifEmpty { null }

    companion object {
        /** Builds the facts from RFC 2253 subject and issuer names (`CN=…,O=…`) and the DNS alternative names. */
        fun of(subject: String, issuer: String, altNames: List<String>): CertFacts {
            val s = dn(subject)
            val i = dn(issuer)
            return CertFacts(
                commonName = Greetings.clean(s["CN"]),
                organization = Greetings.clean(s["O"]),
                issuer = Greetings.clean(i["CN"] ?: i["O"]),
                selfSigned = subject.isNotEmpty() && subject == issuer,
                altNames = altNames.mapNotNull { Greetings.clean(it, 60) }.distinct().take(4),
            )
        }

        /** The attributes of a distinguished name; a backslash escapes the next character. */
        fun dn(name: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val part = StringBuilder()
            fun flush() {
                val p = part.toString()
                val eq = p.indexOf('=')
                if (eq > 0) out.putIfAbsent(p.substring(0, eq).trim().uppercase(Locale.ROOT), p.substring(eq + 1).trim())
                part.clear()
            }
            var i = 0
            while (i < name.length) {
                val c = name[i]
                when {
                    c == '\\' && i + 1 < name.length -> {
                        part.append(name[i + 1])
                        i += 2
                        continue
                    }
                    c == ',' || c == '+' -> flush()
                    else -> part.append(c)
                }
                i++
            }
            flush()
            return out
        }
    }
}

/**
 * NetBIOS node status (RFC 1002, 4.2.17): the question Windows file sharing answers on UDP 137 with its computer name and
 * workgroup. Only names are read; the reply's statistics (which carry the hardware address) are never parsed.
 */
object NetBios {
    const val PORT = 137

    /** A node status request for the wildcard name `*`, with transaction [id]. */
    fun query(id: Int): ByteArray {
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        val out = ArrayList<Byte>(50)
        fun u16(v: Int) {
            out.add(((v shr 8) and 0xFF).toByte())
            out.add((v and 0xFF).toByte())
        }
        u16(id and 0xFFFF)
        u16(0x0000)
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        out.add(0x20)
        for (b in name) {
            val v = b.toInt() and 0xFF
            out.add(('A'.code + (v shr 4)).toByte())
            out.add(('A'.code + (v and 0x0F)).toByte())
        }
        out.add(0)
        u16(0x0021)
        u16(0x0001)
        return out.toByteArray()
    }

    /** One name a node holds: the name, its suffix (0x00 workstation, 0x20 file server…), and whether it is a group name. */
    data class Name(val name: String, val suffix: Int, val group: Boolean)

    /** The names of a node status reply to transaction [id]; null for anything else or anything malformed. */
    fun parse(id: Int, buf: ByteArray, length: Int = buf.size): List<Name>? {
        if (length < 12 || length > buf.size) return null
        fun u8(i: Int) = buf[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u16(0) != (id and 0xFFFF) || u16(2) and 0x8000 == 0) return null
        if (u16(6) < 1) return null
        var pos = 12
        repeat(u16(4)) { pos = skipName(buf, pos, length) ?: return null; pos += 4 }
        pos = skipName(buf, pos, length) ?: return null
        if (pos + 10 > length || u16(pos) != 0x0021) return null
        val rdLength = u16(pos + 8)
        pos += 10
        if (pos + rdLength > length || rdLength < 1) return null
        val count = u8(pos)
        pos++
        val names = ArrayList<Name>()
        repeat(count) {
            if (pos + 18 > length) return names
            val raw = String(buf, pos, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
            val suffix = u8(pos + 15)
            val group = u16(pos + 16) and 0x8000 != 0
            Greetings.clean(raw, 15)?.let { names += Name(it, suffix, group) }
            pos += 18
        }
        return names
    }

    private fun skipName(buf: ByteArray, start: Int, length: Int): Int? {
        var pos = start
        while (pos < length) {
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= length) pos + 2 else null
                else -> pos += 1 + len
            }
        }
        return null
    }

    /** The computer's own name: the first unique name with the workstation suffix. */
    fun computerName(names: List<Name>): String? = names.firstOrNull { !it.group && it.suffix == 0x00 }?.name

    /** Its workgroup or domain: the first group name with the workstation suffix. */
    fun workgroup(names: List<Name>): String? = names.firstOrNull { it.group && it.suffix == 0x00 }?.name
}

/** ICMP echo (ping) as an app's datagram ping socket sends and receives it: no IP header, the kernel sets the id. */
object IcmpEcho {
    fun request(id: Int, seq: Int, payload: ByteArray = "Tunnels".toByteArray(Charsets.US_ASCII)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 8
        out[1] = 0
        out[4] = ((id shr 8) and 0xFF).toByte()
        out[5] = (id and 0xFF).toByte()
        out[6] = ((seq shr 8) and 0xFF).toByte()
        out[7] = (seq and 0xFF).toByte()
        payload.copyInto(out, 8)
        val sum = checksum(out)
        out[2] = ((sum shr 8) and 0xFF).toByte()
        out[3] = (sum and 0xFF).toByte()
        return out
    }

    /** The internet checksum (RFC 1071) of [data]. */
    fun checksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** True when [reply] is an echo reply (type 0) carrying sequence number [seq]. */
    fun isReply(reply: ByteArray, length: Int, seq: Int): Boolean =
        length >= 8 && reply[0].toInt() == 0 && (((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF)) == (seq and 0xFFFF)
}

/**
 * The operating system family a ping reply's time-to-live hints at. Systems start it at 64 (Android, iOS, macOS, Linux),
 * 128 (Windows) or 255 (much network equipment), and a device on the same network is zero or one hop away.
 */
enum class OsHint(val slug: String, val words: String) {
    UNIX_LIKE("unix", "Android, iPhone, Mac or Linux"),
    WINDOWS("windows", "Windows"),
    NETWORK_GEAR("gear", "network equipment, a printer or another embedded system"),
    ;

    companion object {
        fun fromTtl(ttl: Int): OsHint? = when (ttl) {
            in 33..64 -> UNIX_LIKE
            in 65..128 -> WINDOWS
            in 129..255 -> NETWORK_GEAR
            else -> null
        }

        fun bySlug(slug: String?): OsHint? = entries.firstOrNull { it.slug == slug }
    }
}

/** One thing a port tells about a device, in plain words, and the kind it points to when it points to one. */
data class PortClue(val port: Int, val words: String, val kind: HostKind? = null)

/** What the open ports of one device say about it. */
object PortClues {
    private val clues: Map<Int, PortClue> = listOf(
        PortClue(62078, "listens for iPhone or iPad sync", HostKind.PHONE),
        PortClue(7000, "receives AirPlay (Apple TV, HomePod, Mac or an AirPlay TV)", HostKind.TV),
        PortClue(8008, "answers Google Cast", HostKind.TV),
        PortClue(8009, "answers Google Cast (Chromecast, Google TV, Nest speaker or a Cast TV)", HostKind.TV),
        PortClue(1400, "answers like a Sonos speaker", HostKind.SPEAKER),
        PortClue(554, "streams video (RTSP), usually a camera or recorder", HostKind.CAMERA),
        PortClue(8554, "streams video (RTSP), usually a camera", HostKind.CAMERA),
        PortClue(34567, "speaks the protocol of cheap cameras and video recorders (XMEye)", HostKind.CAMERA),
        PortClue(37777, "speaks Dahua's camera and recorder protocol", HostKind.CAMERA),
        PortClue(9100, "takes raw print jobs", HostKind.PRINTER),
        PortClue(631, "takes print jobs (IPP)", HostKind.PRINTER),
        PortClue(515, "takes print jobs (LPD)", HostKind.PRINTER),
        PortClue(6668, "answers like a Tuya smart-home device", HostKind.IOT),
        PortClue(1883, "runs a smart-home message bus (MQTT)", HostKind.IOT),
        PortClue(445, "shares files like a Windows PC or a NAS"),
        PortClue(3389, "offers Windows remote desktop", HostKind.COMPUTER),
        PortClue(5357, "announces itself like Windows does", HostKind.COMPUTER),
        PortClue(2869, "runs the Windows UPnP host", HostKind.COMPUTER),
        PortClue(5900, "offers screen sharing (VNC)", HostKind.COMPUTER),
        PortClue(548, "shares files like a Mac or an older NAS"),
        PortClue(5000, "has an admin page on 5000, like a NAS"),
        PortClue(5001, "has an encrypted admin page on 5001, like a NAS", HostKind.NAS),
        PortClue(32400, "runs a Plex media server", HostKind.NAS),
        PortClue(5555, "leaves the Android debug bridge open (often an Android TV box)"),
        PortClue(22, "accepts SSH logins (a computer, NAS, router or small Linux gadget)"),
        PortClue(23, "accepts telnet logins (a router or cheap gadget, often with a default password)"),
        PortClue(2323, "accepts telnet logins on 2323 (a cheap gadget)"),
        PortClue(53, "answers DNS (a router or a DNS filter such as a Pi-hole)"),
    ).associateBy { it.port }

    /** The clues of [open], most telling first (those that name a kind). */
    fun of(open: Collection<Int>): List<PortClue> =
        open.distinct().sorted().mapNotNull { clues[it] }.sortedBy { if (it.kind != null) 0 else 1 }

    /** The kind the ports point to when they agree, else null. An iPhone sync port outweighs AirPlay. */
    fun kindOf(open: Collection<Int>): HostKind? {
        if (62078 in open) return HostKind.PHONE
        val kinds = of(open).mapNotNull { it.kind }.distinct()
        return kinds.singleOrNull()
    }
}

/**
 * "Look closer" (RJ, 2026-10-11): one device the user tapped, asked directly. What is kept is [Summary] only (the kind,
 * maker, a few words it says about itself, its open ports, its names and the system hint), never the greetings as
 * received. Plain JVM, unit-tested.
 */
object LookCloser {
    /** Events stream of the kept summaries, one row per look, subject [subject] (network tag and address). */
    const val LEDGER = "homenet.closer"
    const val EVENT = "closer"
    const val MAX_ROWS = 2_000

    /** What one look found, as kept. */
    data class Summary(
        val at: Long,
        val kind: HostKind,
        val vendor: String?,
        /** A few words the device says about itself (a page title, a model, a server name). */
        val says: String?,
        val openPorts: List<Int>,
        val names: List<String>,
        val os: OsHint?,
    ) {
        fun encode(): String = buildString {
            append("at=").append(at)
            append(";kind=").append(kind.label)
            vendor?.let { append(";vendor=").append(escape(it)) }
            says?.let { append(";says=").append(escape(it)) }
            append(";ports=").append(LanKeys.portList(openPorts))
            if (names.isNotEmpty()) append(";names=").append(names.joinToString(",") { escape(it) })
            os?.let { append(";os=").append(it.slug) }
        }

        companion object {
            fun parse(text: String): Summary? {
                val fields = text.split(';').mapNotNull { f ->
                    val i = f.indexOf('=')
                    if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
                }.toMap()
                val at = fields["at"]?.toLongOrNull() ?: return null
                return Summary(
                    at = at,
                    kind = HostKind.byLabel(fields["kind"]),
                    vendor = fields["vendor"]?.let(::unescape),
                    says = fields["says"]?.let(::unescape),
                    openPorts = LanKeys.ports(fields["ports"]),
                    names = fields["names"].orEmpty().split(',').filter { it.isNotEmpty() }.mapNotNull(::unescape),
                    os = OsHint.bySlug(fields["os"]),
                )
            }
        }
    }

    /** The row subject for a device: the network's tag and its address, so another network's 192.168.1.5 is another device. */
    fun subject(networkTag: String, ip: String): String = "$networkTag|$ip"

    fun ipOf(subject: String, networkTag: String): String? = subject.removePrefix("$networkTag|").takeIf { subject.startsWith("$networkTag|") && it.isNotEmpty() }

    /** Everything one look learned, live. The greetings are shown on the screen and dropped; [summary] is what is kept. */
    data class Findings(
        val open: List<Int> = emptyList(),
        /** Ports that refused: something is there, without that service. */
        val refused: List<Int> = emptyList(),
        val httpTitle: String? = null,
        val httpServer: String? = null,
        val authRealm: String? = null,
        val redirect: String? = null,
        val cert: CertFacts? = null,
        val ssh: String? = null,
        val rtspServer: String? = null,
        val banner: String? = null,
        val netbiosName: String? = null,
        val workgroup: String? = null,
        val mdnsName: String? = null,
        val routerName: String? = null,
        val ttl: Int? = null,
        /** Names and maker the scan already knew (mDNS, SSDP, router): they count too. */
        val known: List<String> = emptyList(),
    ) {
        val os: OsHint? get() = ttl?.let(OsHint::fromTtl)

        /** Every name the device answered to, the computer name first. */
        val names: List<String> get() = listOfNotNull(netbiosName, mdnsName, routerName).distinct()

        /** Everything it said about itself, for the maker and kind words. */
        private val texts: List<String>
            get() = listOfNotNull(httpTitle, httpServer, authRealm, cert?.commonName, cert?.organization, ssh, rtspServer, banner) + names + known

        val vendor: String? get() = VendorHints.vendorOf(*texts.toTypedArray())

        /** The kind its words and ports point to; the ports decide only when the words say nothing. */
        val kind: HostKind
            get() {
                val fromWords = HostKinds.infer(HostEvidence(names = texts, openPorts = open.toSet()))
                if (fromWords != HostKind.UNKNOWN) return fromWords
                return PortClues.kindOf(open) ?: if (os == OsHint.WINDOWS) HostKind.COMPUTER else HostKind.UNKNOWN
            }

        /** The few words worth keeping: the page title, else the certificate's name, else a server name. */
        val says: String? get() = (httpTitle ?: cert?.commonName ?: rtspServer ?: httpServer ?: authRealm)?.let { Greetings.clean(it, 60) }

        fun summary(at: Long): Summary = Summary(at, kind, vendor, says, open.sorted(), names.take(4), os)
    }

    private const val SPECIAL = "%,;="

    fun escape(text: String): String = buildString {
        for (c in text) if (c in SPECIAL || c.code < 0x20) append('%').append("%02x".format(c.code)) else append(c)
    }

    fun unescape(text: String): String? {
        if ('%' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            if (i + 3 > text.length) return null
            out.append(text.substring(i + 1, i + 3).toIntOrNull(16)?.toChar() ?: return null)
            i += 3
        }
        return out.toString()
    }
}
