package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Spec section 6, tests 2, 3, 4 and 6: the decision table, the refresh rule, the SSDP reply filter and the tokens. */
class RouterIdentityTest {
    private fun t(c: Char) = c.toString().repeat(32)

    private val net = t('0')
    private val u1 = t('1')
    private val u2 = t('2')
    private val u3 = t('3')
    private val tA = t('a')
    private val tB = t('b')

    private val router = NetworkRecord(net, RecordMode.ROUTER, listOf(u1), tA)
    private val address = NetworkRecord(net, RecordMode.ADDRESS)

    private fun judge(
        record: NetworkRecord? = router,
        seen: SeenTokens? = SeenTokens(listOf(u1), tA),
        purpose: Purpose = Purpose.SCAN,
        legacy: Boolean = false,
        keyIdOk: Boolean = true,
        asked: Boolean = false,
    ) = RouterIdentity.judge(record, legacy, keyIdOk, seen, purpose, asked)

    // ---- 2. judge, one row per line of the table, plus the extra cases -----------------------------

    @Test
    fun noRecordAndNoLegacyLineIsNotConfirmed() {
        assertSame(Verdict.NotConfirmed, judge(record = null, seen = null))
        assertEquals(LanKeys.REASON_NOT_CONFIRMED, Verdict.NotConfirmed.reason)
    }

    @Test
    fun onlyALegacyLineAsksForOneMoreConfirmation() {
        assertSame(Verdict.Reconfirm, judge(record = null, seen = null, legacy = true))
        assertEquals("reconfirm", Verdict.Reconfirm.reason)
    }

    @Test
    fun aRowUnderAnotherKeyIsKeyChangedBeforeAnythingElse() {
        assertSame(Verdict.KeyChanged, judge(keyIdOk = false))
        assertSame(Verdict.KeyChanged, judge(record = null, legacy = true, keyIdOk = false))
        assertEquals("key-changed", Verdict.KeyChanged.reason)
    }

    @Test
    fun aSeenUpnpIdOrTheSameAdminKeyIsAMatch() {
        assertSame(Verdict.Match, judge(seen = SeenTokens(listOf(u1), null)))
        assertSame(Verdict.Match, judge(seen = SeenTokens(emptyList(), tA)))
        assertTrue(Verdict.Match.allowed)
    }

    @Test
    fun aUpnpMatchWithAnotherAdminKeyIsStillAMatch() {
        assertSame(Verdict.Match, judge(seen = SeenTokens(listOf(u1), tB)))
    }

    @Test
    fun anAdminKeyMatchWithAnotherUpnpIdIsStillAMatch() {
        assertSame(Verdict.Match, judge(seen = SeenTokens(listOf(u2), tA)))
    }

    @Test
    fun aComparableFactorThatDiffersIsAMismatch() {
        assertSame(Verdict.Mismatch, judge(seen = SeenTokens(listOf(u2), null)))
        assertSame(Verdict.Mismatch, judge(seen = SeenTokens(emptyList(), tB)))
        assertSame(Verdict.Mismatch, judge(seen = SeenTokens(listOf(u2), tB)))
        assertEquals("router-mismatch", Verdict.Mismatch.reason)
        assertFalse(Verdict.Mismatch.allowed)
    }

    @Test
    fun nothingComparableIsSilent() {
        assertSame(Verdict.Silent, judge(seen = SeenTokens()))
        assertSame(Verdict.Silent, judge(seen = SeenTokens(probed = false)))
        assertSame("no probe ran", Verdict.Silent, judge(seen = null))
        assertEquals("router-silent", Verdict.Silent.reason)
    }

    @Test
    fun upnpAbsentNowAndAnAdminKeyNeverStoredIsSilent() {
        val upnpOnly = NetworkRecord(net, RecordMode.ROUTER, listOf(u1), null)
        assertSame(Verdict.Silent, judge(record = upnpOnly, seen = SeenTokens(emptyList(), tB)))
        val tlsOnly = NetworkRecord(net, RecordMode.ROUTER, emptyList(), tA)
        assertSame(Verdict.Silent, judge(record = tlsOnly, seen = SeenTokens(listOf(u2), null)))
    }

    @Test
    fun addressOnlyIsAllowedOnlyWhenAskedNow() {
        assertSame(Verdict.AddressOnly, judge(record = address, seen = null, asked = true))
        assertSame(Verdict.AddressOnly, judge(record = address, seen = null, asked = true, purpose = Purpose.LOOK_CLOSER))
        assertTrue(Verdict.AddressOnly.allowed)
        assertSame(Verdict.Ask, judge(record = address, seen = null))
        assertSame(Verdict.Ask, judge(record = address, seen = null, purpose = Purpose.LOOK_CLOSER))
        assertEquals("address-only", Verdict.Ask.reason)
    }

    @Test
    fun closeItNeedsTheUpnpIdItself() {
        assertSame(Verdict.NoUpnpMatch, judge(seen = SeenTokens(emptyList(), tA), purpose = Purpose.CLOSE_IT))
        assertSame(Verdict.NoUpnpMatch, judge(seen = SeenTokens(listOf(u2), tA), purpose = Purpose.CLOSE_IT))
        assertSame(Verdict.Match, judge(seen = SeenTokens(listOf(u1), tB), purpose = Purpose.CLOSE_IT))
        assertSame(Verdict.Mismatch, judge(seen = SeenTokens(listOf(u2), tB), purpose = Purpose.CLOSE_IT))
    }

    @Test
    fun closeItOnAnAddressOnlyNetworkIsAlwaysRefused() {
        assertSame(Verdict.NoUpnpMatch, judge(record = address, seen = null, purpose = Purpose.CLOSE_IT, asked = true))
        assertSame(Verdict.NoUpnpMatch, judge(record = address, seen = SeenTokens(listOf(u1), tA), purpose = Purpose.CLOSE_IT))
    }

    @Test
    fun aLostProbeIsTheWifiChanging() {
        assertSame(Verdict.Lost, judge(seen = SeenTokens(listOf(u1), tA, lost = true)))
        assertEquals(LanKeys.REASON_NETWORK_UNKNOWN, Verdict.Lost.reason)
    }

    @Test
    fun aV2RecordWinsOverALegacyLine() {
        assertSame(Verdict.Match, judge(legacy = true))
        assertSame(Verdict.Mismatch, judge(legacy = true, seen = SeenTokens(listOf(u2), null)))
    }

    @Test
    fun onlyARouterRecordUnderTheCurrentKeyIsProbed() {
        assertTrue(RouterIdentity.needsProbe(router, keyIdOk = true))
        assertFalse(RouterIdentity.needsProbe(router, keyIdOk = false))
        assertFalse(RouterIdentity.needsProbe(address, keyIdOk = true))
        assertFalse(RouterIdentity.needsProbe(null, keyIdOk = true))
    }

    // ---- 3. refresh -----------------------------------------------------------------------------

    @Test
    fun refreshPutsANewUpnpIdFirstAndKeepsFour() {
        val full = NetworkRecord(net, RecordMode.ROUTER, listOf(u1, u2, u3, t('4')), tA)
        val fresh = RouterIdentity.refresh(full, SeenTokens(listOf(t('5')), tA))
        assertEquals(listOf(t('5'), u1, u2, u3), fresh.upnp)
        assertTrue(fresh.valid)
        assertEquals(listOf(u2, u1), RouterIdentity.refresh(router, SeenTokens(listOf(u2, u1), null)).upnp)
    }

    @Test
    fun refreshReplacesTheAdminKey() {
        assertEquals(tB, RouterIdentity.refresh(router, SeenTokens(listOf(u1), tB)).tls)
    }

    @Test
    fun refreshNeverDropsAFactorNotSeen() {
        assertEquals(router, RouterIdentity.refresh(router, SeenTokens(listOf(u1), null)))
        assertEquals(router, RouterIdentity.refresh(router, SeenTokens(emptyList(), tA)))
        assertEquals(listOf(u1), RouterIdentity.refresh(router, SeenTokens(emptyList(), tB)).upnp)
    }

    @Test
    fun anUnchangedRecordIsReturnedAsItWas() {
        assertEquals(router, RouterIdentity.refresh(router, SeenTokens(listOf(u1), tA)))
        assertEquals(address, RouterIdentity.refresh(address, SeenTokens(listOf(u1), tA)))
    }

    // ---- 4. uuidsFrom and the unicast search ---------------------------------------------------------

    private fun reply(usn: String, st: String = "upnp:rootdevice") =
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nST: $st\r\nUSN: $usn\r\nLOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n\r\n"

    private val gw = "192.168.1.1"
    private val id1 = "3a2f9c00-1111-4b4b-8c8c-0123456789ab"
    private val id2 = "00112233-4455-6677-8899-aabbccddeeff"

    @Test
    fun onlyTheGatewaysRootDeviceRepliesCount() {
        val replies = listOf(
            gw to reply("uuid:$id1::upnp:rootdevice"),
            "192.168.1.20" to reply("uuid:$id2::upnp:rootdevice"),
            gw to reply("uuid:$id2::urn:schemas-upnp-org:device:WANDevice:1", st = "urn:schemas-upnp-org:device:WANDevice:1"),
            gw to reply("uuid:$id2", st = "uuid:$id2"),
        )
        assertEquals(listOf(id1), RouterIdentity.uuidsFrom(gw, replies))
    }

    @Test
    fun anEmbeddedDevicesUsnUnderARootDeviceStIsRefused() {
        assertEquals(emptyList<String>(), RouterIdentity.uuidsFrom(gw, listOf(gw to reply("uuid:$id1::urn:schemas-upnp-org:device:WANDevice:1"))))
    }

    @Test
    fun theStAndUsnSuffixAreCaseInsensitiveAndTheUuidLowercased() {
        val r = reply("uuid:${id1.uppercase()}::UPnP:RootDevice", st = "UPNP:ROOTDEVICE")
        assertEquals(listOf(id1), RouterIdentity.uuidsFrom(gw, listOf(gw to r)))
    }

    @Test
    fun freeTextInTheUsnAndUnparsableRepliesAreRefused() {
        val replies = listOf(
            gw to reply("uuid:hello world, I am your router::upnp:rootdevice"),
            gw to reply("uuid:${"a".repeat(65)}::upnp:rootdevice"),
            gw to "NOTIFY * HTTP/1.0\r\nUSN: uuid:$id1::upnp:rootdevice\r\nNT: upnp:rootdevice\r\n\r\n",
            gw to "HTTP/1.1 404 Not Found\r\nST: upnp:rootdevice\r\nUSN: uuid:$id1::upnp:rootdevice\r\n\r\n",
            "$gw " to reply("uuid:$id1::upnp:rootdevice"),
        )
        assertEquals(emptyList<String>(), RouterIdentity.uuidsFrom(gw, replies))
    }

    @Test
    fun atMostFourDistinctSortedIds() {
        val ids = (9 downTo 0).map { "0000000$it-0000-0000-0000-000000000000" }
        val replies = (ids + ids).map { gw to reply("uuid:$it::upnp:rootdevice") }
        assertEquals(ids.sorted().take(4), RouterIdentity.uuidsFrom(gw, replies))
    }

    @Test
    fun theUnicastSearchIsExact() {
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\nHOST: 192.168.1.1:1900\r\nMAN: \"ssdp:discover\"\r\nST: upnp:rootdevice\r\n\r\n",
            Ssdp.unicastSearch("192.168.1.1"),
        )
    }

    // ---- 6. tokens --------------------------------------------------------------------------------

    private val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    private val mac: (String) -> String = { input ->
        Mac.getInstance("HmacSHA256").apply { init(testKey) }.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private val tokens = RouterTokens(mac)

    @Test
    fun inputsAreDomainSeparated() {
        val text = "3a2f9c00111148"
        assertNotEquals(tokens.upnp(text), tokens.tls(text))
        assertEquals(mac("upnp:v1\n$text").take(32), tokens.upnp(text))
        assertEquals(mac("tls:v1\n$text").take(32), tokens.tls(text))
    }

    @Test
    fun fixedVectorsUnderTheTestKey() {
        val fp = NetworkFingerprint.of("192.168.1.1", "192.168.1.1", listOf("192.168.1.1"), "192.168.1.0/24")!!
        assertEquals(mac("net:v2\n" + fp.canonical).take(32), tokens.net(fp))
        // Pinned values: a change of the input format or the truncation changes these.
        assertEquals(FIXED_NET, tokens.net(fp))
        assertEquals(FIXED_UPNP, tokens.upnp(id1))
        assertEquals(FIXED_TLS, tokens.tls("ab".repeat(32)))
        assertEquals(FIXED_KID, tokens.keyId())
        assertTrue(RouterTokens.isToken(tokens.net(fp)))
        assertTrue(NetworkRecordCodec.isKeyId(tokens.keyId()))
        assertNotEquals("the keyed token is not the unkeyed hash", fp.hash.take(32), tokens.net(fp))
    }

    @Test
    fun theAdminKeyHashIsTokenisedLowercase() {
        assertEquals(tokens.tls("ab".repeat(32)), tokens.tls("AB".repeat(32)))
    }

    @Test
    fun seenTurnsRawValuesIntoTokensOnly() {
        val seen = tokens.seen(listOf(id1, id1, id2), "ab".repeat(32), probed = true, lost = false)
        assertEquals(listOf(tokens.upnp(id1), tokens.upnp(id2)), seen.upnp)
        assertEquals(tokens.tls("ab".repeat(32)), seen.tls)
        assertTrue(seen.upnp.none { it.contains(id1) })
        assertEquals(SeenTokens(emptyList(), null, probed = false, lost = true), tokens.seen(emptyList(), null, probed = false, lost = true))
    }

    @Test(expected = IllegalStateException::class)
    fun aMacThatIsNotHexIsRefused() {
        RouterTokens { "not hex" }.upnp("x")
    }

    // ---- the one-shot permit -------------------------------------------------------------------------

    @Test
    fun thePermitIsOneShotAndExpiresAtSixtySeconds() {
        var now = 1_000L
        val permit = AddressOnlyPermit { now }
        assertFalse(permit.consume(net))
        permit.grant(net)
        assertFalse("another network", permit.consume(u1))
        permit.grant(net)
        now += 59_999
        assertTrue(permit.consume(net))
        assertFalse("one shot", permit.consume(net))
        permit.grant(net)
        now += 60_000
        assertFalse("expired at 60 s", permit.consume(net))
    }

    /** Computed independently with Python's hmac module under the key bytes 0..31. */
    companion object {
        const val FIXED_NET = "7f82927d1b0728068eacf6020d402629"
        const val FIXED_UPNP = "c2f521b0f4eab387cc76d2622f4dcf0e"
        const val FIXED_TLS = "76c99cfd5254a01af21e385355d80951"
        const val FIXED_KID = "0334322d"
    }
}
