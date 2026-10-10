package io.github.stronghorse44.tunnels.emissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards what this module adds to the APK: the same permissions, with the same flags, as the modules that already
 * declare them (so the merged manifest and the release gate do not change), and a tile only the system can bind.
 */
class ManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val allowed = File("permissions.allow").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
    private val declared = Regex("""<uses-permission\s[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()

    @Test
    fun declaresExactlyItsAllowlistAndNoNetwork() {
        assertEquals(allowed, declared)
        assertTrue("android.permission.INTERNET" !in declared)
    }

    @Test
    fun nearbyPermissionsAreNeverForLocation() {
        for (p in listOf("BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES")) {
            val tag = Regex("""<uses-permission\s[^>]*android:name="android\.permission\.$p"[^>]*/>""").find(manifest)?.value
            assertTrue("$p must carry neverForLocation: $tag", tag != null && tag.contains("""android:usesPermissionFlags="neverForLocation""""))
        }
    }

    @Test
    fun theTileIsBoundOnlyByTheSystem() {
        val service = Regex("""<service\s[^>]*android:name="\.EmissionsTileService"[^>]*>""").find(manifest)?.value.orEmpty()
        assertTrue(service, service.contains("""android:permission="android.permission.BIND_QUICK_SETTINGS_TILE""""))
        assertTrue(manifest.contains("android.service.quicksettings.action.QS_TILE"))
    }

    @Test
    fun theProviderIsRegistered() {
        val services = File("src/main/resources/META-INF/services/io.github.stronghorse44.tunnels.runtime.TunnelProvider").readText().trim()
        assertEquals(EmissionsTunnels::class.java.name, services)
    }
}
