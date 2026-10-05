package io.github.stronghorse44.tunnels.devicecheck

import io.github.stronghorse44.tunnels.attestation.SiliconKeys
import io.github.stronghorse44.tunnels.posture.PostureKeys
import io.github.stronghorse44.tunnels.posture.PostureState
import io.github.stronghorse44.tunnels.posture.PostureWhy
import io.github.stronghorse44.tunnels.posture.Reading
import io.github.stronghorse44.tunnels.watchrules.WatchSettings
import io.github.stronghorse44.tunnels.watchrules.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DeviceChecksTest {
    private val now = Instant.parse("2026-10-02T08:00:00Z")

    @Test
    fun theNetworkToggleIsConfirmedOnlyWhenSomeAppReadsOff() {
        assertEquals(CheckStatus.PASS, DeviceChecks.networkToggle(ToggleProbe(false, false, 120, 7), grapheneOs = true).status)
        val untested = DeviceChecks.networkToggle(ToggleProbe(true, true, 120, 0), grapheneOs = true)
        assertEquals(CheckStatus.TODO, untested.status)
        assertTrue(untested.action is CheckAction.OpenSettings)
        assertEquals(CheckStatus.FAIL, DeviceChecks.networkToggle(ToggleProbe(true, false, 120, 7), grapheneOs = true).status)
        assertEquals(CheckStatus.NOTE, DeviceChecks.networkToggle(ToggleProbe(true, true, 120, 0), grapheneOs = false).status)
    }

    @Test
    fun theSensorsToggleNeedsGrapheneOsPermission() {
        assertEquals(CheckStatus.NOTE, DeviceChecks.sensorsToggle(ToggleProbe(null, false, 0, 0), permissionDefined = false).status)
        assertEquals(CheckStatus.PASS, DeviceChecks.sensorsToggle(ToggleProbe(true, true, 90, 2), permissionDefined = true).status)
        assertEquals(CheckStatus.TODO, DeviceChecks.sensorsToggle(ToggleProbe(true, true, 90, 0), permissionDefined = true).status)
    }

    private val graphene = mapOf(
        SiliconKeys.BOOT_STATE to "Verified",
        SiliconKeys.BOOT_LOCKED to "true",
        SiliconKeys.BOOT_KEY_NAME to "GrapheneOS on Pixel 10",
        SiliconKeys.CHAIN_VERIFIED to "true",
        SiliconKeys.CHAIN_ROOT to "Google hardware attestation root",
    )

    @Test
    fun verifiedBootPassesForAFreshGrapheneOsReading() {
        val r = DeviceChecks.verifiedBoot(graphene, now.minus(Duration.ofDays(2)), now)
        assertEquals(CheckStatus.PASS, r.status)
        assertEquals("Booted GrapheneOS on Pixel 10 with a locked bootloader, chain verified to Google hardware attestation root.", r.detail)
    }

    @Test
    fun verifiedBootFlagsUnlockedUnknownStaleAndMissing() {
        assertEquals(CheckStatus.FAIL, DeviceChecks.verifiedBoot(graphene + (SiliconKeys.BOOT_LOCKED to "false"), now, now).status)
        assertEquals(CheckStatus.FAIL, DeviceChecks.verifiedBoot(graphene + (SiliconKeys.BOOT_STATE to "SelfSigned"), now, now).status)
        assertEquals(CheckStatus.WARN, DeviceChecks.verifiedBoot(graphene + (SiliconKeys.BOOT_KEY_NAME to "unknown"), now, now).status)
        val stale = DeviceChecks.verifiedBoot(graphene, now.minus(Duration.ofDays(40)), now)
        assertEquals(CheckStatus.WARN, stale.status)
        assertTrue(stale.detail.contains("scan Silicon again"))
        assertEquals(CheckStatus.TODO, DeviceChecks.verifiedBoot(null, null, now).status)
        assertEquals(CheckStatus.WARN, DeviceChecks.verifiedBoot(mapOf(SiliconKeys.ATTESTATION_ERROR to "no keystore"), now, now).status)
    }

    @Test
    fun strictPrivateDnsBlindsTraffic() {
        val r = DeviceChecks.privateDns(PrivateDns.Strict("dns.quad9.net"))
        assertEquals(CheckStatus.WARN, r.status)
        assertTrue(r.detail.contains("dns.quad9.net"))
        assertEquals(CheckStatus.PASS, DeviceChecks.privateDns(PrivateDns.Automatic).status)
        assertEquals(CheckStatus.PASS, DeviceChecks.privateDns(PrivateDns.Off).status)
    }

    @Test
    fun anotherVpnBlocksSessions() {
        assertEquals(CheckStatus.WARN, DeviceChecks.vpn(connected = true, ours = false).status)
        assertEquals(CheckStatus.NOTE, DeviceChecks.vpn(connected = true, ours = true).status)
        assertEquals(CheckStatus.PASS, DeviceChecks.vpn(connected = false, ours = false).status)
    }

    @Test
    fun backgroundChecksReportOffLateAndHealthy() {
        val on = WatchSettings(enabled = true, intervalHours = 12)
        val ran = WatchStatus(lastRunAt = now.minus(Duration.ofHours(3)).toEpochMilli())
        assertEquals(CheckStatus.TODO, DeviceChecks.backgroundChecks(WatchSettings(), ran, false, true, true, now).status)
        assertEquals(CheckStatus.WARN, DeviceChecks.backgroundChecks(on, ran, scheduled = false, notificationsAllowed = true, batteryUnrestricted = true, now = now).status)
        val healthy = DeviceChecks.backgroundChecks(on, ran, true, true, true, now)
        assertEquals(CheckStatus.PASS, healthy.status)
        assertEquals("Every 12 h; the last one ran 3 h ago.", healthy.detail)
        val late = DeviceChecks.backgroundChecks(on, WatchStatus(lastRunAt = now.minus(Duration.ofHours(40)).toEpochMilli()), true, false, false, now)
        assertEquals(CheckStatus.WARN, late.status)
        assertTrue(late.detail, late.detail.contains("Unrestricted") && late.detail.contains("notifications are off"))
    }

    @Test
    fun specialAccessExplainsRestrictedSettingsOnlyWhenTheyApply() {
        val sideloaded = DeviceChecks.specialAccess("timeline", "Timeline", "usage", "Usage access", granted = false, restricted = true, installedFromFile = true)
        assertEquals(CheckStatus.TODO, sideloaded.status)
        assertTrue(sideloaded.detail.contains("Allow restricted settings"))
        assertEquals(CheckAction.GrantAccess("timeline", "usage", "Open setting"), sideloaded.action)
        val store = DeviceChecks.specialAccess("timeline", "Timeline", "usage", "Usage access", granted = false, restricted = true, installedFromFile = false)
        assertTrue(!store.detail.contains("restricted"))
        assertEquals(CheckStatus.PASS, DeviceChecks.specialAccess("timeline", "Timeline", "usage", "Usage access", true, true, true).status)
    }

    @Test
    fun aSecondPhoneCheckOpensPairing() {
        val none = DeviceChecks.secondPhone(auditorInstalled = false, pairedPhones = 0)
        assertEquals(CheckStatus.TODO, none.status)
        assertEquals(CheckAction.OpenScreen(DeviceChecks.ACTION_PAIRING, "Open Second phone"), none.action)
        val paired = DeviceChecks.secondPhone(auditorInstalled = true, pairedPhones = 2)
        assertEquals(CheckStatus.NOTE, paired.status)
        assertTrue(paired.detail, paired.detail.contains("2 paired phones") && paired.detail.contains("Auditor is installed"))
    }

    @Test
    fun smallerChecks() {
        assertEquals(CheckStatus.PASS, DeviceChecks.storeKey("StrongBox").status)
        assertEquals(CheckStatus.WARN, DeviceChecks.storeKey("software").status)
        assertEquals(CheckStatus.FAIL, DeviceChecks.packageVisibility(12).status)
        assertEquals(CheckStatus.PASS, DeviceChecks.packageVisibility(310).status)
        assertEquals(CheckStatus.PASS, DeviceChecks.appLock(available = true, enabled = true).status)
        assertTrue(DeviceChecks.appLock(available = true, enabled = false).action is CheckAction.OpenScreen)
        assertTrue(DeviceChecks.byHand.all { it.status == CheckStatus.TODO && it.action != null })
    }

    private fun reading(id: String, state: PostureState, value: String? = null, why: PostureWhy? = null) =
        Reading(PostureKeys.item(id)!!, state, value, why)

    @Test
    fun postureNotScannedIsTodo() {
        val none = DeviceChecks.postureReadings(emptyList(), null, now)
        assertEquals(CheckStatus.TODO, none.status)
        assertTrue(none.detail, none.detail.startsWith("Deep mode has not read posture yet"))
        assertEquals(CheckAction.OpenTunnel("deep_mode", "Open Deep mode"), none.action)
        // Readings without a snapshot time cannot have come from a scan.
        assertEquals(CheckStatus.TODO, DeviceChecks.postureReadings(listOf(reading("auto_reboot", PostureState.GOOD, "12 h")), null, now).status)
    }

    @Test
    fun postureCountsByState() {
        val readings = PostureKeys.ITEMS.map { item ->
            when (item.id) {
                "wifi_auto_off", "bt_auto_off", "nfc_auto_off" -> Reading(item, PostureState.UNKNOWN, null, PostureWhy.ABSENT)
                // The USB-C port is a reminder on this build: never counted, even though it reads absent.
                "usb_port" -> Reading(item, PostureState.UNKNOWN, null, PostureWhy.ABSENT)
                else -> Reading(item, PostureState.GOOD, "x", null)
            }
        }
        val r = DeviceChecks.postureReadings(readings, now.minus(Duration.ofHours(1)), now)
        assertEquals(CheckStatus.NOTE, r.status)
        assertTrue(r.detail, r.detail.startsWith("10 read, 3 not set on this phone (Wi-Fi auto-off, Bluetooth auto-off, NFC auto-off), 0 failed."))
        val all = DeviceChecks.postureReadings(PostureKeys.ITEMS.map { Reading(it, PostureState.GOOD, "x", null) }, now, now)
        assertEquals(CheckStatus.PASS, all.status)
        assertEquals("13 read, 0 not set on this phone, 0 failed.", all.detail)
        val unconfirmed = DeviceChecks.postureReadings(listOf(reading("wifi_auto_off", PostureState.UNKNOWN, "off", PostureWhy.UNCONFIRMED)), now, now)
        assertTrue(unconfirmed.detail, unconfirmed.detail.startsWith("1 read (1 not confirmed on this phone yet), 0 not set on this phone, 0 failed."))
        val many = DeviceChecks.postureReadings(PostureKeys.ITEMS.map { Reading(it, PostureState.UNKNOWN, null, PostureWhy.ABSENT) }, now, now)
        assertTrue(many.detail, many.detail.contains("and 9 more"))
        val stale = DeviceChecks.postureReadings(PostureKeys.ITEMS.map { Reading(it, PostureState.GOOD, "x", null) }, now.minus(Duration.ofDays(40)), now)
        assertTrue(stale.detail, stale.detail.contains("scan Deep mode again"))
    }

    @Test
    fun unreadablePostureWarns() {
        val r = DeviceChecks.postureReadings(
            listOf(reading("auto_reboot", PostureState.GOOD, "12 h"), reading("wifi_auto_off", PostureState.UNKNOWN, null, PostureWhy.UNREADABLE)),
            now, now,
        )
        assertEquals(CheckStatus.WARN, r.status)
        assertTrue(r.detail, r.detail.startsWith("1 read, 0 not set on this phone, 1 failed."))
        assertEquals(
            CheckStatus.WARN,
            DeviceChecks.postureReadings(listOf(reading("auto_reboot", PostureState.UNKNOWN, null, PostureWhy.MALFORMED)), now, now).status,
        )
    }

    @Test
    fun privateDnsSettingDisagreeingWithTheNetworkFails() {
        fun dns(value: String?, net: PrivateDns) =
            DeviceChecks.privateDnsAgrees(value?.let { reading("private_dns", PostureState.GOOD, it) }, net, now, now)
        assertEquals(CheckStatus.FAIL, dns("off", PrivateDns.Automatic).status)
        assertEquals(CheckStatus.FAIL, dns("off", PrivateDns.Strict("resolver")).status)
        assertEquals(CheckStatus.FAIL, dns("provider", PrivateDns.Off).status)
        assertEquals(CheckStatus.PASS, dns("off", PrivateDns.Off).status)
        assertEquals(CheckStatus.PASS, dns("automatic", PrivateDns.Automatic).status)
        assertEquals(CheckStatus.PASS, dns("provider", PrivateDns.Strict("resolver")).status)
        // Automatic may fall back to plain lookups; that neither confirms nor contradicts the key.
        assertEquals(CheckStatus.NOTE, dns("automatic", PrivateDns.Off).status)
        // Either side unknown: a note, never a verdict.
        assertEquals(CheckStatus.NOTE, dns("off", PrivateDns.Unknown).status)
        assertEquals(CheckStatus.NOTE, dns(null, PrivateDns.Off).status)
        assertEquals(CheckStatus.NOTE, DeviceChecks.privateDnsAgrees(reading("private_dns", PostureState.UNKNOWN, null, PostureWhy.ABSENT), PrivateDns.Automatic, now, now).status)
        // An unconfirmed item is still compared: that is how its key gets confirmed.
        assertEquals(
            CheckStatus.PASS,
            DeviceChecks.privateDnsAgrees(reading("private_dns", PostureState.UNKNOWN, "automatic", PostureWhy.UNCONFIRMED), PrivateDns.Automatic, now, now).status,
        )
        // The network's hostname never reaches the text.
        assertTrue("resolver" !in dns("off", PrivateDns.Strict("resolver")).detail)
    }

    @Test
    fun aStalePrivateDnsReadingNeverFails() {
        val off = reading("private_dns", PostureState.WEAK, "off")
        // Fresh contradiction: FAIL. The same contradiction from a scan an hour old: the setting may have changed since.
        assertEquals(CheckStatus.FAIL, DeviceChecks.privateDnsAgrees(off, PrivateDns.Automatic, now.minus(Duration.ofMinutes(9)), now).status)
        val stale = DeviceChecks.privateDnsAgrees(off, PrivateDns.Automatic, now.minus(Duration.ofMinutes(60)), now)
        assertEquals(CheckStatus.NOTE, stale.status)
        assertTrue(stale.detail, stale.detail.contains("60 min ago") && stale.detail.contains("Scan Deep mode again"))
        assertEquals(CheckAction.OpenTunnel("deep_mode", "Open Deep mode"), stale.action)
        assertEquals(CheckStatus.NOTE, DeviceChecks.privateDnsAgrees(off, PrivateDns.Automatic, null, now).status)
        // Agreement still passes when stale.
        assertEquals(CheckStatus.PASS, DeviceChecks.privateDnsAgrees(off, PrivateDns.Off, now.minus(Duration.ofDays(2)), now).status)
        val provider = reading("private_dns", PostureState.GOOD, "provider")
        assertEquals(CheckStatus.NOTE, DeviceChecks.privateDnsAgrees(provider, PrivateDns.Off, now.minus(Duration.ofHours(5)), now).status)
        assertEquals(CheckStatus.FAIL, DeviceChecks.privateDnsAgrees(provider, PrivateDns.Off, now, now).status)
    }
}
