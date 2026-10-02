package io.github.stronghorse44.tunnels.devicecheck

import io.github.stronghorse44.tunnels.attestation.SiliconKeys
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
    fun aSecondPhoneCheckPointsToAuditor() {
        val missing = DeviceChecks.secondPhone(auditorInstalled = false, appStoreInstalled = true)
        assertEquals(CheckStatus.TODO, missing.status)
        assertEquals(CheckAction.OpenApp(DeviceChecks.GRAPHENE_APPS, "Open App Store"), missing.action)
        assertEquals(null, DeviceChecks.secondPhone(auditorInstalled = false, appStoreInstalled = false).action)
        val present = DeviceChecks.secondPhone(auditorInstalled = true, appStoreInstalled = true)
        assertEquals(CheckAction.OpenApp(DeviceChecks.AUDITOR, "Open Auditor"), present.action)
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
}
