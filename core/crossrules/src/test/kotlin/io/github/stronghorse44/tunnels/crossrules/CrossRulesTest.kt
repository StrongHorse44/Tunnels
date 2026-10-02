package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.crossrules.Fixtures.NOW
import io.github.stronghorse44.tunnels.crossrules.Fixtures.apk
import io.github.stronghorse44.tunnels.crossrules.Fixtures.context
import io.github.stronghorse44.tunnels.crossrules.Fixtures.deep
import io.github.stronghorse44.tunnels.crossrules.Fixtures.finding
import io.github.stronghorse44.tunnels.crossrules.Fixtures.join
import io.github.stronghorse44.tunnels.crossrules.Fixtures.permissions
import io.github.stronghorse44.tunnels.crossrules.Fixtures.timeline
import io.github.stronghorse44.tunnels.crossrules.Fixtures.traffic
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.permrules.PermissionRules
import io.github.stronghorse44.tunnels.trackers.ApkRules
import io.github.stronghorse44.tunnels.trackers.GooglePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class CrossRulesTest {
    private fun drafts(joined: List<Observation>): List<FindingDraft> = CrossRules.all.flatMap { it.evaluate(context(joined)) }

    private fun only(joined: List<Observation>, kind: String): FindingDraft = drafts(joined).single { it.kind == kind }

    private fun kinds(joined: List<Observation>): Set<String> = drafts(joined).map { it.kind }.toSet()

    @Test
    fun accessibilityFromAFileIsCritical() {
        val pkg = "com.free.cleaner"
        val joined = join(mapOf(Sources.PERMISSIONS to permissions(pkg, accessibility = true), Sources.APK to apk(pkg, "com.android.packageinstaller")))
        val d = only(joined, CrossRules.SIDELOADED_ACCESSIBILITY)
        assertEquals(Severity.CRITICAL, d.severity)
        assertEquals(pkg, d.subject)
        assertTrue(d.evidence, d.evidence.contains("installed from an APK file (Android's package installer)"))
        assertTrue(d.evidence.contains("(Permissions)") && d.evidence.contains("(APK excavation)"))
    }

    @Test
    fun accessibilityFromAnUpdaterIsAWarningAndFromAStoreIsNotThisFinding() {
        val obtainium = join(mapOf(Sources.PERMISSIONS to permissions("a.b", accessibility = true), Sources.APK to apk("a.b", "dev.imranr.obtainium")))
        assertEquals(Severity.WARN, only(obtainium, CrossRules.SIDELOADED_ACCESSIBILITY).severity)
        assertTrue(only(obtainium, CrossRules.SIDELOADED_ACCESSIBILITY).evidence.contains("installed by Obtainium"))
        val store = join(mapOf(Sources.PERMISSIONS to permissions("a.b", accessibility = true), Sources.APK to apk("a.b", "org.fdroid.fdroid")))
        assertTrue(CrossRules.SIDELOADED_ACCESSIBILITY !in kinds(store))
    }

    @Test
    fun smsFromAFileOrAdbIsAWarning() {
        val adb = join(mapOf(Sources.PERMISSIONS to permissions("x.sms", "READ_SMS", "RECEIVE_SMS"), Sources.APK to apk("x.sms", "unknown")))
        val d = only(adb, CrossRules.SIDELOADED_MESSAGES)
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.evidence, d.evidence.startsWith("Holds SMS (Permissions) and has no installer on record"))
        val store = join(mapOf(Sources.PERMISSIONS to permissions("x.sms", "READ_SMS"), Sources.APK to apk("x.sms", "com.android.vending")))
        assertTrue(CrossRules.SIDELOADED_MESSAGES !in kinds(store))
    }

    @Test
    fun adSdksWithBackgroundLocationAndNetworkOnWarn() {
        val pkg = "com.example.weather"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION"),
                Sources.APK to apk(pkg, "com.android.vending", mapOf("applovin" to "ads", "firebase_crashlytics" to "crash")),
            ),
        )
        val d = only(joined, CrossRules.TRACKERS_WITH_PERSONAL_DATA)
        assertEquals(Severity.WARN, d.severity)
        assertEquals(
            "Holds background location and precise location (Permissions) and embeds AppLovin MAX and Firebase Crashlytics for ads " +
                "(APK excavation). Embedded SDKs run with the app's permissions, so they can read what it holds, and its Network toggle is on.",
            d.evidence,
        )
    }

    @Test
    fun preciseLocationAloneIsANoticeUntilTrafficConfirmsTheTrackers() {
        val pkg = "com.example.maps"
        val sources = mapOf(
            Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION"),
            Sources.APK to apk(pkg, "com.android.vending", mapOf("foursquare_pilgrim" to "location")),
        )
        assertEquals(Severity.NOTICE, only(join(sources), CrossRules.TRACKERS_WITH_PERSONAL_DATA).severity)
        val confirmed = only(join(sources + (Sources.TRAFFIC to traffic(pkg, 2, "foursquare.com,doubleclick.net"))), CrossRules.TRACKERS_WITH_PERSONAL_DATA)
        assertEquals(Severity.WARN, confirmed.severity)
        assertTrue(confirmed.evidence, confirmed.evidence.endsWith("During Traffic sessions it looked up 2 tracking domains: foursquare.com, doubleclick.net."))
    }

    @Test
    fun noPersonalDataFindingWithTheNetworkOffOrOnlyCrashSdks() {
        val pkg = "com.example.maps"
        val off = join(mapOf(Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION", network = "off"), Sources.APK to apk(pkg, "com.android.vending", mapOf("applovin" to "ads"))))
        assertTrue(CrossRules.TRACKERS_WITH_PERSONAL_DATA !in kinds(off))
        val crashOnly = join(mapOf(Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION"), Sources.APK to apk(pkg, "com.android.vending", mapOf("firebase_crashlytics" to "crash"))))
        assertTrue(CrossRules.TRACKERS_WITH_PERSONAL_DATA !in kinds(crashOnly))
    }

    @Test
    fun accessibilityInAStoreAppWithTrackersWarnsOnce() {
        val pkg = "com.example.launcher"
        val joined = join(mapOf(Sources.PERMISSIONS to permissions(pkg, accessibility = true), Sources.APK to apk(pkg, "com.android.vending", mapOf("firebase_analytics" to "analytics"))))
        assertEquals(Severity.WARN, only(joined, CrossRules.ACCESSIBILITY_WITH_TRACKERS).severity)
        // From a file, the sideload finding covers it instead of a second card for the same app.
        val file = join(mapOf(Sources.PERMISSIONS to permissions(pkg, accessibility = true), Sources.APK to apk(pkg, "com.android.packageinstaller", mapOf("firebase_analytics" to "analytics"))))
        assertEquals(setOf(CrossRules.SIDELOADED_ACCESSIBILITY), kinds(file))
    }

    @Test
    fun idleAppsStillHoldingTheMicrophoneWarn() {
        val pkg = "com.example.old"
        val joined = join(mapOf(Sources.PERMISSIONS to permissions(pkg, "RECORD_AUDIO", "READ_CALENDAR"), Sources.TIMELINE to timeline(pkg, lastUsed = "2026-07-01")))
        val d = only(joined, CrossRules.IDLE_WITH_ACCESS)
        assertEquals(Severity.WARN, d.severity)
        assertTrue(
            d.evidence,
            d.evidence.startsWith("Not opened for 91 days (last on 2026-07-01), up to the Timeline scan on 2026-09-30, yet it still holds microphone and calendar"),
        )
        val calendarOnly = join(mapOf(Sources.PERMISSIONS to permissions(pkg, "READ_CALENDAR"), Sources.TIMELINE to timeline(pkg, lastUsed = "2026-07-01")))
        assertEquals(Severity.NOTICE, only(calendarOnly, CrossRules.IDLE_WITH_ACCESS).severity)
    }

    @Test
    fun microphoneUseAfterTheLastOpenIsFlagged() {
        val pkg = "com.example.recorder"
        val sources = mapOf(
            Sources.PERMISSIONS to permissions(pkg, "RECORD_AUDIO"),
            Sources.TIMELINE to timeline(pkg, lastUsed = "2026-09-01"),
            Sources.DEEP to deep(pkg, mic = "3 days ago"),
        )
        val d = only(join(sources), CrossRules.SENSOR_USE_UNOPENED)
        assertEquals(
            "Used the microphone on 2026-09-27 (Deep mode), but it was last opened on 2026-09-01 (Timeline, up to 2026-09-30). " +
                "Apps rarely need the camera or microphone while you are not using them: check its permissions and whether it runs in the background.",
            d.evidence,
        )
        // Opened the day before the use: normal.
        val recent = join(sources + (Sources.TIMELINE to timeline(pkg, lastUsed = "2026-09-26")))
        assertTrue(CrossRules.SENSOR_USE_UNOPENED !in kinds(recent))
        // A use after the Timeline scan proves nothing: the app may have been opened since.
        val late = join(sources, at = mapOf(Sources.TIMELINE to NOW.minus(Duration.ofDays(6))))
        assertTrue(CrossRules.SENSOR_USE_UNOPENED !in kinds(late))
    }

    @Test
    fun aNewSignerWithNewAccessIsCritical() {
        val pkg = "com.example.app"
        val joined = join(
            mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.APK to apk(pkg, "com.android.vending")),
            findings = listOf(
                finding(Sources.APK, pkg, ApkRules.CERT_CHANGED, "Signing certificate changed from AA to BB."),
                finding(Sources.PERMISSIONS, pkg, PermissionRules.PERMISSION_GAINED, "Newly granted: camera, SMS"),
            ),
        )
        val d = only(joined, CrossRules.NEW_SIGNER_NEW_ACCESS)
        assertEquals(Severity.CRITICAL, d.severity)
        assertTrue(d.evidence.contains("gained camera, SMS (Permissions)"))
        // Only one side open: no finding.
        val one = join(
            mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.APK to apk(pkg, "com.android.vending")),
            findings = listOf(finding(Sources.APK, pkg, ApkRules.CERT_CHANGED, "changed")),
        )
        assertTrue(CrossRules.NEW_SIGNER_NEW_ACCESS !in kinds(one))
    }

    private val gmsSdks = mapOf("firebase_analytics" to "analytics", "google_admob" to "ads")

    @Test
    fun googlePlayServicesHoldingSmsAndContactsIsItsOwnFindingWithoutTrackers() {
        val pkg = "com.google.android.gms"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "READ_SMS", "READ_CONTACTS"),
                Sources.APK to apk(pkg, "app.grapheneos.apps", gmsSdks, cert = GooglePlay.CERT_SHA256),
            ),
        )
        assertEquals("true", Fixtures.facts(joined, pkg)[CrossKeys.GOOGLE_PLAY])
        assertTrue(CrossKeys.SDK_COUNT !in Fixtures.facts(joined, pkg))
        assertEquals(setOf(CrossRules.GOOGLE_HOLDS_PERSONAL_DATA), kinds(joined))
        val d = only(joined, CrossRules.GOOGLE_HOLDS_PERSONAL_DATA)
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.evidence, d.evidence.startsWith("Google Play services holds SMS and contacts (Permissions), is signed by Google"))
        assertTrue(CrossRules.GOOGLE_HOLDS_PERSONAL_DATA in CrossRules.NO_UNINSTALL_KINDS)
    }

    @Test
    fun googlePlayWithoutPersonalAccessOrNetworkIsQuiet() {
        val pkg = "com.google.android.gms"
        val apk = apk(pkg, "app.grapheneos.apps", gmsSdks, cert = GooglePlay.CERT_SHA256)
        assertTrue(drafts(join(mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.APK to apk))).isEmpty())
        assertTrue(drafts(join(mapOf(Sources.PERMISSIONS to permissions(pkg, "READ_SMS", network = "off"), Sources.APK to apk))).isEmpty())
    }

    @Test
    fun aLookAlikeGooglePackageWithAnotherSignerIsJudgedLikeAnyApp() {
        val pkg = "com.google.android.gms"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "READ_SMS", "READ_CONTACTS"),
                Sources.APK to apk(pkg, "com.android.packageinstaller", gmsSdks, cert = "AB".repeat(32)),
            ),
        )
        val kinds = kinds(joined)
        assertTrue(kinds.toString(), CrossRules.TRACKERS_WITH_PERSONAL_DATA in kinds && CrossRules.SIDELOADED_MESSAGES in kinds)
        assertTrue(CrossRules.GOOGLE_HOLDS_PERSONAL_DATA !in kinds)
    }

    @Test
    fun systemAppsRaiseNoAppFindings() {
        val pkg = "com.android.systemui"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION", "READ_SMS", system = true, accessibility = true),
                Sources.APK to apk(pkg, "unknown", mapOf("applovin" to "ads"), system = true),
            ),
        )
        assertTrue(drafts(joined).isEmpty())
    }

    @Test
    fun everyDraftBelongsToCrossroads() {
        val pkg = "com.free.cleaner"
        val joined = join(mapOf(Sources.PERMISSIONS to permissions(pkg, "READ_SMS", accessibility = true), Sources.APK to apk(pkg, "com.android.packageinstaller")))
        val all = drafts(joined)
        assertEquals(setOf(CrossRules.SIDELOADED_ACCESSIBILITY, CrossRules.SIDELOADED_MESSAGES), all.map { it.kind }.toSet())
        assertTrue(all.all { it.tunnelId == CrossKeys.TUNNEL_ID && !it.sticky })
    }
}
