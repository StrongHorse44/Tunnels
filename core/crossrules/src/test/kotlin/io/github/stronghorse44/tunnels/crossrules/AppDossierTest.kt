package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.crossrules.Fixtures.apk
import io.github.stronghorse44.tunnels.crossrules.Fixtures.permissions
import io.github.stronghorse44.tunnels.crossrules.Fixtures.timeline
import io.github.stronghorse44.tunnels.crossrules.Fixtures.traffic
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import io.github.stronghorse44.tunnels.trackers.GooglePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDossierTest {
    private fun AppDossier.row(section: String, label: String): AppDossier.Row =
        sections.single { it.title.startsWith(section) }.rows.single { it.label == label }

    @Test
    fun joinsEveryTunnelsFactsAboutOneApp() {
        val pkg = "com.example.weather"
        val apk = apk(pkg, "com.android.packageinstaller", mapOf("applovin" to "ads", "firebase_analytics" to "analytics"), cert = "AB".repeat(32)) + listOf(
            Observation(Sources.APK, pkg, ApkKeys.VERSION, "2.1 (21)"),
            Observation(Sources.APK, pkg, ApkKeys.TARGET_SDK, "26"),
            Observation(Sources.APK, pkg, ApkKeys.DEBUGGABLE, "true"),
            Observation(Sources.APK, pkg, ApkKeys.CLEARTEXT, "true"),
            Observation(Sources.APK, pkg, ApkKeys.EXPORTED_OPEN, "activities=1,services=0,receivers=2,providers=1"),
            Observation(Sources.APK, pkg, ApkKeys.OPEN_PROVIDERS, "com.example.weather.data"),
            Observation("other_tunnel", "com.other", ApkKeys.LABEL, "ignored"),
        )
        val d = AppDossier.of(
            pkg,
            mapOf(
                Sources.PERMISSIONS to permissions(pkg, "ACCESS_FINE_LOCATION", "READ_CONTACTS"),
                Sources.APK to apk,
                Sources.TRAFFIC to traffic(pkg, 2, "doubleclick.net,graph.facebook.com"),
                Sources.TIMELINE to timeline(pkg, "2026-09-01"),
            ),
        )
        assertEquals("Weather", d.label)
        assertEquals(emptyList<String>(), d.missing)
        assertEquals(listOf("Origin", "Access", "Build", "Embedded SDKs", "Network (Traffic sessions, 30 days)", "Use"), d.sections.map { it.title })
        assertEquals(AppDossier.Row("installed", "from an APK file", AppDossier.Tone.WARN), d.row("Origin", "installed"))
        assertEquals("AB:AB:AB:AB…", d.row("Origin", "signed by").value)
        assertEquals(AppDossier.Tone.WARN, d.row("Access", "holds").tone)
        assertEquals("Android API 26", d.row("Build", "targets").value)
        assertEquals(AppDossier.Tone.WARN, d.row("Build", "debuggable").tone)
        assertEquals("com.example.weather.data", d.row("Build", "unguarded providers").value)
        assertTrue(d.row("Build", "open to other apps").value.startsWith("1 screens, 0 services, 2 receivers, 1 data providers"))
        assertEquals("Firebase Analytics", d.row("Embedded SDKs", "Google").value)
        assertEquals("doubleclick.net", d.row("Network", "Google").value)
        assertEquals("graph.facebook.com", d.row("Network", "Meta").value)
        assertEquals("2026-09-01", d.row("Use", "last opened").value)
    }

    @Test
    fun namesTheTunnelsThatHaveNotScannedTheApp() {
        val d = AppDossier.of("com.only.perm", mapOf(Sources.PERMISSIONS to permissions("com.only.perm")))
        assertEquals(listOf("APK excavation", "Traffic", "Timeline"), d.missing)
        assertEquals(listOf("Access"), d.sections.map { it.title })
        assertEquals("no runtime permissions", d.row("Access", "holds").value)
        assertEquals(AppDossier.Tone.PLAIN, d.row("Access", "holds").tone)
    }

    @Test
    fun googlesPlayAppsAreNotListedAsEmbeddingTrackers() {
        val pkg = "com.google.android.gms"
        val d = AppDossier.of(pkg, mapOf(Sources.APK to apk(pkg, "app.grapheneos.apps", mapOf("google_admob" to "ads"), cert = GooglePlay.CERT_SHA256)))
        assertTrue(d.row("Embedded SDKs", "trackers").value.startsWith("none counted"))
        assertTrue(d.row("Origin", "signed by").value.endsWith("(Google)"))
        assertEquals("from the GrapheneOS App Store", d.row("Origin", "installed").value)
    }
}
