package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.crossrules.Fixtures.NOW
import io.github.stronghorse44.tunnels.crossrules.Fixtures.apk
import io.github.stronghorse44.tunnels.crossrules.Fixtures.deep
import io.github.stronghorse44.tunnels.crossrules.Fixtures.facts
import io.github.stronghorse44.tunnels.crossrules.Fixtures.finding
import io.github.stronghorse44.tunnels.crossrules.Fixtures.join
import io.github.stronghorse44.tunnels.crossrules.Fixtures.permissions
import io.github.stronghorse44.tunnels.crossrules.Fixtures.timeline
import io.github.stronghorse44.tunnels.crossrules.Fixtures.traffic
import io.github.stronghorse44.tunnels.permrules.PermissionRules
import io.github.stronghorse44.tunnels.trackers.ApkRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class CrossJoinTest {
    @Test
    fun joinsEverySourceIntoOneSetOfFactsPerApp() {
        val pkg = "com.example.weather"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS"),
                Sources.APK to apk(pkg, "com.android.vending", mapOf("applovin" to "ads", "firebase_analytics" to "analytics")),
                Sources.TRAFFIC to traffic(pkg, 3, "applovin.com,app-measurement.com"),
            ),
        )
        val f = facts(joined, pkg)
        assertEquals("Weather", f[CrossKeys.LABEL])
        assertEquals("false", f[CrossKeys.SYSTEM])
        assertEquals("store", f[CrossKeys.INSTALL_SOURCE])
        assertEquals("com.android.vending", f[CrossKeys.INSTALLED_BY])
        assertEquals("background location, precise location, contacts", f[CrossKeys.HELD])
        assertEquals("on", f[CrossKeys.NETWORK])
        assertEquals("2", f[CrossKeys.SDK_COUNT])
        assertEquals("AppLovin MAX,Firebase Analytics", f[CrossKeys.SDK_NAMES])
        assertEquals("analytics,ads", f[CrossKeys.SDK_CATEGORIES])
        assertEquals("3", f[CrossKeys.DNS_TRACKERS])
        assertEquals("applovin.com,app-measurement.com", f[CrossKeys.DNS_TRACKER_TOP])
        assertNull(f[CrossKeys.ACCESSIBILITY])
    }

    @Test
    fun theSummaryDatesOnlyTheActivitySources() {
        val joined = join(mapOf(Sources.PERMISSIONS to permissions("a.b", "CAMERA"), Sources.DEEP to deep("a.b", mic = "today")))
        val dates = CrossJoin.sourceDates(joined)
        assertEquals(setOf(Sources.TIMELINE, Sources.DEEP), dates.keys)
        assertEquals(CrossKeys.NONE, dates[Sources.TIMELINE])
        assertEquals("2026-09-30", dates[Sources.DEEP])
    }

    @Test
    fun aDayLaterTheSameDataJoinsTheSame() {
        // Background checks rescan Permissions daily: the join must not change just because the scan is newer.
        val sources = mapOf(Sources.PERMISSIONS to permissions("x.y", "READ_SMS"), Sources.APK to apk("x.y", "unknown"))
        val today = join(sources)
        val tomorrow = CrossJoin.observe(
            io.github.stronghorse44.tunnels.model.DerivedInput(
                sources.mapValues { (_, obs) -> io.github.stronghorse44.tunnels.model.SourceData(obs, NOW.plus(Duration.ofDays(1))) },
                emptyList(),
                NOW.plus(Duration.ofDays(1)),
            ),
            Fixtures.UTC,
        )
        assertEquals(today, tomorrow)
    }

    @Test
    fun anAppWithNothingCrossCuttingIsLeftOut() {
        // A store app holding the camera, no SDKs, no other source: nothing to join.
        val joined = join(mapOf(Sources.PERMISSIONS to permissions("org.camera", "CAMERA"), Sources.APK to apk("org.camera", "app.accrescent.client")))
        assertTrue(facts(joined, "org.camera").isEmpty())
        // The same app from a file is kept: where it came from matters for what it holds.
        val sideloaded = join(mapOf(Sources.PERMISSIONS to permissions("org.camera", "CAMERA"), Sources.APK to apk("org.camera", "com.android.packageinstaller")))
        assertEquals("file", facts(sideloaded, "org.camera")[CrossKeys.INSTALL_SOURCE])
    }

    @Test
    fun idleDaysCountToTheTimelineScanNotToday() {
        val pkg = "com.example.idle"
        val timelineAt = NOW.minus(Duration.ofDays(5)) // 2026-09-25
        val joined = join(
            mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.TIMELINE to timeline(pkg, lastUsed = "2026-08-01")),
            at = mapOf(Sources.TIMELINE to timelineAt),
        )
        val f = facts(joined, pkg)
        assertEquals("55", f[CrossKeys.IDLE_DAYS])
        assertEquals("2026-08-01", f[CrossKeys.LAST_OPENED])
    }

    @Test
    fun recentUseIsNotIdleAndNeverOpenedCountsFromInstall() {
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions("a.recent", "CAMERA") + permissions("a.never", "CAMERA"),
                Sources.TIMELINE to timeline("a.recent", lastUsed = "2026-09-20") + timeline("a.never", lastUsed = "never", firstInstall = "2026-06-01"),
            ),
        )
        assertTrue(facts(joined, "a.recent").isEmpty())
        val never = facts(joined, "a.never")
        assertEquals("121", never[CrossKeys.IDLE_DAYS])
        assertEquals("never", never[CrossKeys.LAST_OPENED])
    }

    @Test
    fun staleActivityReadingsAreNotJoined() {
        val pkg = "com.example.idle"
        val joined = join(
            mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.TIMELINE to timeline(pkg, lastUsed = "2026-01-01")),
            at = mapOf(Sources.TIMELINE to NOW.minus(Duration.ofDays(20))),
        )
        assertFalse(CrossKeys.IDLE_DAYS in facts(joined, pkg))
        // The summary still says when the (unused) Timeline data was from.
        assertEquals("2026-09-10", CrossJoin.sourceDates(joined)[Sources.TIMELINE])
    }

    @Test
    fun deepModeAgesBecomeDatesFromItsOwnScanDay() {
        val pkg = "com.example.recorder"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "RECORD_AUDIO"),
                Sources.TIMELINE to timeline(pkg, lastUsed = "2026-09-01"),
                Sources.DEEP to deep(pkg, camera = "30+ days", mic = "3 days ago"),
            ),
            at = mapOf(Sources.DEEP to NOW.minus(Duration.ofDays(1))),
        )
        val f = facts(joined, pkg)
        assertEquals("2026-09-26", f[CrossKeys.MIC_USED])
        assertNull("30+ days has no date", f[CrossKeys.CAMERA_USED])
        assertEquals("2026-09-01", f[CrossKeys.LAST_OPENED])
    }

    @Test
    fun openChangeFindingsFromSourcesAreJoined() {
        val pkg = "com.example.app"
        val joined = join(
            mapOf(Sources.PERMISSIONS to permissions(pkg, "CAMERA"), Sources.APK to apk(pkg, "com.android.vending")),
            findings = listOf(
                finding(Sources.APK, pkg, ApkRules.CERT_CHANGED, "Signing certificate changed"),
                finding(Sources.PERMISSIONS, pkg, PermissionRules.PERMISSION_GAINED, "Newly granted: camera, SMS"),
            ),
        )
        val f = facts(joined, pkg)
        assertEquals("true", f[CrossKeys.SIGNER_CHANGED])
        assertEquals("camera, SMS", f[CrossKeys.GAINED])
    }

    @Test
    fun systemAppsGetNoUsageFacts() {
        val pkg = "com.android.settings"
        val joined = join(
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "CAMERA", system = true, accessibility = true),
                Sources.TIMELINE to timeline(pkg, lastUsed = "2025-01-01", system = true),
            ),
        )
        val f = facts(joined, pkg)
        assertEquals("true", f[CrossKeys.SYSTEM])
        assertNull(f[CrossKeys.IDLE_DAYS])
    }

    @Test
    fun idleDaysHandlesUnparseableDates() {
        assertNull(CrossJoin.idleDays("garbage", null, LocalDate.parse("2026-09-30")))
        assertNull(CrossJoin.idleDays("never", null, LocalDate.parse("2026-09-30")))
        assertEquals(29L, CrossJoin.idleDays("2026-09-01", null, LocalDate.parse("2026-09-30")))
    }
}
