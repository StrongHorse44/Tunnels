package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.model.SourceData
import io.github.stronghorse44.tunnels.permrules.PermissionKeys
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import java.time.Instant
import java.time.ZoneOffset

/** Observations shaped like the real tunnels write them, for one app at a time. */
object Fixtures {
    val NOW: Instant = Instant.parse("2026-09-30T12:00:00Z")
    val UTC: ZoneOffset = ZoneOffset.UTC

    fun permissions(pkg: String, vararg granted: String, network: String = "on", accessibility: Boolean = false, system: Boolean = false) = buildList {
        add(Observation(Sources.PERMISSIONS, pkg, PermissionKeys.APP_LABEL, pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }))
        add(Observation(Sources.PERMISSIONS, pkg, PermissionKeys.APP_SYSTEM, system.toString()))
        granted.forEach { add(Observation(Sources.PERMISSIONS, pkg, PermissionKeys.permKey("android.permission.$it"), PermissionKeys.GRANTED)) }
        add(Observation(Sources.PERMISSIONS, pkg, PermissionKeys.TOGGLE_NETWORK, network))
        if (accessibility) add(Observation(Sources.PERMISSIONS, pkg, PermissionKeys.ACCESS_ACCESSIBILITY, PermissionKeys.ENABLED))
    }

    /** [sdks] maps tracker ids to their category labels as the APK tunnel writes them ("ads", "analytics,location"). */
    fun apk(pkg: String, installer: String, sdks: Map<String, String> = emptyMap(), system: Boolean = false) = buildList {
        add(Observation(Sources.APK, pkg, ApkKeys.SYSTEM, system.toString()))
        add(Observation(Sources.APK, pkg, ApkKeys.INSTALLER, installer))
        sdks.forEach { (id, categories) -> add(Observation(Sources.APK, pkg, ApkKeys.sdkKey(id), categories)) }
        add(Observation(Sources.APK, pkg, ApkKeys.SDK_COUNT, sdks.size.toString()))
    }

    fun traffic(pkg: String, trackerDomains: Int, top: String) = listOf(
        Observation(Sources.TRAFFIC, pkg, TrafficKeys.TRACKER_DOMAINS30, trackerDomains.toString()),
        Observation(Sources.TRAFFIC, pkg, TrafficKeys.TRACKER_TOP, top),
    )

    fun timeline(pkg: String, lastUsed: String, firstInstall: String = "2025-01-01", system: Boolean = false) = listOf(
        Observation(Sources.TIMELINE, pkg, Sources.TIMELINE_SYSTEM, system.toString()),
        Observation(Sources.TIMELINE, pkg, Sources.TIMELINE_FIRST_INSTALL, firstInstall),
        Observation(Sources.TIMELINE, pkg, Sources.TIMELINE_LAST_USED, lastUsed),
    )

    fun deep(pkg: String, camera: String? = null, mic: String? = null) = listOfNotNull(
        camera?.let { Observation(Sources.DEEP, pkg, Sources.deepLastKey(Sources.DEEP_CAMERA), it) },
        mic?.let { Observation(Sources.DEEP, pkg, Sources.deepLastKey(Sources.DEEP_RECORD_AUDIO), it) },
    )

    fun finding(tunnelId: String, pkg: String, kind: String, evidence: String) =
        Finding(tunnelId, pkg, kind, Severity.WARN, NOW, NOW, evidence, emptyList(), sticky = true)

    /** The joined observations for [sources] (tunnel id -> observations, all taken at [at]). */
    fun join(sources: Map<String, List<Observation>>, findings: List<Finding> = emptyList(), at: Map<String, Instant> = emptyMap()): List<Observation> =
        CrossJoin.observe(
            DerivedInput(sources.mapValues { (id, obs) -> SourceData(obs, at[id] ?: NOW) }, findings, NOW),
            UTC,
        )

    fun context(joined: List<Observation>) = RuleContext(CrossKeys.TUNNEL_ID, joined, emptyList(), isFirstScan = false)

    fun facts(joined: List<Observation>, pkg: String): Map<String, String> =
        joined.filter { it.subject == pkg }.associate { it.key to it.value }
}
