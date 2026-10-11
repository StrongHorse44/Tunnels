package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Finding rules of the home_network tunnel. Pure functions over observations, unit-tested here. */
object LanRules {
    const val RISKY_SERVICE = "RISKY_SERVICE"
    const val UPNP_IGD_ENABLED = "UPNP_IGD_ENABLED"
    const val DNS_HIJACK = "DNS_HIJACK"
    /** Retired as a rule (the census replaced it); kept so a finding stored by an older build still gets its actions. */
    const val NEW_HOST = "NEW_HOST"
    const val UNKNOWN_DEVICE = "UNKNOWN_DEVICE"
    const val CENSUS_NOT_SET_UP = "CENSUS_NOT_SET_UP"
    private const val SUBJECT_JOIN = " · "
    private const val PARTIAL_NETWORK = "network"
    private const val MAX_NAMED = 8
    const val CAMERA_OPEN_WEB = "CAMERA_OPEN_WEB"
    const val WATCHED_BACK = "WATCHED_BACK"
    const val WATCHED_CHANGED = "WATCHED_CHANGED"
    const val PORT_OPENED = "PORT_OPENED"

    /** Ports that make a camera reachable from a browser or video player. */
    val cameraWebPorts = setOf(80, 443, 554)

    /** Ports only Look closer tries: the scan never sees them, so a look is the only word on them. */
    private val closerOnlyPorts = PortCatalog.closerOnly.map { it.port }.toSet()

    /**
     * State WARN per host: a login or file-sharing service anyone on the Wi-Fi can try. Besides what the scan found, the
     * risky doors the last Look closer found among the ports only it tries (Android debug, the second telnet, the cheap
     * recorders' protocol; RJ, 2026-10-11), for as long as the look is kept or until a new look finds them closed.
     */
    val riskyService: FindingRule = Rules.perSubject(RISKY_SERVICE, Severity.WARN) { subject, obs ->
        if (!LanKeys.isHostSubject(subject)) return@perSubject null
        val scanned = LanKeys.ports(LanKeys.value(obs, LanKeys.HOST_RISKY))
        val look = LanKeys.value(obs, LanKeys.HOST_CLOSER)?.let(LookCloser.Summary::parse)
        val looked = look?.openPorts.orEmpty().filter { it in closerOnlyPorts && it in PortCatalog.risky }.distinct().sorted()
        val risky = (scanned + looked).distinct().sorted()
        if (risky.isEmpty()) return@perSubject null
        val who = LanKeys.value(obs, LanKeys.HOST_NAME)?.let { "$it accepts" } ?: "This device accepts"
        val foundBy = if (look == null || looked.isEmpty()) "" else
            " ${PortCatalog.describe(looked)}: found when you looked closer on ${Instant.ofEpochMilli(look.at).atZone(ZoneId.systemDefault()).toLocalDate()}."
        "$who connections for ${PortCatalog.describe(risky)}.$foundBy Anyone on this Wi-Fi can try to log in or read files."
    }

    /** State WARN: a camera serves a web page or video stream to the whole network. */
    val cameraOpenWeb: FindingRule = Rules.perSubject(CAMERA_OPEN_WEB, Severity.WARN) { subject, obs ->
        if (!LanKeys.isHostSubject(subject)) return@perSubject null
        if (LanKeys.value(obs, LanKeys.HOST_KIND) != HostKind.CAMERA.label) return@perSubject null
        val open = LanKeys.ports(LanKeys.value(obs, LanKeys.HOST_OPEN_PORTS)).filter { it in cameraWebPorts }
        if (open.isEmpty()) return@perSubject null
        val name = LanKeys.value(obs, LanKeys.HOST_NAME) ?: "This camera"
        "$name serves ${PortCatalog.describe(open)} to every device on the network. With a default or weak password, anyone here can watch it."
    }

    /**
     * State, one per opening: a device asked the router to open a port to the internet (UPnP; RJ, 2026-10-11). WARN when
     * it opens a risky door or reaches a camera ([PortMappings.isLoud]), NOTICE otherwise. Only enabled openings, and
     * only on a scan the gate confirmed.
     */
    val portOpened: FindingRule = FindingRule { ctx ->
        val bySubject = ctx.bySubject()
        val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
        if (LanKeys.value(summary, LanKeys.SCAN_GATE) != LanKeys.GATE_CONFIRMED) return@FindingRule emptyList()
        val mappings = PortMappings.parse(LanKeys.value(bySubject[LanKeys.SUBJECT_ROUTER].orEmpty(), LanKeys.ROUTER_PORT_MAPPINGS))
        mappings.filter { it.enabled }.distinctBy { PortMappings.subject(it) }.map { m ->
            val host = bySubject[m.client].orEmpty()
            val kind = LanKeys.value(host, LanKeys.HOST_KIND)?.let(HostKind::byLabel)
            val title = LanKeys.value(host, LanKeys.HOST_LABEL) ?: LanKeys.value(host, LanKeys.HOST_NAME) ?: LanKeys.value(host, LanKeys.HOST_VENDOR)
            val who = if (title != null) "$title (${m.client})" else "The device at ${m.client}"
            val label = m.description?.let { " for \"$it\"" }.orEmpty()
            val loud = PortMappings.isLoud(m, kind)
            val service = PortCatalog.byPort(m.internalPort)
            val reach = when {
                service != null && loud -> "Anyone on the internet can reach its ${service.service}: ${service.note}"
                kind == HostKind.CAMERA -> "Anyone on the internet can reach this camera on that port."
                else -> "Anyone on the internet can reach that port on it; if you do not know why it is open, close it."
            }
            val only = if (m.remoteHost.isNotEmpty()) " The router limits it to the internet address ${m.remoteHost}." else ""
            FindingDraft(
                ctx.tunnelId, PortMappings.subject(m), PORT_OPENED, if (loud) Severity.WARN else Severity.NOTICE,
                "$who asked your router to open ${PortMappings.ports(m)} to the internet$label (UPnP). $reach$only",
            )
        }
    }

    /** State NOTICE on the router: UPnP IGD port mapping is on. */
    val upnpIgd: FindingRule = Rules.perSubject(UPNP_IGD_ENABLED, Severity.NOTICE) { subject, obs ->
        if (subject != LanKeys.SUBJECT_ROUTER || LanKeys.value(obs, LanKeys.ROUTER_UPNP_IGD) != LanKeys.TRUE) return@perSubject null
        "Your router accepts UPnP port-mapping requests from any device on the LAN: any app or gadget can open a hole in your firewall without asking you."
    }

    /** State CRITICAL on the router: its resolver invents answers for names that do not exist. */
    val dnsHijack: FindingRule = Rules.perSubject(DNS_HIJACK, Severity.CRITICAL) { subject, obs ->
        if (subject != LanKeys.SUBJECT_ROUTER || LanKeys.value(obs, LanKeys.ROUTER_DNS_HIJACK) != LanKeys.TRUE) return@perSubject null
        "Your router's DNS answers for names that do not exist. It rewrites failed lookups, which is how ad injection and phishing redirects work; a compromised router does the same."
    }

    /** Discovery stages whose partial result can hide a listed device (ports and the router checks cannot). */
    private val discoveryStages = listOf("mdns", "ssdp", PARTIAL_NETWORK)

    private fun kindWord(kind: HostKind): String? = when (kind) {
        HostKind.ROUTER -> "router"
        HostKind.CAMERA -> "camera"
        HostKind.NAS -> "storage"
        HostKind.COMPUTER -> "computer"
        HostKind.PHONE -> "phone"
        HostKind.TV -> "TV"
        HostKind.SPEAKER -> "speaker"
        HostKind.PRINTER -> "printer"
        HostKind.IOT -> "smart-home device"
        HostKind.UNKNOWN -> null
    }

    /**
     * Sticky NOTICE, one per device: a host with no token on this network's list. Judged only when the gate confirmed the
     * scan and the list is `set`; the gateway is never judged. A host with no `host:ids` has no identity and is unknown
     * (fail closed): its subject is `unidentified · <address>` and it cannot be added. A partial discovery still judges,
     * and the evidence says so, because a listed device seen only in part may have lost the names it is known by.
     */
    val unknownDevice: FindingRule = FindingRule { ctx ->
        val bySubject = ctx.bySubject()
        val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
        if (LanKeys.value(summary, LanKeys.SCAN_GATE) != LanKeys.GATE_CONFIRMED) return@FindingRule emptyList()
        if (LanKeys.value(summary, LanKeys.CENSUS_STATE) != DeviceCensus.STATE_SET) return@FindingRule emptyList()
        val known = DeviceCensus.parseKnown(LanKeys.value(summary, LanKeys.CENSUS_KNOWN))
        val gateway = LanKeys.value(bySubject[LanKeys.SUBJECT_ROUTER].orEmpty(), LanKeys.ROUTER_IP)
        val incomplete = LanKeys.items(LanKeys.value(summary, LanKeys.SCAN_PARTIAL)).filter { it in discoveryStages }
        val partialNote = if (incomplete.isEmpty()) "" else
            " This scan was incomplete (${incomplete.joinToString(", ")}), so it may be a listed device seen only in part."
        bySubject.entries
            .filter { (subject, _) -> LanKeys.isHostSubject(subject) && subject != gateway }
            .sortedBy { (subject, _) -> LanSummary.ipSortKey(subject) }
            .mapNotNull { (ip, obs) ->
                val ids = LanKeys.items(LanKeys.value(obs, LanKeys.HOST_IDS)).filter(DeviceIdentity::isToken)
                if (DeviceCensus.isListed(ids, known)) return@mapNotNull null
                val primary = DeviceIdentity.primaryOf(ids)
                if (primary == null) {
                    return@mapNotNull FindingDraft(
                        ctx.tunnelId, "unidentified$SUBJECT_JOIN$ip", UNKNOWN_DEVICE, Severity.NOTICE,
                        "A device that is not on your list for this network is here at $ip, and it gave nothing to recognise it by, so it cannot be added to the list.$partialNote",
                        sticky = true,
                    )
                }
                val title = DeviceCensus.title(LanKeys.value(obs, LanKeys.HOST_NAME), LanKeys.value(obs, LanKeys.HOST_VENDOR))
                if (DeviceIdentity.isAddressToken(primary)) {
                    return@mapNotNull FindingDraft(
                        ctx.tunnelId, DeviceCensus.subject(title, primary), UNKNOWN_DEVICE, Severity.NOTICE,
                        "A device that is not on your list for this network is here at $ip. It gives nothing to recognise it by, " +
                            "so Mine adds it by its address: it stays yours while a device that announces nothing and opens no door " +
                            "is at that address. Reserve the address for it in your router.$partialNote",
                        sticky = true,
                    )
                }
                val kind = kindWord(HostKind.byLabel(LanKeys.value(obs, LanKeys.HOST_KIND)))
                val what = if (kind == null) title else "$title ($kind)"
                FindingDraft(
                    ctx.tunnelId, DeviceCensus.subject(title, primary), UNKNOWN_DEVICE, Severity.NOTICE,
                    "A device that is not on your list for this network is here: $what at $ip.$partialNote",
                    sticky = true,
                )
            }
    }

    /**
     * State NOTICE, one per network: the list was never set up (or was started again) and there are devices to put on it.
     * One finding for the whole network, not one per device, so a first scan does not bury the user.
     */
    val censusNotSetUp: FindingRule = FindingRule { ctx ->
        val bySubject = ctx.bySubject()
        val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
        if (LanKeys.value(summary, LanKeys.SCAN_GATE) != LanKeys.GATE_CONFIRMED) return@FindingRule emptyList()
        if (LanKeys.value(summary, LanKeys.CENSUS_STATE) != DeviceCensus.STATE_UNSET) return@FindingRule emptyList()
        val tag = LanKeys.value(summary, LanKeys.SCAN_NETWORK) ?: return@FindingRule emptyList()
        val gateway = LanKeys.value(bySubject[LanKeys.SUBJECT_ROUTER].orEmpty(), LanKeys.ROUTER_IP)
        val devices = bySubject.entries
            .filter { (subject, _) -> LanKeys.isHostSubject(subject) && subject != gateway }
            .sortedBy { (subject, _) -> LanSummary.ipSortKey(subject) }
        if (devices.isEmpty()) return@FindingRule emptyList()
        val named = devices.take(MAX_NAMED).joinToString(", ") { (ip, obs) ->
            LanKeys.value(obs, LanKeys.HOST_NAME) ?: LanKeys.value(obs, LanKeys.HOST_VENDOR) ?: ip
        }
        val more = if (devices.size > MAX_NAMED) " and ${devices.size - MAX_NAMED} more" else ""
        val count = if (devices.size == 1) "1 device" else "${devices.size} devices"
        listOf(
            FindingDraft(
                ctx.tunnelId, DeviceCensus.setupSubject(tag), CENSUS_NOT_SET_UP, Severity.NOTICE,
                "Tunnels has no list of the devices you accept on this network yet, so it cannot tell a stranger from your own gear. $count answered: $named$more.",
            ),
        )
    }

    /**
     * Sticky NOTICE, one per watched device and day: a device the user watches ([Watch]) came back to the network, or
     * opened or closed ports or changed its name since the last scan. The subject carries the device's key and the day,
     * `<title> · <key> · <yyyy-mm-dd>`, so news on another day is a new finding even after the last one was dismissed.
     */
    val watchedDevice: FindingRule = FindingRule { ctx ->
        val bySubject = ctx.bySubject()
        val summary = bySubject[LanKeys.SUBJECT_SUMMARY].orEmpty()
        if (LanKeys.value(summary, LanKeys.SCAN_GATE) != LanKeys.GATE_CONFIRMED) return@FindingRule emptyList()
        bySubject.entries
            .filter { (subject, _) -> LanKeys.isHostSubject(subject) }
            .sortedBy { (subject, _) -> LanSummary.ipSortKey(subject) }
            .mapNotNull { (ip, obs) ->
                val noted = Watch.parseObservation(LanKeys.value(obs, LanKeys.HOST_WATCH_EVENT)) ?: return@mapNotNull null
                val ids = LanKeys.items(LanKeys.value(obs, LanKeys.HOST_IDS)).filter(DeviceIdentity::isToken)
                val key = DeviceKeys.of(DeviceIdentity.primaryOf(ids), ip)
                val title = LanKeys.value(obs, LanKeys.HOST_LABEL)
                    ?: DeviceCensus.title(LanKeys.value(obs, LanKeys.HOST_NAME), LanKeys.value(obs, LanKeys.HOST_VENDOR))
                val subject = watchSubject(title, key, noted.day)
                when (val event = noted.event) {
                    Watch.Event.Back -> FindingDraft(
                        ctx.tunnelId, subject, WATCHED_BACK, Severity.NOTICE,
                        "$title, a device you watch, is back on this network at $ip. It was not here at the last scan.",
                        sticky = true,
                    )
                    is Watch.Event.Changed -> FindingDraft(
                        ctx.tunnelId, subject, WATCHED_CHANGED, Severity.NOTICE,
                        "$title, a device you watch, changed since the last scan: ${event.what.joinToString("; ")}. It is at $ip.",
                        sticky = true,
                    )
                }
            }
    }

    /** `<title> · <key> · <yyyy-mm-dd>`: the subject of a watched device's news. */
    fun watchSubject(title: String, key: String, day: Long): String = "$title$SUBJECT_JOIN$key$SUBJECT_JOIN${LocalDate.ofEpochDay(day)}"

    /** The device key in a [watchSubject], or null when the subject is not one. */
    fun watchKeyOf(subject: String): String? =
        subject.substringBeforeLast(SUBJECT_JOIN, "").substringAfterLast(SUBJECT_JOIN, "").takeIf(DeviceKeys::isKey)

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(dnsHijack, riskyService, cameraOpenWeb, portOpened, upnpIgd, unknownDevice, watchedDevice, censusNotSetUp)
}
