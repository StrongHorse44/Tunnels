package io.github.stronghorse44.tunnels.homenet

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.lan.HostEvidence
import io.github.stronghorse44.tunnels.lan.HostKinds
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.lan.LanSummary
import io.github.stronghorse44.tunnels.lan.MdnsTypes
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.Ssdp
import io.github.stronghorse44.tunnels.lan.VendorHints
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.PermissionSpec
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.model.TunnelModule
import io.github.stronghorse44.tunnels.runtime.TunnelScreenActions
import io.github.stronghorse44.tunnels.runtime.TunnelScreenState
import io.github.stronghorse44.tunnels.runtime.TunnelUi

/**
 * Home network: which devices are on the Wi-Fi the user confirmed as theirs, which doors they leave
 * open, and whether the router accepts UPnP port mappings or rewrites DNS failures. Talks to the local
 * network only, and only during a scan the user starts after the own-network gate.
 */
class HomeNetworkTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = LanKeys.TUNNEL_ID

    override val requiredPermissions: List<PermissionSpec> = listOf(
        PermissionSpec(Manifest.permission.NEARBY_WIFI_DEVICES, "To know which Wi-Fi network you are on, so Tunnels only ever scans your own"),
    )

    override val rules: List<FindingRule> = LanRules.all

    val gate = NetworkGate(context)
    private val scanner = LanScanner(context)

    /** Gateway of the last allowed scan, for the "Open router admin" action. */
    @Volatile private var lastGateway: String? = null

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val started = System.nanoTime()
        val decision = gate.check()
        val out = ArrayList<Observation>(64)
        fun add(subject: String, key: String, value: String) {
            if (value.isNotEmpty()) out += Observation(id, subject, key, value)
        }
        if (decision is GateDecision.Refused) {
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_UNCONFIRMED)
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE_REASON, decision.reason)
            return out
        }
        val allowed = decision as GateDecision.Allowed
        val result = scanner.scan(allowed.state, progress)
        val gateway = result.router.gateway
        lastGateway = gateway

        val hosts = result.hosts.values.sortedBy { LanSummary.ipSortKey(it.ip) }.take(LanScanner.MAX_HOSTS)
        var risky = 0
        for (host in hosts) {
            try {
                val evidence = HostEvidence(
                    mdnsTypes = host.mdnsTypes.toSet(),
                    names = host.names.toList(),
                    ssdpServer = host.ssdpServer,
                    ssdpTypes = host.ssdpTypes.toSet(),
                    openPorts = host.openPorts.toSet(),
                    isGateway = host.ip == gateway,
                    models = host.models.toList(),
                )
                val kind = HostKinds.infer(evidence)
                val name = host.names.firstOrNull() ?: if (host.ip == gateway) result.router.description?.label else null
                val services = (host.mdnsTypes.map(MdnsTypes::shortName) + host.ssdpTypes.map(Ssdp::shortType)).distinct().take(12)
                val open = host.openPorts.toList()
                val riskyPorts = PortCatalog.riskyOf(open)
                if (riskyPorts.isNotEmpty()) risky++
                name?.let { add(host.ip, LanKeys.HOST_NAME, it) }
                add(host.ip, LanKeys.HOST_KIND, kind.label)
                add(host.ip, LanKeys.HOST_SERVICES, LanKeys.list(services))
                add(host.ip, LanKeys.HOST_OPEN_PORTS, LanKeys.portList(open))
                add(host.ip, LanKeys.HOST_RISKY, LanKeys.portList(riskyPorts))
                add(host.ip, LanKeys.HOST_UPNP, host.upnp.toString())
                val vendor = VendorHints.vendorOf(host.ssdpServer, *host.names.toTypedArray(), *host.models.toTypedArray())
                    ?: if (host.ip == gateway) result.router.description?.manufacturer else null
                vendor?.let { add(host.ip, LanKeys.HOST_VENDOR, it) }
            } catch (_: Exception) {
                add(host.ip, LanKeys.HOST_KIND, LanKeys.UNKNOWN)
            }
        }

        val router = result.router
        if (gateway != null) {
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_IP, gateway)
            router.description?.label?.let { add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_NAME, it) }
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_UPNP_IGD, router.upnpIgd)
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_HIJACK, router.dnsVerdict?.hijackValue ?: LanKeys.UNKNOWN)
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_IS_GATEWAY, triState(router.dnsIsGateway))
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_PRIVATE_DNS, triState(router.privateDns))
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_OPEN_PORTS, LanKeys.portList(result.hosts[gateway]?.openPorts.orEmpty()))
        }

        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED)
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_SSID, GateHashing.prefix(allowed.hash))
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_TOTAL, result.hosts.size.toString())
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_RISKY, risky.toString())
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_DURATION, ((System.nanoTime() - started) / 1_000_000_000L).toString())
        if (result.partial.isNotEmpty()) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_PARTIAL, LanKeys.list(result.partial))
        return out
    }

    private fun triState(value: Boolean?): String = when (value) {
        true -> LanKeys.TRUE
        false -> LanKeys.FALSE
        null -> LanKeys.UNKNOWN
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val ports = portsInParentheses.findAll(draft.evidence).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val actions = mutableListOf<FindingAction>(
            FindingAction.Perform(LanGuides.FIX_LABEL) { LanGuides.fix(draft.kind, ports) },
        )
        if (LanGuides.isRouterKind(draft.kind)) {
            actions += FindingAction.Perform(LanGuides.ROUTER_ADMIN_LABEL) { openRouterAdmin() }
        }
        actions += FindingAction.OpenSettings(Settings.ACTION_WIFI_SETTINGS, LanGuides.WIFI_SETTINGS_LABEL)
        return actions
    }

    /** Opens http://<gateway>/ in the browser. Returns a one-line result for the UI. */
    private fun openRouterAdmin(): String {
        val gateway = lastGateway ?: runCatching { gate.current().gateway?.hostAddress }.getOrNull()
            ?: return "No gateway known yet: connect to your Wi-Fi and scan first."
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$gateway/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opening http://$gateway/ in your browser."
        } catch (_: ActivityNotFoundException) {
            "No browser can open http://$gateway/."
        }
    }

    @Composable
    override fun Content(state: TunnelScreenState, actions: TunnelScreenActions) {
        HomeNetPanel(state, actions, gate)
    }

    companion object {
        private val portsInParentheses = Regex("\\((\\d{1,5})\\)")
    }
}
