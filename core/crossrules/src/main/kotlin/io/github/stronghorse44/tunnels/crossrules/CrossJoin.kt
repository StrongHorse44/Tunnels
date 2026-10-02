package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.model.DerivedInput
import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.SourceData
import io.github.stronghorse44.tunnels.permrules.AppPermissionState
import io.github.stronghorse44.tunnels.permrules.PermissionCatalog
import io.github.stronghorse44.tunnels.permrules.PermissionGroup
import io.github.stronghorse44.tunnels.permrules.PermissionKeys
import io.github.stronghorse44.tunnels.permrules.PermissionRules
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import io.github.stronghorse44.tunnels.trackers.ApkRules
import io.github.stronghorse44.tunnels.trackers.TrackerCatalog
import io.github.stronghorse44.tunnels.trackers.TrackerCategory
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Joins the latest data of Permissions, APK excavation, Traffic, Timeline and Deep mode into one set of facts per
 * app (see [CrossKeys]). Pure. Data that is too old to trust for a fact is left out, never stretched: Timeline and
 * Deep mode readings older than [FRESH_ACTIVITY] are ignored, Traffic's older than [FRESH_TRAFFIC].
 */
object CrossJoin {
    /** Usage and app-op readings say what happened up to their scan; older than this they are not joined. */
    val FRESH_ACTIVITY: Duration = Duration.ofDays(14)
    /** Traffic observations already cover 30 days of sessions. */
    val FRESH_TRAFFIC: Duration = Duration.ofDays(30)

    fun observe(input: DerivedInput, zone: ZoneId = ZoneId.systemDefault()): List<Observation> {
        val now = input.now
        fun fresh(id: String, maxAge: Duration?): SourceData? =
            input.sources[id]?.takeIf { maxAge == null || !it.takenAt.isBefore(now.minus(maxAge)) }

        val permissions = fresh(Sources.PERMISSIONS, null)
        val apk = fresh(Sources.APK, null)
        val traffic = fresh(Sources.TRAFFIC, FRESH_TRAFFIC)
        val timeline = fresh(Sources.TIMELINE, FRESH_ACTIVITY)
        val deep = fresh(Sources.DEEP, FRESH_ACTIVITY)

        val perm = permissions?.observations.orEmpty().groupBy { it.subject }
        val apkBy = apk?.observations.orEmpty().groupBy { it.subject }
        val trafficBy = traffic?.observations.orEmpty().groupBy { it.subject }
        val timelineBy = timeline?.observations.orEmpty().groupBy { it.subject }
        val deepBy = deep?.observations.orEmpty().groupBy { it.subject }
        val timelineDay = timeline?.takenAt?.atZone(zone)?.toLocalDate()
        val deepDay = deep?.takenAt?.atZone(zone)?.toLocalDate()
        val signerChanged = openFindings(input.findings, Sources.APK, ApkRules.CERT_CHANGED)
        val gained = openFindings(input.findings, Sources.PERMISSIONS, PermissionRules.PERMISSION_GAINED)

        val out = ArrayList<Observation>()
        for (pkg in (perm.keys + apkBy.keys).sorted()) {
            val facts = LinkedHashMap<String, String>()
            val p = perm[pkg]?.let { AppPermissionState.of(pkg, it) }
            val a = apkBy[pkg].orEmpty()
            val t = timelineBy[pkg].orEmpty()
            val system = p?.isSystem == true || ApkKeys.isSystem(a)

            // Access (Permissions)
            if (p != null) {
                val held = p.grantedGroups - PermissionGroup.ACCESSIBILITY
                if (held.isNotEmpty()) facts[CrossKeys.HELD] = PermissionCatalog.describe(held)
                if (p.accessibilityEnabled) facts[CrossKeys.ACCESSIBILITY] = CrossKeys.ON
                perm[pkg]?.firstOrNull { it.key == PermissionKeys.TOGGLE_NETWORK }?.value?.let { facts[CrossKeys.NETWORK] = it }
            }

            // Origin and embedded SDKs (APK excavation)
            if (a.isNotEmpty()) {
                val installer = ApkKeys.value(a, ApkKeys.INSTALLER)
                val source = InstallSources.classify(installer, system)
                facts[CrossKeys.INSTALL_SOURCE] = source.value
                if (installer != null && installer != InstallSources.UNKNOWN_INSTALLER && source != InstallSource.SYSTEM) {
                    facts[CrossKeys.INSTALLED_BY] = installer
                }
                val sdks = a.mapNotNull { o -> ApkKeys.trackerId(o.key)?.let { id -> id to ApkKeys.categoriesOf(o.value) } }
                if (sdks.isNotEmpty()) {
                    val names = sdks.map { (id, _) -> TrackerCatalog.byId(id)?.name ?: id }.distinct().sorted()
                    val categories = sdks.flatMapTo(HashSet()) { it.second }
                    facts[CrossKeys.SDK_COUNT] = sdks.size.toString()
                    facts[CrossKeys.SDK_NAMES] = names.take(CrossKeys.NAMES_MAX).joinToString(",")
                    facts[CrossKeys.SDK_CATEGORIES] = TrackerCategory.entries.filter { it in categories }.joinToString(",") { it.label }
                }
            }

            // Tracking lookups (Traffic)
            trafficBy[pkg]?.let { obs ->
                val count = TrafficKeys.intValue(obs, TrafficKeys.TRACKER_DOMAINS30) ?: 0
                if (count > 0) {
                    facts[CrossKeys.DNS_TRACKERS] = count.toString()
                    TrafficKeys.value(obs, TrafficKeys.TRACKER_TOP)?.let { facts[CrossKeys.DNS_TRACKER_TOP] = it }
                }
            }

            // Use (Timeline) and sensor access (Deep mode): user apps only.
            if (!system && timelineDay != null && t.isNotEmpty()) {
                val lastUsed = CrossKeys.value(t, Sources.TIMELINE_LAST_USED)
                val idle = idleDays(lastUsed, CrossKeys.value(t, Sources.TIMELINE_FIRST_INSTALL), timelineDay)
                if (idle != null && idle >= CrossKeys.IDLE_THRESHOLD_DAYS) facts[CrossKeys.IDLE_DAYS] = idle.toString()
                val opsDates = deepBy[pkg]?.let { obs -> sensorDates(obs, deepDay) }.orEmpty()
                opsDates.forEach { (key, day) -> facts[key] = day.toString() }
                if (lastUsed != null && (CrossKeys.IDLE_DAYS in facts || opsDates.isNotEmpty())) facts[CrossKeys.LAST_OPENED] = lastUsed
            }

            // Open change findings from the sources.
            if (pkg in signerChanged) facts[CrossKeys.SIGNER_CHANGED] = "true"
            gained[pkg]?.let { facts[CrossKeys.GAINED] = it.evidence.removePrefix("Newly granted: ").trimEnd('.') }

            if (!worthKeeping(facts)) continue
            val label = p?.label ?: ApkKeys.value(a, ApkKeys.LABEL) ?: CrossKeys.value(t, Sources.TIMELINE_LABEL)
            label?.let { out += Observation(CrossKeys.TUNNEL_ID, pkg, CrossKeys.LABEL, it) }
            out += Observation(CrossKeys.TUNNEL_ID, pkg, CrossKeys.SYSTEM, system.toString())
            facts.forEach { (k, v) -> out += Observation(CrossKeys.TUNNEL_ID, pkg, k, v) }
        }

        for (id in Sources.ALL.sorted()) {
            val day = input.sources[id]?.takenAt?.atZone(zone)?.toLocalDate()
            out += Observation(CrossKeys.TUNNEL_ID, CrossKeys.SUMMARY, CrossKeys.sourceKey(id), day?.toString() ?: CrossKeys.NONE)
        }
        return out
    }

    /** An app is listed when at least one fact needs a second tunnel to mean something, or is a red flag on its own. */
    private fun worthKeeping(facts: Map<String, String>): Boolean {
        val outside = InstallSource.of(facts[CrossKeys.INSTALL_SOURCE])?.outsideStore == true
        return CrossKeys.ACCESSIBILITY in facts || CrossKeys.SDK_COUNT in facts || CrossKeys.DNS_TRACKERS in facts ||
            CrossKeys.IDLE_DAYS in facts || CrossKeys.CAMERA_USED in facts || CrossKeys.MIC_USED in facts ||
            CrossKeys.SIGNER_CHANGED in facts || CrossKeys.GAINED in facts || (outside && CrossKeys.HELD in facts)
    }

    /** Days without use up to [asOf]: from the last use, or from the install when it was never opened. */
    fun idleDays(lastUsed: String?, firstInstall: String?, asOf: LocalDate): Long? {
        if (lastUsed == null) return null
        return if (lastUsed == Sources.TIMELINE_NEVER) daysBetween(firstInstall, asOf) else daysBetween(lastUsed, asOf)
    }

    private fun daysBetween(iso: String?, asOf: LocalDate): Long? = try {
        iso?.let { ChronoUnit.DAYS.between(LocalDate.parse(it), asOf) }
    } catch (_: DateTimeParseException) {
        null
    }

    /** Absolute dates of the last camera and microphone use, from Deep mode's coarse ages and its scan day. */
    private fun sensorDates(obs: List<Observation>, deepDay: LocalDate?): Map<String, LocalDate> {
        if (deepDay == null) return emptyMap()
        val out = LinkedHashMap<String, LocalDate>()
        listOf(Sources.DEEP_CAMERA to CrossKeys.CAMERA_USED, Sources.DEEP_RECORD_AUDIO to CrossKeys.MIC_USED).forEach { (op, key) ->
            val days = Sources.deepAgeDays(CrossKeys.value(obs, Sources.deepLastKey(op))) ?: return@forEach
            out[key] = deepDay.minusDays(days.toLong())
        }
        return out
    }

    /** Package -> the open finding of [kind] raised by [tunnelId]. */
    private fun openFindings(findings: List<Finding>, tunnelId: String, kind: String): Map<String, Finding> =
        findings.filter { it.tunnelId == tunnelId && it.kind == kind }.associateBy { it.subject }

    /** Source tunnel ids for the summary, with their data dates; for display. */
    fun sourceDates(observations: List<Observation>): Map<String, String> =
        observations.filter { it.subject == CrossKeys.SUMMARY && it.key.startsWith(CrossKeys.SOURCE_PREFIX) }
            .associate { it.key.removePrefix(CrossKeys.SOURCE_PREFIX) to it.value }
}
