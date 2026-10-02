package io.github.stronghorse44.tunnels.trackers

import io.github.stronghorse44.tunnels.engine.DiffEngine
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkRulesTest {
    private val t = ApkKeys.TUNNEL_ID

    private fun app(
        pkg: String,
        system: Boolean = false,
        trackers: Map<String, String> = emptyMap(),
        cert: String = "AABBCCDD" + "00".repeat(28),
        installer: String = "com.android.vending",
        targetSdk: Int = 35,
        abis: String = "arm64-v8a",
        skipped: String? = null,
        version: String = "1.0 (1)",
    ): List<Observation> = buildList {
        add(Observation(t, pkg, ApkKeys.LABEL, pkg.substringAfterLast('.')))
        add(Observation(t, pkg, ApkKeys.SYSTEM, system.toString()))
        add(Observation(t, pkg, ApkKeys.VERSION, version))
        trackers.forEach { (id, cats) -> add(Observation(t, pkg, ApkKeys.sdkKey(id), cats)) }
        add(Observation(t, pkg, ApkKeys.SDK_COUNT, trackers.size.toString()))
        skipped?.let { add(Observation(t, pkg, ApkKeys.SDK_SKIPPED, it)) }
        add(Observation(t, pkg, ApkKeys.CERT_SHA256, cert))
        add(Observation(t, pkg, ApkKeys.CERT_COUNT, "1"))
        add(Observation(t, pkg, ApkKeys.INSTALLER, installer))
        add(Observation(t, pkg, ApkKeys.TARGET_SDK, targetSdk.toString()))
        add(Observation(t, pkg, ApkKeys.NATIVE_ABIS, abis))
    }

    private fun evaluate(current: List<Observation>, previous: List<Observation>? = null): List<FindingDraft> {
        val ctx = RuleContext(t, current, previous?.let { DiffEngine.diff(it, current) }.orEmpty(), isFirstScan = previous == null)
        return ApkRules.all.flatMap { it.evaluate(ctx) }
    }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun trackerSdkSeverityAndEvidence() {
        val one = app("com.a", trackers = mapOf("firebase_analytics" to "analytics"))
        val many = app("com.b", trackers = mapOf("firebase_analytics" to "analytics", "appsflyer" to "attribution,analytics", "sentry" to "crash"))
        val adsLoc = app("com.c", trackers = mapOf("applovin" to "ads", "cuebiq" to "location"))
        val clean = app("com.d")
        val drafts = evaluate(one + many + adsLoc + clean).of(ApkRules.TRACKER_SDK)
        assertEquals(setOf("com.a", "com.b", "com.c"), drafts.map { it.subject }.toSet())
        val a = drafts.single { it.subject == "com.a" }
        assertEquals(Severity.NOTICE, a.severity)
        assertEquals("Embeds Firebase Analytics (analytics)", a.evidence)
        val b = drafts.single { it.subject == "com.b" }
        assertEquals(Severity.WARN, b.severity)
        assertEquals("Embeds AppsFlyer (analytics,attribution), Firebase Analytics (analytics), Sentry (crash)", b.evidence)
        assertEquals(Severity.WARN, drafts.single { it.subject == "com.c" }.severity)
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun googlesOwnPlayAppsAreNotTrackerFindingsButLookAlikesAre() {
        val sdks = mapOf("firebase_analytics" to "analytics", "google_admob" to "ads")
        val gms = app("com.google.android.gms", trackers = sdks, cert = GooglePlay.CERT_SHA256)
        val fake = app("com.android.vending", trackers = sdks)
        val drafts = evaluate(gms + fake).of(ApkRules.TRACKER_SDK)
        assertEquals(listOf("com.android.vending"), drafts.map { it.subject })
        assertTrue(GooglePlay.isGooglePlay("com.google.android.gsf", GooglePlay.CERT_SHA256.lowercase().chunked(2).joinToString(":")))
        assertTrue(!GooglePlay.isGooglePlay("com.example.app", GooglePlay.CERT_SHA256))
    }

    @Test
    fun trackerSdkTruncatesLongLists() {
        val ids = listOf("amplitude", "mixpanel", "segment", "heap", "onesignal", "flurry", "countly", "mparticle")
        val many = app("com.many", trackers = ids.associateWith { "analytics" })
        val d = evaluate(many).of(ApkRules.TRACKER_SDK).single()
        assertTrue(d.evidence, d.evidence.endsWith(" and 2 more"))
        assertEquals(6, d.evidence.count { it == '(' })
    }

    @Test
    fun certChangedIsStickyCriticalWithShortFingerprints() {
        val before = app("com.a", cert = "AABBCCDD" + "11".repeat(28))
        val after = app("com.a", cert = "EEFF0011" + "22".repeat(28))
        assertTrue(evaluate(after).of(ApkRules.CERT_CHANGED).isEmpty())
        assertTrue(evaluate(after, after).of(ApkRules.CERT_CHANGED).isEmpty())
        val d = evaluate(after, before).of(ApkRules.CERT_CHANGED).single()
        assertEquals(Severity.CRITICAL, d.severity)
        assertTrue(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("Signing certificate changed from AA:BB:CC:DD… to EE:FF:00:11…"))
        // A newly installed app has no "before" and must not trigger.
        assertTrue(evaluate(after + app("com.new"), before).of(ApkRules.CERT_CHANGED).map { it.subject } == listOf("com.a"))
    }

    @Test
    fun installerChangedIsStickyWarn() {
        val before = app("com.a", installer = "com.android.vending")
        val after = app("com.a", installer = "unknown")
        val d = evaluate(after, before).of(ApkRules.INSTALLER_CHANGED).single()
        assertEquals(Severity.WARN, d.severity)
        assertTrue(d.sticky)
        assertTrue(d.evidence, d.evidence.startsWith("Install source changed from com.android.vending to unknown."))
        assertTrue(d.evidence, d.evidence.contains("store that installed the app was itself uninstalled"))
        val toStore = evaluate(app("com.a", installer = "org.fdroid.fdroid"), before).of(ApkRules.INSTALLER_CHANGED).single()
        assertEquals("Install source changed from com.android.vending to org.fdroid.fdroid.", toStore.evidence)
    }

    @Test
    fun lowTargetSdkOnlyForUserApps() {
        val old = app("com.old", targetSdk = 28)
        val oldSystem = app("com.sys", system = true, targetSdk = 23)
        val fine = app("com.fine", targetSdk = 29)
        val unknown = app("com.unknown", targetSdk = 0)
        val drafts = evaluate(old + oldSystem + fine + unknown).of(ApkRules.LOW_TARGET_SDK)
        assertEquals(listOf("com.old"), drafts.map { it.subject })
        assertEquals(Severity.WARN, drafts.single().severity)
        assertTrue(drafts.single().evidence.contains("API 28"))
    }

    @Test
    fun abi32Only() {
        val only32 = app("com.a", abis = "armeabi-v7a,x86")
        val both = app("com.b", abis = "armeabi-v7a,arm64-v8a")
        val none = app("com.c", abis = "none")
        val drafts = evaluate(only32 + both + none).of(ApkRules.ABI_32_ONLY)
        assertEquals(listOf("com.a"), drafts.map { it.subject })
        assertEquals(Severity.INFO, drafts.single().severity)
    }

    @Test
    fun newTrackerFiresOnlyForUpdatedApps() {
        val before = app("com.a", trackers = mapOf("sentry" to "crash")) + app("com.gone", trackers = mapOf("sentry" to "crash"), skipped = null) +
            app("com.skip", skipped = "dex too large")
        val after = app("com.a", trackers = mapOf("sentry" to "crash", "appsflyer" to "attribution,analytics", "applovin" to "ads"), version = "1.1 (2)") +
            app("com.fresh", trackers = mapOf("mixpanel" to "analytics")) +
            app("com.skip", trackers = mapOf("amplitude" to "analytics"), version = "2.0 (2)")
        val drafts = evaluate(after, before).of(ApkRules.NEW_TRACKER)
        assertEquals(listOf("com.a"), drafts.map { it.subject })
        val d = drafts.single()
        assertTrue(d.sticky)
        assertEquals(Severity.NOTICE, d.severity)
        assertEquals("An update added AppLovin MAX (ads), AppsFlyer (analytics,attribution)", d.evidence)
        assertTrue(evaluate(after).of(ApkRules.NEW_TRACKER).isEmpty())
    }

    @Test
    fun newTrackerIgnoresCatalogGrowthWithoutAnUpdate() {
        // Same app version, one more sdk key: a grown tracker catalog, not an app update.
        val before = app("com.a", trackers = mapOf("sentry" to "crash")) + app("com.b", trackers = mapOf("sentry" to "crash"))
        val grown = app("com.a", trackers = mapOf("sentry" to "crash", "appsflyer" to "attribution,analytics")) +
            app("com.b", trackers = mapOf("sentry" to "crash", "appsflyer" to "attribution,analytics"))
        assertTrue(evaluate(grown, before).of(ApkRules.NEW_TRACKER).isEmpty())
        // The same key change together with a version change is a real update and must fire, for that app only.
        val updated = app("com.a", trackers = mapOf("sentry" to "crash", "appsflyer" to "attribution,analytics"), version = "1.0 (2)") +
            app("com.b", trackers = mapOf("sentry" to "crash", "appsflyer" to "attribution,analytics"))
        val drafts = evaluate(updated, before).of(ApkRules.NEW_TRACKER)
        assertEquals(listOf("com.a"), drafts.map { it.subject })
        assertEquals("An update added AppsFlyer (analytics,attribution)", drafts.single().evidence)
        // An update that changes nothing about trackers is silent.
        val bumped = app("com.a", trackers = mapOf("sentry" to "crash"), version = "1.0 (2)") + app("com.b", trackers = mapOf("sentry" to "crash"))
        assertTrue(evaluate(bumped, before).of(ApkRules.NEW_TRACKER).isEmpty())
    }

    @Test
    fun keysAndStats() {
        assertTrue(ApkKeys.isTrackerKey("sdk:sentry"))
        assertTrue(!ApkKeys.isTrackerKey("sdk:count") && !ApkKeys.isTrackerKey("sdk:skipped") && !ApkKeys.isTrackerKey("cert:sha256"))
        assertEquals("sentry", ApkKeys.trackerId("sdk:sentry"))
        assertEquals("analytics,ads", ApkKeys.categoriesValue(setOf(TrackerCategory.ADS, TrackerCategory.ANALYTICS)))
        assertEquals(setOf(TrackerCategory.ADS), ApkKeys.categoriesOf("ads, bogus"))
        assertEquals("AB:CD:EF:01…", ApkKeys.shortFingerprint("ABCDEF0123456789"))
        assertEquals("ABC", ApkKeys.shortFingerprint("ABC"))
        assertTrue(ApkKeys.is32BitOnly("armeabi-v7a") && !ApkKeys.is32BitOnly("none") && !ApkKeys.is32BitOnly("x86_64,x86") && !ApkKeys.is32BitOnly(""))

        val obs = app("com.a", trackers = mapOf("sentry" to "crash", "applovin" to "ads")) +
            app("com.b", trackers = mapOf("sentry" to "crash")) +
            app("com.c", skipped = "dex too large")
        val stats = TrackerStats.from(obs)
        assertEquals(3, stats.apps)
        assertEquals(2, stats.appsWithTrackers)
        assertEquals(1, stats.appsSkipped)
        assertEquals(listOf("sentry" to 2, "applovin" to 1), stats.perTracker)
        assertEquals(listOf(TrackerCategory.CRASH to 2, TrackerCategory.ADS to 1), stats.perCategory)
        assertEquals(TrackerStats.EMPTY, TrackerStats.from(emptyList()))
    }
}
