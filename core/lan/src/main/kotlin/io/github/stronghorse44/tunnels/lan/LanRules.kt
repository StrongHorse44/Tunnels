package io.github.stronghorse44.tunnels.lan

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the home_network tunnel. Pure functions over observations, unit-tested here. */
object LanRules {
    const val RISKY_SERVICE = "RISKY_SERVICE"
    const val UPNP_IGD_ENABLED = "UPNP_IGD_ENABLED"
    const val DNS_HIJACK = "DNS_HIJACK"
    const val NEW_HOST = "NEW_HOST"
    const val CAMERA_OPEN_WEB = "CAMERA_OPEN_WEB"

    /** Ports that make a camera reachable from a browser or video player. */
    val cameraWebPorts = setOf(80, 443, 554)

    /** State WARN per host: a login or file-sharing service anyone on the Wi-Fi can try. */
    val riskyService: FindingRule = Rules.perSubject(RISKY_SERVICE, Severity.WARN) { subject, obs ->
        if (!LanKeys.isHostSubject(subject)) return@perSubject null
        val risky = LanKeys.ports(LanKeys.value(obs, LanKeys.HOST_RISKY))
        if (risky.isEmpty()) return@perSubject null
        val who = LanKeys.value(obs, LanKeys.HOST_NAME)?.let { "$it accepts" } ?: "This device accepts"
        "$who connections for ${PortCatalog.describe(risky)}. Anyone on this Wi-Fi can try to log in or read files."
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

    /**
     * Sticky INFO: a host appeared after the first scan of this network. Skipped when the previous snapshot
     * had no hosts (the gate refused then, or this is a different network), since every host would look new.
     */
    val newHost: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val summaryChanged = ctx.diff.filter { it.key.subject == LanKeys.SUBJECT_SUMMARY }
        val freshBaseline = summaryChanged.any {
            (it is DiffEntry.Added && it.key.key == LanKeys.HOSTS_TOTAL) ||
                (it is DiffEntry.Changed && (it.key.key == LanKeys.SCAN_SSID || it.key.key == LanKeys.SCAN_GATE)) ||
                (it is DiffEntry.Added && it.key.key == LanKeys.SCAN_SSID)
        }
        if (freshBaseline) return@FindingRule emptyList()
        val bySubject = ctx.bySubject()
        ctx.diff.asSequence()
            .filterIsInstance<DiffEntry.Added>()
            .filter { it.key.key == LanKeys.HOST_KIND && LanKeys.isHostSubject(it.key.subject) }
            .map { added ->
                val subject = added.key.subject
                val obs = bySubject[subject].orEmpty()
                val name = LanKeys.value(obs, LanKeys.HOST_NAME)
                val kind = HostKind.byLabel(added.observation.value)
                val what = listOfNotNull(name, kind.takeIf { it != HostKind.UNKNOWN }?.label).joinToString(", ").ifEmpty { "unidentified device" }
                FindingDraft(ctx.tunnelId, subject, NEW_HOST, Severity.INFO, "A new device ($what) joined your network at $subject since the last scan.", sticky = true)
            }
            .toList()
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(dnsHijack, riskyService, cameraOpenWeb, upnpIgd, newHost)
}
