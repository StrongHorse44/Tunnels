package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.system.ErrnoException
import android.system.OsConstants
import io.github.stronghorse44.tunnels.lan.AddressOnlyPermit
import io.github.stronghorse44.tunnels.lan.BindRefusal
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkBook
import io.github.stronghorse44.tunnels.lan.GateRecord
import io.github.stronghorse44.tunnels.lan.LanAddresses
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import io.github.stronghorse44.tunnels.lan.NetworkRecordBook
import io.github.stronghorse44.tunnels.lan.NetworkRecordCodec
import io.github.stronghorse44.tunnels.lan.Purpose
import io.github.stronghorse44.tunnels.lan.RecordMode
import io.github.stronghorse44.tunnels.lan.RouterIdentity
import io.github.stronghorse44.tunnels.lan.SaveResult
import io.github.stronghorse44.tunnels.lan.SeenTokens
import io.github.stronghorse44.tunnels.lan.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** The Wi-Fi the phone is on right now, as far as the gate and the scanner need to know. */
data class WifiState(
    /** The non-VPN Wi-Fi network, or null when the phone is not on Wi-Fi. */
    val network: Network?,
    val linkProperties: LinkProperties?,
    /** DHCP server from LinkProperties, else WifiManager's DhcpInfo; may be null. */
    val dhcpServer: InetAddress? = null,
    /** Gateway from DhcpInfo when the routes name none (rare). */
    val dhcpGateway: Inet4Address? = null,
    /**
     * Display only. Android redacts it for apps without location permission (see [NetworkFingerprint]); the
     * gate never depends on it.
     */
    val ssid: String? = null,
) {
    val onWifi: Boolean get() = network != null

    /** The IPv4 default gateway from the routes, else from DHCP, if any. */
    val gateway: Inet4Address?
        get() = linkProperties?.routes?.firstOrNull { it.hasGateway() && it.destination.prefixLength == 0 && it.gateway is Inet4Address }
            ?.gateway as? Inet4Address ?: dhcpGateway

    /** The IPv6 default gateway (the router's link-local address), used only when there is no IPv4 one. */
    val gateway6: Inet6Address?
        get() = linkProperties?.routes?.firstOrNull { it.hasGateway() && it.destination.prefixLength == 0 && it.gateway is Inet6Address }
            ?.gateway as? Inet6Address

    val dnsServers: List<InetAddress> get() = linkProperties?.dnsServers.orEmpty()

    val ownAddresses: Set<String> get() = linkProperties?.linkAddresses?.mapNotNull { it.address?.hostAddress }.orEmpty().toSet()

    /**
     * The phone's own link addresses with their prefix lengths. These define the confirmed network: every
     * discovered address must lie inside one of them before the scanner probes it (see LanScope), and the
     * addresses themselves are the phone's own, which are never probed.
     */
    val prefixes: List<Pair<InetAddress, Int>>
        get() = linkProperties?.linkAddresses?.mapNotNull { la -> la.address?.let { it to la.prefixLength } }.orEmpty()

    /** The IPv4 network of the phone's own address, else its first non-link-local IPv6 one. */
    val prefixNetwork: String?
        get() {
            val las = linkProperties?.linkAddresses.orEmpty()
            val v4 = las.firstOrNull { it.address is Inet4Address }
            if (v4 != null) return LanAddresses.network(v4.address, v4.prefixLength)
            val v6 = las.firstOrNull { it.address is Inet6Address && !it.address.isLinkLocalAddress }
            return v6?.let { LanAddresses.network(it.address, it.prefixLength) }
        }

    /** What the gate confirms and the scan tags; null when the network cannot be told apart from another. */
    val fingerprint: NetworkFingerprint?
        get() = if (!onWifi) null else NetworkFingerprint.of(
            gateway = (gateway ?: gateway6)?.hostAddress?.substringBefore('%'),
            dhcpServer = dhcpServer?.hostAddress?.substringBefore('%'),
            dnsServers = dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
            prefix = prefixNetwork,
            ssid = ssid,
        )

    companion object {
        val OFFLINE = WifiState(null, null)
    }
}

/** How the gate recognised the network of an allowed scan, look or Close it. */
enum class Identity { ROUTER, ADDRESS_ONLY }

/** The gate's verdict before a scan, a look or Close it. */
sealed interface GateDecision {
    data class Allowed(val state: WifiState, val fingerprint: NetworkFingerprint, val identity: Identity = Identity.ROUTER) : GateDecision

    /** [reason] is one of the LanKeys.REASON_* values. */
    data class Refused(val reason: String, val state: WifiState) : GateDecision
}

/** What the network card shows before anything is sent: the stored state of the current network. */
enum class NetworkStatus { NOT_CONFIRMED, RECONFIRM, KEY_CHANGED, ROUTER, ADDRESS_ONLY, UNAVAILABLE }

/** How "This is my network" (or "Confirm by address only") ended. */
sealed interface ConfirmOutcome {
    /** Saved; the router identified itself by its UPnP id ([upnp]) and/or its admin page key ([tls]). */
    data class Confirmed(val upnp: Boolean, val tls: Boolean) : ConfirmOutcome

    /** Saved as address only (the user's choice after [RouterSilent]). */
    data object AddressOnly : ConfirmOutcome

    /** The router identified itself in no way: nothing saved; the card offers "Confirm by address only". */
    data class RouterSilent(val asked: Boolean = true) : ConfirmOutcome

    /** The Wi-Fi changed (or went away) while Tunnels asked: nothing saved. */
    data object WifiChanged : ConfirmOutcome

    /** A VPN that Tunnels may not bypass blocks the Wi-Fi: nothing was sent and nothing saved (OQ197). */
    data object VpnBlocksWifi : ConfirmOutcome

    /** The cap of networks is reached: nothing saved. */
    data object Full : ConfirmOutcome

    /** Tunnels' key or encrypted store could not be used: nothing saved. */
    data object Failed : ConfirmOutcome
}

/**
 * Own-network gate: Tunnels scans only a Wi-Fi the user confirmed as theirs. A network is identified by its
 * [NetworkFingerprint] (gateway, DHCP server, DNS servers, prefix), not by its SSID, which Android hides from apps without
 * a location permission, **and by its router** (audit L8): the gateway's UPnP root-device id and its HTTPS admin key,
 * asked fresh before every scan, Look closer and Close it ([admit]), before anything else is sent. Confirmed networks are
 * keyed records in the encrypted settings table (`homenet.networks.v2`, [NetworkRecordBook], under [HomenetKey]); the old
 * address-only hashes (`homenet.confirmed_networks`, [ConfirmedNetworkBook]) are read only to ask for one more
 * confirmation, and an older build's plaintext preferences file is still moved and deleted by [migrate].
 *
 * The functions read the database: call them off the main thread. If the store or the key cannot be used the gate
 * refuses with [LanKeys.REASON_STORE_UNAVAILABLE]; it never falls back to the old rows or the old file.
 */
class NetworkGate(
    private val context: Context,
    private val book: ConfirmedNetworkBook = ConfirmedNetworkBook(StoreNetworkTable(context), PrefsNetworkFile(context)),
    private val records: NetworkRecordBook = NetworkRecordBook(StoreNetworkTable(context, NetworkRecordCodec.KEY), book, HomenetKey()::mac),
    private val identity: RouterIdentitySource = RouterIdentityProbe(context),
    clock: () -> Long = System::currentTimeMillis,
    /** True when a VPN Tunnels may not bypass refuses the bind to this Wi-Fi; device tests pass their own. */
    private val vpnBlocks: (WifiState) -> Boolean = ::bindRefusedByVpn,
) {
    private val permit = AddressOnlyPermit(clock)

    /** Reads the current Wi-Fi network; cheap binder calls, safe from the main thread. */
    fun current(): WifiState {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return WifiState.OFFLINE
        val network = wifiNetwork(cm) ?: return WifiState.OFFLINE
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull()
        val dhcp = runCatching { dhcpInfo() }.getOrNull()
        val dhcpServer = lp?.dhcpServerAddress ?: dhcp?.serverAddress?.takeIf { it != 0 }?.let(::ipv4)
        val dhcpGateway = dhcp?.gateway?.takeIf { it != 0 }?.let(::ipv4)
        val ssid = displaySsid(cm, network)
        return WifiState(network, lp, dhcpServer, dhcpGateway, ssid)
    }

    /** WifiManager's DHCP lease (ACCESS_WIFI_STATE only; no location fields). */
    @Suppress("DEPRECATION")
    private fun dhcpInfo() = context.getSystemService(WifiManager::class.java)?.dhcpInfo

    /** DhcpInfo packs IPv4 addresses little-endian. */
    private fun ipv4(packed: Int): Inet4Address? = runCatching {
        InetAddress.getByAddress(byteArrayOf(packed.toByte(), (packed shr 8).toByte(), (packed shr 16).toByte(), (packed shr 24).toByte())) as? Inet4Address
    }.getOrNull()

    /**
     * Best-effort SSID for the card only. Expected to be redacted ("<unknown ssid>") on API 31+ without
     * ACCESS_FINE_LOCATION (NEARBY_WIFI_DEVICES with neverForLocation would not change that, so the tunnel asks
     * for neither); if a device does share it, the card shows it next to the fingerprint.
     */
    @Suppress("DEPRECATION")
    private fun displaySsid(cm: ConnectivityManager, network: Network): String? {
        val fromCaps = runCatching { (cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo)?.ssid }.getOrNull()
        val fromManager = runCatching { context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
        return GateHashing.normalizeSsid(fromCaps) ?: GateHashing.normalizeSsid(fromManager)
    }

    /** The active network when it is plain Wi-Fi, else the first Wi-Fi network that is not a VPN. */
    private fun wifiNetwork(cm: ConnectivityManager): Network? {
        fun isPlainWifi(n: Network): Boolean {
            val c = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return false
            return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.takeIf(::isPlainWifi)?.let { return it }
        return allNetworks(cm).firstOrNull(::isPlainWifi)
    }

    /** Needed when a VPN is the active network: the Wi-Fi underneath is still listed here. */
    @Suppress("DEPRECATION")
    private fun allNetworks(cm: ConnectivityManager): List<Network> = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())

    /** Moves hashes an older build kept in plaintext preferences into the encrypted store and deletes that file. Idempotent. */
    fun migrate() = book.migrate()

    /** The stored state of [fingerprint]'s network; nothing is sent. */
    fun status(fingerprint: NetworkFingerprint): NetworkStatus = try {
        statusOf(records.lookup(fingerprint))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        NetworkStatus.UNAVAILABLE
    }

    private fun statusOf(found: GateRecord): NetworkStatus = when {
        !found.keyIdOk -> NetworkStatus.KEY_CHANGED
        found.record == null -> if (found.legacy) NetworkStatus.RECONFIRM else NetworkStatus.NOT_CONFIRMED
        found.record?.mode == RecordMode.ADDRESS -> NetworkStatus.ADDRESS_ONLY
        else -> NetworkStatus.ROUTER
    }

    /**
     * "This is my network" (also "Confirm", "It's my new router"): asks the router who it is, then reads the network again
     * (a changed network saves nothing). The router identified itself: saved as recognised by its router, replacing any
     * record the network had, and its old address-only line is removed. It did not: nothing saved, [ConfirmOutcome.RouterSilent].
     * The key is never recreated over a failure.
     */
    suspend fun confirmWithRouter(fingerprint: NetworkFingerprint): ConfirmOutcome = withContext(Dispatchers.IO) {
        val state = runCatching { current() }.getOrDefault(WifiState.OFFLINE)
        if (state.fingerprint?.hash != fingerprint.hash || state.prefixes.isEmpty()) return@withContext ConfirmOutcome.WifiChanged
        if (vpnBlocks(state)) return@withContext ConfirmOutcome.VpnBlocksWifi
        confirmOn(state, fingerprint) { sameNetwork(state, fingerprint) }
    }

    /** [confirmWithRouter] after the address step; [stillOn] reads the network again after the probe. */
    internal suspend fun confirmOn(state: WifiState, fingerprint: NetworkFingerprint, stillOn: () -> Boolean): ConfirmOutcome {
        val raw = probe(state)
        if (raw.lost || !stillOn()) return ConfirmOutcome.WifiChanged
        val seen = try {
            records.tokens.seen(raw.uuids, raw.spkiSha256, raw.probed, raw.lost)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return ConfirmOutcome.Failed
        }
        // [ConfirmOutcome.RouterSilent.asked] is false when nothing could be sent (no IPv4 router address inside the network).
        if (!seen.any) return ConfirmOutcome.RouterSilent(asked = raw.probed)
        return when (records.confirm(fingerprint, RecordMode.ROUTER, seen)) {
            SaveResult.SAVED -> ConfirmOutcome.Confirmed(upnp = seen.upnp.isNotEmpty(), tls = seen.tls != null)
            SaveResult.FULL -> ConfirmOutcome.Full
            SaveResult.FAILED -> ConfirmOutcome.Failed
        }
    }

    /** "Confirm by address only" (L8-Q2): saved only while the phone is still on [fingerprint]'s network. */
    suspend fun confirmAddressOnly(fingerprint: NetworkFingerprint): ConfirmOutcome = withContext(Dispatchers.IO) {
        val state = runCatching { current() }.getOrDefault(WifiState.OFFLINE)
        if (state.fingerprint?.hash != fingerprint.hash) return@withContext ConfirmOutcome.WifiChanged
        saveAddressOnly(fingerprint)
    }

    internal fun saveAddressOnly(fingerprint: NetworkFingerprint): ConfirmOutcome = when (records.confirm(fingerprint, RecordMode.ADDRESS, SeenTokens())) {
        SaveResult.SAVED -> ConfirmOutcome.AddressOnly
        SaveResult.FULL -> ConfirmOutcome.Full
        SaveResult.FAILED -> ConfirmOutcome.Failed
    }

    /** The user answered "Is this your own Wi-Fi?" with Scan (or Look closer): the next admission of this network, within a minute, may go ahead. */
    fun permitAddressOnly(fingerprint: NetworkFingerprint) {
        try {
            permit.grant(records.tokens.net(fingerprint))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The key cannot be used: the admission refuses as store-unavailable anyway.
        }
    }

    /** Forgets the network's record and its old line; false when the store could not be changed. */
    fun forget(fingerprint: NetworkFingerprint): Boolean = records.forget(fingerprint)

    /** Forgets every network (both rows emptied); also clears a row that could not be read. */
    fun forgetAll(): Boolean = records.forgetAll()

    /** How many networks are confirmed; 0 when the store cannot be read. */
    fun confirmedCount(): Int = records.count() ?: 0

    /**
     * Decides whether a scan, a look or Close it ([purpose]) may run right now. The address step and the stored record
     * first (nothing is sent); then, for a network recognised by its router, the identity probe to the gateway (spec
     * 2.2), with [onProbe] called just before it; then the verdict ([RouterIdentity.judge]). A match refreshes the stored
     * factors. Never throws but for cancellation.
     */
    suspend fun admit(purpose: Purpose, onProbe: () -> Unit = {}): GateDecision = withContext(Dispatchers.IO) {
        val state = runCatching { current() }.getOrDefault(WifiState.OFFLINE)
        if (!state.onWifi) return@withContext GateDecision.Refused(LanKeys.REASON_NO_WIFI, state)
        val fingerprint = state.fingerprint ?: return@withContext GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, state)
        // Scanning stays inside the link's prefixes; without any, there is nothing to stay inside.
        if (state.prefixes.isEmpty()) return@withContext GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, state)
        decide(state, fingerprint, purpose, onProbe) { sameNetwork(state, fingerprint) }
    }

    /** [admit] after the address step; [stillOn] reads the network again after the probe. */
    internal suspend fun decide(
        state: WifiState,
        fingerprint: NetworkFingerprint,
        purpose: Purpose,
        onProbe: () -> Unit = {},
        stillOn: () -> Boolean,
    ): GateDecision {
        // Last, so a phone that is not on a usable Wi-Fi never opens the store for this.
        val found = try {
            records.lookup(fingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return GateDecision.Refused(LanKeys.REASON_STORE_UNAVAILABLE, state)
        }
        val record = found.record
        // Before anything is sent (the probe, or an address-only scan): a VPN that refuses the bind blocks the Wi-Fi.
        if (found.keyIdOk && record != null && vpnBlocks(state)) return GateDecision.Refused(LanKeys.REASON_VPN_BLOCKS_WIFI, state)
        val asked = found.keyIdOk && record?.mode == RecordMode.ADDRESS && purpose != Purpose.CLOSE_IT && permit.consume(found.net)
        val seen = if (RouterIdentity.needsProbe(record, found.keyIdOk)) {
            onProbe()
            val raw = probe(state)
            val tokens = try {
                records.tokens.seen(raw.uuids, raw.spkiSha256, raw.probed, raw.lost)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return GateDecision.Refused(LanKeys.REASON_STORE_UNAVAILABLE, state)
            }
            if (stillOn()) tokens else tokens.copy(lost = true)
        } else {
            null
        }
        val verdict = RouterIdentity.judge(record, found.legacy, found.keyIdOk, seen, purpose, asked)
        return when (verdict) {
            Verdict.Match -> {
                // A failed refresh keeps the admission: the match was made against the stored record.
                if (record != null && seen != null) records.refresh(record, seen)
                GateDecision.Allowed(state, fingerprint, Identity.ROUTER)
            }
            Verdict.AddressOnly -> GateDecision.Allowed(state, fingerprint, Identity.ADDRESS_ONLY)
            else -> GateDecision.Refused(verdict.reason ?: LanKeys.REASON_NOT_CONFIRMED, state)
        }
    }

    private suspend fun probe(state: WifiState): SeenRouter = try {
        identity.probe(state)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // An unexpected failure inside the probe: something may have been sent, so it reads as a silent router, not "not asked".
        SeenRouter(emptyList(), null, probed = true, lost = false)
    }

    /** The phone is still on the same Wi-Fi with the same fingerprint as [state]. */
    private fun sameNetwork(state: WifiState, fingerprint: NetworkFingerprint): Boolean {
        val now = runCatching { current() }.getOrNull() ?: return false
        return now.network == state.network && now.fingerprint?.hash == fingerprint.hash
    }
}

/**
 * True when Android refuses to bind a socket to [state]'s Wi-Fi because a VPN Tunnels may not bypass covers it
 * (EPERM; [BindRefusal]). The socket is created and bound, never sent on. Any other failure (the network went away)
 * is not the VPN's and reads false; the probe and the scan then stop as before.
 */
internal fun bindRefusedByVpn(state: WifiState): Boolean {
    val network = state.network ?: return false
    return try {
        DatagramSocket().use { network.bindSocket(it) }
        false
    } catch (e: Throwable) {
        // Any throwable, an Error included, so the check never crashes a scan or a look; only EPERM reads as the VPN's.
        BindRefusal.isVpnRefusal(e, { (it as? ErrnoException)?.errno }, OsConstants.EPERM)
    }
}
