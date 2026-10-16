package io.github.stronghorse44.tunnels.homenet

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stronghorse44.tunnels.lan.ConfirmedNetworkCodec
import io.github.stronghorse44.tunnels.lan.GateTexts
import io.github.stronghorse44.tunnels.lan.LanKeys
import io.github.stronghorse44.tunnels.lan.NetworkFingerprint
import io.github.stronghorse44.tunnels.lan.NetworkRecordCodec
import io.github.stronghorse44.tunnels.lan.Purpose
import io.github.stronghorse44.tunnels.lan.RouterTokens
import io.github.stronghorse44.tunnels.model.ScanProgress
import io.github.stronghorse44.tunnels.store.TunnelsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey

/**
 * Spec section 7, tests 1 to 7: the Keystore key, the gate with a fake router and the real encrypted store, address only,
 * the migration from v1, a key change, a scan and Close it refused on a mismatch, and the real probe on the emulator.
 * The gate's decision after the address step ([NetworkGate.decide]) is tested on any emulator; the tests that need the
 * emulator's own Wi-Fi are skipped (and say so) when it has none.
 */
@RunWith(AndroidJUnit4::class)
class RouterIdentityGateTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: TunnelsStore get() = TunnelsStore.get(context)

    /** A router whose answers the test sets; counts how often it was asked. */
    private class FakeRouter(var seen: SeenRouter) : RouterIdentitySource {
        var calls = 0
        override suspend fun probe(state: WifiState): SeenRouter {
            calls++
            return seen
        }
    }

    private val uuidA = "3a2f9c00-1111-4b4b-8c8c-0123456789ab"
    private val uuidC = "00112233-4455-6677-8899-aabbccddeeff"
    private val keyB = "ab".repeat(32)
    private val keyD = "cd".repeat(32)
    private fun router(uuids: List<String> = emptyList(), spki: String? = null) = SeenRouter(uuids, spki, probed = true, lost = false)

    private val home = NetworkFingerprint.of("192.168.77.1", "192.168.77.1", listOf("192.168.77.1"), "192.168.77.0/24")!!
    private val offline = WifiState.OFFLINE
    private val tokens = RouterTokens(HomenetKey()::mac)

    @Before
    fun clean() {
        assertTrue(NetworkGate(context).forgetAll())
    }

    @After
    fun cleanAfter() {
        NetworkGate(context).forgetAll()
    }

    // ---- 1 ---------------------------------------------------------------------------------------------

    @Test
    fun theKeyIsMadeOnceStaysInTheKeystoreAndCannotBeExported() {
        val first = HomenetKey()
        val token = RouterTokens(first::mac).net(home)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias(HomenetKey.ALIAS))
        val second = HomenetKey()
        assertEquals("two instances, one key", token, RouterTokens(second::mac).net(home))
        assertTrue(Regex("[0-9a-f]{8}").matches(first.keyId()))
        assertEquals(first.keyId(), second.keyId())
        val key = first.key()
        assertNull("the key material never leaves the keystore", key.encoded)
        assertEquals(key, ks.getKey(HomenetKey.ALIAS, null) as SecretKey)
    }

    // ---- 2 ---------------------------------------------------------------------------------------------

    @Test
    fun aConfirmedRouterIsRecognisedAndAnotherIsRefused() = runBlocking<Unit> {
        val fake = FakeRouter(router(listOf(uuidA), keyB))
        val gate = NetworkGate(context, identity = fake)
        assertEquals(ConfirmOutcome.Confirmed(upnp = true, tls = true), gate.confirmOn(offline, home) { true })
        assertEquals(NetworkStatus.ROUTER, gate.status(home))
        val row = store.setting(NetworkRecordCodec.KEY)!!
        assertTrue("only keyed tokens are stored", row.contains(tokens.upnp(uuidA)) && row.contains(tokens.tls(keyB)))
        assertFalse(row.contains(uuidA) || row.contains(keyB) || row.contains("192.168"))

        fake.seen = router(listOf(uuidA))
        val allowed = gate.decide(offline, home, Purpose.SCAN) { true }
        assertEquals(GateDecision.Allowed(offline, home, Identity.ROUTER), allowed)

        fake.seen = router(listOf(uuidC))
        assertEquals(GateDecision.Refused(LanKeys.REASON_ROUTER_MISMATCH, offline), gate.decide(offline, home, Purpose.SCAN) { true })

        fake.seen = router()
        assertEquals(GateDecision.Refused(LanKeys.REASON_ROUTER_SILENT, offline), gate.decide(offline, home, Purpose.SCAN) { true })

        fake.seen = router(listOf(uuidA), keyD)
        assertTrue(gate.decide(offline, home, Purpose.SCAN) { true } is GateDecision.Allowed)
        val refreshed = store.setting(NetworkRecordCodec.KEY)!!
        assertTrue("the new admin key is kept", refreshed.contains(tokens.tls(keyD)))
        assertFalse(refreshed.contains(tokens.tls(keyB)))

        fake.seen = router(listOf(uuidA), keyD)
        assertEquals(GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, offline), gate.decide(offline, home, Purpose.SCAN) { false })
        fake.seen = SeenRouter(listOf(uuidA), keyD, probed = true, lost = true)
        assertEquals(GateDecision.Refused(LanKeys.REASON_NETWORK_UNKNOWN, offline), gate.decide(offline, home, Purpose.SCAN) { true })

        // Close it needs the UPnP id itself: the admin key alone is not enough.
        fake.seen = router(spki = keyD)
        assertTrue(gate.decide(offline, home, Purpose.SCAN) { true } is GateDecision.Allowed)
        assertEquals(GateDecision.Refused(LanKeys.REASON_NO_UPNP_MATCH, offline), gate.decide(offline, home, Purpose.CLOSE_IT) { true })
    }

    @Test
    fun aRouterThatGivesNothingSavesNothing() = runBlocking<Unit> {
        val gate = NetworkGate(context, identity = FakeRouter(router()))
        assertEquals(ConfirmOutcome.RouterSilent, gate.confirmOn(offline, home) { true })
        assertEquals(NetworkStatus.NOT_CONFIRMED, gate.status(home))
        assertEquals(ConfirmOutcome.WifiChanged, NetworkGate(context, identity = FakeRouter(router(listOf(uuidA)))).confirmOn(offline, home) { false })
        assertEquals(NetworkStatus.NOT_CONFIRMED, gate.status(home))
    }

    // ---- 3 ---------------------------------------------------------------------------------------------

    @Test
    fun addressOnlyAsksBeforeEveryScanAndNeverClosesAnything() = runBlocking<Unit> {
        val fake = FakeRouter(router(listOf(uuidA), keyB))
        val gate = NetworkGate(context, identity = fake)
        assertEquals(ConfirmOutcome.AddressOnly, gate.saveAddressOnly(home))
        assertEquals(NetworkStatus.ADDRESS_ONLY, gate.status(home))
        val askFirst = GateDecision.Refused(LanKeys.REASON_ADDRESS_ONLY, offline)
        assertEquals(askFirst, gate.decide(offline, home, Purpose.SCAN) { true })
        gate.permitAddressOnly(home)
        assertEquals(GateDecision.Allowed(offline, home, Identity.ADDRESS_ONLY), gate.decide(offline, home, Purpose.SCAN) { true })
        assertEquals("one shot", askFirst, gate.decide(offline, home, Purpose.SCAN) { true })
        gate.permitAddressOnly(home)
        assertEquals(GateDecision.Allowed(offline, home, Identity.ADDRESS_ONLY), gate.decide(offline, home, Purpose.LOOK_CLOSER) { true })
        gate.permitAddressOnly(home)
        assertEquals(GateDecision.Refused(LanKeys.REASON_NO_UPNP_MATCH, offline), gate.decide(offline, home, Purpose.CLOSE_IT) { true })
        assertEquals("nothing is ever sent to an address-only router by the gate", 0, fake.calls)
    }

    // ---- 4 ---------------------------------------------------------------------------------------------

    @Test
    fun anOldConfirmationAsksOnceMoreAndScansNothingUntilThen() = runBlocking<Unit> {
        val fake = FakeRouter(router(listOf(uuidA)))
        val gate = NetworkGate(context, identity = fake)
        store.putSetting(ConfirmedNetworkCodec.KEY, ConfirmedNetworkCodec.encode(setOf(home.hash)))
        assertEquals(NetworkStatus.RECONFIRM, gate.status(home))
        assertEquals(GateDecision.Refused(LanKeys.REASON_RECONFIRM, offline), gate.decide(offline, home, Purpose.SCAN) { true })
        assertEquals("no probe before the user confirms", 0, fake.calls)
        val tag = home.prefixTag
        assertEquals(ConfirmOutcome.Confirmed(upnp = true, tls = false), gate.confirmOn(offline, home) { true })
        assertEquals(NetworkStatus.ROUTER, gate.status(home))
        assertEquals("the v1 line is gone", emptySet<String>(), ConfirmedNetworkCodec.decode(store.setting(ConfirmedNetworkCodec.KEY)))
        assertEquals("the device list's tag is unchanged (L8-Q5)", tag, home.prefixTag)
        assertEquals(home.hash.take(NetworkFingerprint.PREFIX_LENGTH), home.prefixTag)
    }

    // ---- 5 ---------------------------------------------------------------------------------------------

    @Test
    fun aRowUnderAnotherKeyIsKeyChanged() = runBlocking<Unit> {
        val gate = NetworkGate(context, identity = FakeRouter(router(listOf(uuidA))))
        store.putSetting(NetworkRecordCodec.KEY, "k=deadbeef\n${tokens.net(home)} a - -\n")
        assertEquals(NetworkStatus.KEY_CHANGED, gate.status(home))
        assertEquals(GateDecision.Refused(LanKeys.REASON_KEY_CHANGED, offline), gate.decide(offline, home, Purpose.SCAN) { true })
        assertEquals(ConfirmOutcome.Confirmed(upnp = true, tls = false), gate.confirmOn(offline, home) { true })
        assertTrue(store.setting(NetworkRecordCodec.KEY)!!.startsWith("k=${HomenetKey().keyId()}\n"))
        assertEquals(NetworkStatus.ROUTER, gate.status(home))
    }

    @Test
    fun aMalformedRowFailsClosedUntilForgetAll() = runBlocking<Unit> {
        val gate = NetworkGate(context, identity = FakeRouter(router(listOf(uuidA))))
        store.putSetting(NetworkRecordCodec.KEY, "not a row")
        assertEquals(NetworkStatus.UNAVAILABLE, gate.status(home))
        assertEquals(GateDecision.Refused(LanKeys.REASON_STORE_UNAVAILABLE, offline), gate.decide(offline, home, Purpose.SCAN) { true })
        assertEquals(ConfirmOutcome.Failed, gate.confirmOn(offline, home) { true })
        assertEquals("never silently wiped", "not a row", store.setting(NetworkRecordCodec.KEY))
        assertTrue(gate.forgetAll())
        assertEquals(NetworkStatus.NOT_CONFIRMED, gate.status(home))
    }

    // ---- 6 (on the emulator's own Wi-Fi) ----------------------------------------------------------------

    @Test
    fun aMismatchingRouterStopsTheScanAndCloseIt() = runBlocking<Unit> {
        val fake = FakeRouter(router(listOf(uuidA)))
        val gate = NetworkGate(context, identity = fake)
        val state = gate.current()
        val fp = state.fingerprint
        assumeTrue("the emulator has no Wi-Fi with a fingerprint and a prefix", state.onWifi && fp != null && state.prefixes.isNotEmpty())
        assertTrue(gate.confirmWithRouter(fp!!) is ConfirmOutcome.Confirmed)
        fake.seen = router(listOf(uuidC))
        val tunnel = HomeNetworkTunnel(context, gate)
        var reports = 0
        val obs = tunnel.scan { _, _, label -> reports++; assertEquals(LanScanner.STAGE_IDENTITY, label) }
        assertEquals("only the two gate observations", listOf(LanKeys.SCAN_GATE, LanKeys.SCAN_GATE_REASON), obs.map { it.key })
        assertEquals(LanKeys.GATE_UNCONFIRMED, LanKeys.value(obs, LanKeys.SCAN_GATE))
        assertEquals(LanKeys.REASON_ROUTER_MISMATCH, LanKeys.value(obs, LanKeys.SCAN_GATE_REASON))
        assertEquals("the identity stage only", 1, reports)
        assertEquals(GateTexts.CLOSE_MISMATCH, tunnel.openings.close("192.168.1.250 · TCP 8554 to the internet"))
        val look = tunnel.closer.look(state.gateway?.hostAddress ?: "10.0.2.2", emptyList()) { }
        assertEquals(GateTexts.ROUTER_MISMATCH, look.error)
        assertNull(look.findings)
    }

    @Test
    fun anOldConfirmationOfThisWifiScansNothing() = runBlocking<Unit> {
        val fake = FakeRouter(router(listOf(uuidA)))
        val gate = NetworkGate(context, identity = fake)
        val state = gate.current()
        val fp = state.fingerprint
        assumeTrue("the emulator has no Wi-Fi with a fingerprint and a prefix", state.onWifi && fp != null && state.prefixes.isNotEmpty())
        store.putSetting(ConfirmedNetworkCodec.KEY, ConfirmedNetworkCodec.encode(setOf(fp!!.hash)))
        val obs = HomeNetworkTunnel(context, gate).scan(ScanProgress.NONE)
        assertEquals(LanKeys.REASON_RECONFIRM, LanKeys.value(obs, LanKeys.SCAN_GATE_REASON))
        assertEquals(2, obs.size)
        assertEquals(0, fake.calls)
    }

    // ---- 7 ---------------------------------------------------------------------------------------------

    @Test
    fun theRealProbeAnswersWithinItsBudgetAndNeverThrows() = runBlocking<Unit> {
        val state = NetworkGate(context).current()
        val started = System.nanoTime()
        val seen = RouterIdentityProbe(context).probe(state)
        val ms = (System.nanoTime() - started) / 1_000_000
        // Counts only: never a UUID or a key.
        Log.i("RouterIdentityGateTest", "probe: onWifi=${state.onWifi} probed=${seen.probed} lost=${seen.lost} upnp=${seen.uuids.size} tls=${seen.spkiSha256 != null} in $ms ms")
        assertTrue("within 3.5 s ($ms ms)", ms < 3_500)
        assertTrue(seen.uuids.size <= 4)
        seen.spkiSha256?.let { assertTrue(Regex("[0-9a-f]{64}").matches(it)) }
    }
}
