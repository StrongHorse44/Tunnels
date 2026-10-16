package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import io.github.stronghorse44.tunnels.lan.BindGuard
import io.github.stronghorse44.tunnels.lan.GateTexts
import io.github.stronghorse44.tunnels.lan.HttpLite
import io.github.stronghorse44.tunnels.lan.HttpResponse
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.LanScope
import io.github.stronghorse44.tunnels.lan.PortMappings
import io.github.stronghorse44.tunnels.lan.Purpose
import io.github.stronghorse44.tunnels.store.ObservationEntity
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * One plain-http exchange with [address]:[port] on a socket bound to the confirmed network: sends [request] as it is
 * and reads the answer, capped at [HttpLite.MAX_BODY]. Null for a lost network, no answer or no status line.
 */
internal fun httpExchange(guard: BindGuard, address: InetAddress, port: Int, request: String, timeoutMs: Int): HttpResponse? = try {
    Socket().use { socket ->
        if (!guard.bindTcp(socket)) return null
        socket.connect(InetSocketAddress(address, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.UTF_8)); flush() }
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        val input = socket.getInputStream()
        while (buffer.size() < HttpLite.MAX_BODY + 8192) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        HttpLite.parseResponse(buffer.toString("UTF-8"))
    }
} catch (_: Exception) {
    null
}

/**
 * "Close it" on a port a device opened to the internet (RJ, 2026-10-11): asks the router, over UPnP, to remove that one
 * opening. The first time Tunnels changes something on the router, so it is careful: only when the gate confirms, with a
 * fresh check after the second tap, that the phone is on the network of the last scan and that its router gives the
 * UPnP id that was confirmed (audit L8), only to the gateway's own port-mapping service that scan found, and only for an
 * opening that scan listed. The device that asked may open it again while UPnP is on; the guide says how to stop that.
 */
class RouterOpenings(context: Context, private val gate: NetworkGate) {
    private val app = context.applicationContext

    /** Openings the user tapped Close it on once, by finding subject, with when: a second tap within [CONFIRM_MS] closes. */
    private val armed = ConcurrentHashMap<String, Long>()

    /**
     * The finding's Close it: the first tap says what it will do and asks for a second; the second, within a minute,
     * closes. A one-line result either way.
     */
    suspend fun closeFromFinding(subject: String): String {
        val (client, protocol, port) = PortMappings.parseSubject(subject) ?: return NOT_LISTED
        val now = System.currentTimeMillis()
        val first = armed[subject]
        if (first == null || now - first > CONFIRM_MS) {
            armed[subject] = now
            return "Tap Close it again within a minute to ask your router to stop forwarding $protocol $port from the internet to $client."
        }
        armed.remove(subject)
        return close(subject)
    }

    /** Asks the router to remove the opening a finding [subject] names. Answers a one-line result; sends nothing it cannot verify. */
    suspend fun close(subject: String): String = withContext(Dispatchers.IO) {
        try {
            val (client, protocol, port) = PortMappings.parseSubject(subject) ?: return@withContext NOT_LISTED
            // A fresh check of the router's identity, after the second tap (audit L8): Close it needs its UPnP id itself.
            val decision = gate.admit(Purpose.CLOSE_IT)
            if (decision !is GateDecision.Allowed) return@withContext GateTexts.closeRefusal((decision as GateDecision.Refused).reason)
            if (decision.identity != Identity.ROUTER) return@withContext GateTexts.CLOSE_NO_UPNP
            val state = decision.state
            val network = state.network ?: return@withContext "The phone is not on Wi-Fi."
            val router = latestRouter() ?: return@withContext "Scan this network first, then tap Close it."
            if (router.tag == null) return@withContext "Scan this network first, then tap Close it."
            if (router.tag != decision.fingerprint.prefixTag) return@withContext "The last scan was of another network: scan this one, then tap Close it."
            val mapping = PortMappings.parse(router.mappings).firstOrNull { it.client == client && it.protocol == protocol && it.externalPort == port }
                ?: return@withContext NOT_LISTED
            val gateway = state.gateway?.hostAddress ?: return@withContext "No router address is known: reconnect to your Wi-Fi and scan again."
            val target = router.control?.let(HttpLite::parseUrl)?.takeIf { it.host == gateway }
                ?: return@withContext "The router did not offer its port-mapping service at the last scan: remove it on the router's admin page."
            val serviceType = router.service ?: return@withContext "The router did not offer its port-mapping service at the last scan: remove it on the router's admin page."
            val address = LanScope.parseLiteral(gateway)?.takeIf { LanScope.accepts(it, state.prefixes, state.prefixes.map { p -> p.first }) }
                ?: return@withContext "The router's address is not inside this network, so Tunnels does not ask it."
            val guard = BindGuard(NetworkBinder(network, VpnFallthroughCheck(app, state)))
            val response = httpExchange(guard, address, target.port, PortMappings.deleteRequest(target, serviceType, mapping), TIMEOUT_MS)
            PortMappings.closeResult(mapping, response?.status, response?.body?.let(PortMappings::errorCode))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            "Tunnels could not ask the router. Remove it on the router's admin page."
        }
    }

    /** What the newest Home network snapshot says about the router: its network, its openings and its port-mapping service. */
    private class RouterSnapshot(val tag: String?, val mappings: String?, val control: String?, val service: String?)

    private suspend fun latestRouter(): RouterSnapshot? {
        val dao = TunnelsStore.get(app).dao
        val id = dao.latestSnapshotIdFor(LanKeys.TUNNEL_ID, null) ?: return null
        val rows = dao.observations(id, LanKeys.TUNNEL_ID).map(ObservationEntity::toModel)
        val router = rows.filter { it.subject == LanKeys.SUBJECT_ROUTER }
        val summary = rows.filter { it.subject == LanKeys.SUBJECT_SUMMARY }
        return RouterSnapshot(
            tag = LanKeys.value(summary, LanKeys.SCAN_NETWORK),
            mappings = LanKeys.value(router, LanKeys.ROUTER_PORT_MAPPINGS),
            control = LanKeys.value(router, LanKeys.ROUTER_IGD_CONTROL),
            service = LanKeys.value(router, LanKeys.ROUTER_IGD_SERVICE),
        )
    }

    companion object {
        const val CONFIRM_MS = 60_000L
        private const val TIMEOUT_MS = 4_000
        private const val NOT_LISTED = "The last scan did not list that opening: scan again, then tap Close it."
    }
}
