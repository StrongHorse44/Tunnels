package io.github.stronghorse44.tunnels.homenet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import io.github.stronghorse44.tunnels.lan.DeviceCensus
import io.github.stronghorse44.tunnels.lan.DeviceIdentity
import io.github.stronghorse44.tunnels.lan.HostEvidence
import io.github.stronghorse44.tunnels.lan.HostKinds
import io.github.stronghorse44.tunnels.lan.LanGuides
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanRules
import io.github.stronghorse44.tunnels.lan.LanSummary
import io.github.stronghorse44.tunnels.lan.MdnsTypes
import io.github.stronghorse44.tunnels.lan.PortCatalog
import io.github.stronghorse44.tunnels.lan.ResolverScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home network: which devices are on the Wi-Fi the user confirmed as theirs, which doors they leave
 * open, and whether the router accepts UPnP port mappings or rewrites DNS failures. Talks to the local
 * network only, and only during a scan the user starts after the own-network gate. The gate tells
 * networks apart by their fingerprint (gateway, DHCP, DNS, prefix), because Android hides the SSID from
 * apps without a location permission; see NetworkFingerprint.
 */
class HomeNetworkTunnel(private val context: Context) : TunnelModule, TunnelUi {
    override val id: String = LanKeys.TUNNEL_ID

    // The own-network gate identifies a Wi-Fi by its fingerprint (gateway, DHCP, DNS, prefix), so no
    // runtime permission is needed (rule #6). The SSID is shown only when Android shares it anyway.
    override val requiredPermissions: List<PermissionSpec> = emptyList()

    override val rules: List<FindingRule> = LanRules.all

    val gate = NetworkGate(context)
    private val scanner = LanScanner(context)

    /** The device census's reads and writes in the encrypted store. */
    val census = CensusStore(context)

    /** Gateway of the last allowed scan, for the "Open router admin" action. */
    @Volatile private var lastGateway: String? = null

    override suspend fun scan(progress: ScanProgress): List<Observation> {
        val started = System.nanoTime()
        // The gate reads the encrypted store.
        val decision = withContext(Dispatchers.IO) { gate.check() }
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
        if (!result.scanned) {
            // Nothing was sent (no confirmed prefix to stay inside): not a scan, so no snapshot with zero hosts
            // that would reset the new-device baseline.
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_UNCONFIRMED)
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE_REASON, LanKeys.REASON_NETWORK_UNKNOWN)
            return out
        }
        val gateway = result.router.gateway
        lastGateway = gateway

        // The table holds at most MAX_HOSTS hosts besides the gateway, which is reserved outside that cap.
        val hosts = result.hosts.values.sortedBy { LanSummary.ipSortKey(it.ip) }.take(LanScanner.MAX_HOSTS + 1)
        val networkHash = allowed.fingerprint.hash
        val hostIds = HashMap<String, List<String>>()
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
                val name = DeviceIdentity.canonicalName(host.names.toList()) ?: if (host.ip == gateway) result.router.description?.label else null
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
                if (host.ip != gateway) {
                    // Open ports are never part of an identity (timeouts make them flicker), and the kind is inferred from
                    // them, so the shape token gets the kind as it reads without them.
                    val identityKind = HostKinds.infer(evidence.copy(openPorts = emptySet()))
                    val ids = DeviceIdentity.tokens(networkHash, host, identityKind, vendor)
                    if (ids.isNotEmpty()) {
                        hostIds[host.ip] = ids
                        add(host.ip, LanKeys.HOST_IDS, LanKeys.list(ids))
                    }
                }
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
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_DNS_LOCAL, triState(router.dnsScope?.let { it != ResolverScope.OFF_LAN }))
            add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_PRIVATE_DNS, triState(router.privateDns))
            // No record means the gateway was never probed (outside the scope, or the host cap): say nothing rather than "none".
            result.hosts[gateway]?.let { add(LanKeys.SUBJECT_ROUTER, LanKeys.ROUTER_OPEN_PORTS, LanKeys.portList(it.openPorts)) }
        }

        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_GATE, LanKeys.GATE_CONFIRMED)
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_NETWORK, allowed.fingerprint.prefixTag)
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_TOTAL, result.hosts.size.toString())
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.HOSTS_RISKY, risky.toString())
        add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_DURATION, ((System.nanoTime() - started) / 1_000_000_000L).toString())
        if (result.probesSkipped > 0) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_PROBES_SKIPPED, result.probesSkipped.toString())
        if (result.droppedOutOfScope > 0) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_DROPPED_OUT_OF_SCOPE, result.droppedOutOfScope.toString())
        if (result.partial.isNotEmpty()) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_PARTIAL, LanKeys.list(result.partial))
        if (result.linkLocalOnly > 0) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_LINK_LOCAL_ONLY, result.linkLocalOnly.toString())
        if (result.overCap > 0) add(LanKeys.SUBJECT_SUMMARY, LanKeys.SCAN_OVER_CAP, result.overCap.toString())

        // The census step: only bookkeeping on the replies already received, plus reads and writes in the encrypted
        // store. A store that cannot be read gives "unavailable" and no list, so nothing is judged (never "all known").
        val list = readCensus(allowed.fingerprint.prefixTag)
        if (list == null) {
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, DeviceCensus.STATE_UNAVAILABLE)
        } else {
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_STATE, list.state)
            add(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_KNOWN, LanKeys.list(list.known.sorted()))
            if (list.state == DeviceCensus.STATE_SET) {
                val unknown = hosts.count { it.ip != gateway && !DeviceCensus.isListed(hostIds[it.ip].orEmpty(), list.known) }
                add(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_UNKNOWN, unknown.toString())
            }
            if (list.full) add(LanKeys.SUBJECT_SUMMARY, LanKeys.CENSUS_FULL, LanKeys.TRUE)
        }
        return out
    }

    /** The list for this network, or null when the store could not be read. */
    private suspend fun readCensus(tag: String): DeviceCensus.Census? = withContext(Dispatchers.IO) {
        try {
            census.census(tag)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun triState(value: Boolean?): String = when (value) {
        true -> LanKeys.TRUE
        false -> LanKeys.FALSE
        null -> LanKeys.UNKNOWN
    }

    override fun actionsFor(draft: FindingDraft): List<FindingAction> {
        val ports = portsInParentheses.findAll(draft.evidence).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
        val actions = mutableListOf<FindingAction>()
        // The census actions only change the list in the encrypted store; the panel updates at the next scan.
        when (draft.kind) {
            LanRules.UNKNOWN_DEVICE ->
                if (DeviceCensus.parsePrimary(draft.subject) != null) {
                    actions += FindingAction.Perform(LanGuides.MINE_LABEL) { withContext(Dispatchers.IO) { census.ack(draft.subject) } }
                }
            LanRules.CENSUS_NOT_SET_UP ->
                DeviceCensus.parseTag(draft.subject)?.let { tag ->
                    actions += FindingAction.Perform(LanGuides.ALL_MINE_LABEL) { withContext(Dispatchers.IO) { census.setup(tag) } }
                }
        }
        actions += FindingAction.Perform(LanGuides.FIX_LABEL) { LanGuides.fix(draft.kind, ports) }
        if (LanGuides.wantsRouterAdmin(draft.kind)) {
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
        HomeNetPanel(state, actions, gate, census)
    }

    companion object {
        private val portsInParentheses = Regex("\\((\\d{1,5})\\)")
    }
}
