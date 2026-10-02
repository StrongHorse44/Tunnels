package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.crossrules.CrossKeys.Sources
import io.github.stronghorse44.tunnels.dns.TrackerDomains
import io.github.stronghorse44.tunnels.dns.TrafficKeys
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.permrules.AppPermissionState
import io.github.stronghorse44.tunnels.permrules.PermissionCatalog
import io.github.stronghorse44.tunnels.permrules.PermissionGroup
import io.github.stronghorse44.tunnels.trackers.ApkKeys
import io.github.stronghorse44.tunnels.trackers.ApkRules
import io.github.stronghorse44.tunnels.trackers.ExportedCounts
import io.github.stronghorse44.tunnels.trackers.GooglePlay
import io.github.stronghorse44.tunnels.trackers.Tracker
import io.github.stronghorse44.tunnels.trackers.TrackerCatalog

/**
 * Everything Tunnels' latest scans say about one app, as readable rows grouped by what they describe. Pure: the
 * screen hands in each tunnel's latest observations of the app. Facts, not findings: the app's findings are listed
 * beside it with their actions.
 */
data class AppDossier(val packageName: String, val label: String, val sections: List<Section>, val missing: List<String>) {
    enum class Tone { PLAIN, GOOD, WARN }

    data class Row(val label: String, val value: String, val tone: Tone = Tone.PLAIN)

    data class Section(val title: String, val rows: List<Row>)

    companion object {
        /** Tunnels the page reads, with the name shown when one has never scanned this app. */
        val SOURCES: Map<String, String> = linkedMapOf(
            Sources.PERMISSIONS to "Permissions",
            Sources.APK to "APK excavation",
            Sources.TRAFFIC to "Traffic",
            Sources.TIMELINE to "Timeline",
        )

        /** [byTunnel] maps a tunnel id to its latest observations of [pkg] (other subjects are ignored). */
        fun of(pkg: String, byTunnel: Map<String, List<Observation>>): AppDossier {
            fun obs(id: String) = byTunnel[id].orEmpty().filter { it.subject == pkg }
            val perm = obs(Sources.PERMISSIONS)
            val apk = obs(Sources.APK)
            val traffic = obs(Sources.TRAFFIC)
            val timeline = obs(Sources.TIMELINE)
            val p = perm.takeIf { it.isNotEmpty() }?.let { AppPermissionState.of(pkg, it) }
            val label = p?.label?.takeIf { it.isNotBlank() } ?: ApkKeys.value(apk, ApkKeys.LABEL) ?: CrossKeys.value(timeline, Sources.TIMELINE_LABEL) ?: pkg

            val sections = listOfNotNull(
                origin(pkg, apk),
                access(p),
                build(apk),
                sdks(pkg, apk),
                network(traffic),
                use(timeline),
            ).filter { it.rows.isNotEmpty() }
            val missing = SOURCES.filterKeys { obs(it).isEmpty() }.values.toList()
            return AppDossier(pkg, label, sections, missing)
        }

        private fun origin(pkg: String, apk: List<Observation>): Section? {
            if (apk.isEmpty()) return null
            val system = ApkKeys.isSystem(apk)
            val installer = ApkKeys.value(apk, ApkKeys.INSTALLER)
            val source = InstallSources.classify(installer, system)
            val cert = ApkKeys.value(apk, ApkKeys.CERT_SHA256)
            val rotations = ApkKeys.value(apk, ApkKeys.CERT_LINEAGE)?.toIntOrNull() ?: 0
            return Section(
                "Origin",
                listOfNotNull(
                    ApkKeys.value(apk, ApkKeys.VERSION)?.let { Row("version", it) },
                    Row("installed", installedFrom(source, installer), if (source.outsideStore) Tone.WARN else Tone.PLAIN),
                    cert?.takeIf { it != "none" }?.let {
                        Row("signed by", ApkKeys.shortFingerprint(it) + if (GooglePlay.isGooglePlay(pkg, it)) " (Google)" else "")
                    },
                    if (rotations > 0) Row("key rotations", "$rotations earlier signing key${if (rotations == 1) "" else "s"}") else null,
                ),
            )
        }

        private fun installedFrom(source: InstallSource, installer: String?): String = when (source) {
            InstallSource.SYSTEM -> "with the OS"
            InstallSource.STORE -> "from " + (InstallSources.STORES[installer] ?: installer ?: "a store")
            InstallSource.FILE -> "from an APK file"
            InstallSource.UNKNOWN -> "no installer on record (adb, or a store since removed)"
            InstallSource.OTHER -> "by " + (InstallSources.KNOWN_OTHERS[installer] ?: installer ?: "another app")
        }

        private fun access(p: AppPermissionState?): Section? {
            p ?: return null
            val groups = p.grantedGroups - PermissionGroup.ACCESSIBILITY
            val sensitive = groups.filter { it.isSensitive }
            return Section(
                "Access",
                listOfNotNull(
                    Row("holds", if (groups.isEmpty()) "no runtime permissions" else PermissionCatalog.describe(groups), if (sensitive.isNotEmpty()) Tone.WARN else Tone.PLAIN),
                    Row("network", if (p.networkOn) "on" else "off", if (p.networkOn) Tone.PLAIN else Tone.GOOD),
                    if (p.accessibilityEnabled) Row("accessibility service", "on: it can read the screen and act in apps", Tone.WARN) else null,
                ),
            )
        }

        private fun build(apk: List<Observation>): Section? {
            if (apk.isEmpty()) return null
            val target = ApkKeys.value(apk, ApkKeys.TARGET_SDK)?.toIntOrNull()?.takeIf { it > 0 }
            val exported = ExportedCounts.parse(ApkKeys.value(apk, ApkKeys.EXPORTED_OPEN))
            val providers = ApkKeys.list(ApkKeys.value(apk, ApkKeys.OPEN_PROVIDERS))
            val abis = ApkKeys.value(apk, ApkKeys.NATIVE_ABIS)
            return Section(
                "Build",
                listOfNotNull(
                    target?.let { Row("targets", "Android API $it", if (it < ApkRules.MIN_TARGET_SDK) Tone.WARN else Tone.PLAIN) },
                    if (ApkKeys.value(apk, ApkKeys.DEBUGGABLE) == "true") Row("debuggable", "yes: a debug build", Tone.WARN) else null,
                    ApkKeys.value(apk, ApkKeys.CLEARTEXT)?.let {
                        if (it == "true") Row("unencrypted HTTP", "allowed by its manifest", Tone.WARN) else Row("unencrypted HTTP", "not allowed by default", Tone.GOOD)
                    },
                    exported?.let {
                        Row(
                            "open to other apps",
                            "${it.activities} screens, ${it.services} services, ${it.receivers} receivers, ${it.providers} data providers with no permission",
                            if (it.providers > 0) Tone.WARN else Tone.PLAIN,
                        )
                    },
                    if (providers.isNotEmpty()) Row("unguarded providers", providers.joinToString(", "), Tone.WARN) else null,
                    abis?.takeIf { it != ApkKeys.NO_ABIS }?.let { Row("native code", it, if (ApkKeys.is32BitOnly(it)) Tone.WARN else Tone.PLAIN) },
                    ApkKeys.value(apk, ApkKeys.SIZE_MB)?.let { Row("size", "$it MB") },
                ),
            )
        }

        private fun sdks(pkg: String, apk: List<Observation>): Section? {
            if (apk.isEmpty()) return null
            if (GooglePlay.isGooglePlay(pkg, apk)) {
                return Section("Embedded SDKs", listOf(Row("trackers", "none counted: this is Google's own Play app, the service those SDKs report to")))
            }
            val trackers = apk.mapNotNull { o -> ApkKeys.trackerId(o.key)?.let { TrackerCatalog.byId(it) } }
            ApkKeys.value(apk, ApkKeys.SDK_SKIPPED)?.let { return Section("Embedded SDKs", listOf(Row("trackers", "not read: $it"))) }
            if (trackers.isEmpty()) return Section("Embedded SDKs", listOf(Row("trackers", "none known", Tone.GOOD)))
            val rows = trackers.groupBy { TrackerDomains.companyOf(it.vendor) }
                .entries.sortedWith(compareByDescending<Map.Entry<String, List<Tracker>>> { it.value.size }.thenBy { it.key })
                .map { (company, list) -> Row(company, list.map { it.name }.sorted().joinToString(", "), Tone.WARN) }
            return Section("Embedded SDKs", rows)
        }

        private fun network(traffic: List<Observation>): Section? {
            if (traffic.isEmpty()) return null
            val domains = TrafficKeys.intValue(traffic, TrafficKeys.DOMAINS30) ?: 0
            val queries = TrafficKeys.intValue(traffic, TrafficKeys.QUERIES30) ?: 0
            val top = TrafficKeys.list(TrafficKeys.value(traffic, TrafficKeys.TOP))
            val trackerTop = TrafficKeys.list(TrafficKeys.value(traffic, TrafficKeys.TRACKER_TOP))
            val blocked = TrafficKeys.intValue(traffic, TrafficKeys.BLOCKED30) ?: 0
            return Section(
                "Network (Traffic sessions, 30 days)",
                listOfNotNull(
                    Row("looked up", "$queries lookups of $domains domains" + if (top.isNotEmpty()) ": ${top.joinToString(", ")}" else ""),
                    *TrackerDomains.companies(trackerTop).map { (company, list) -> Row(company, list.joinToString(", "), Tone.WARN) }.toTypedArray(),
                    if (blocked > 0) Row("blocked", "$blocked tracker lookups", Tone.GOOD) else null,
                ),
            )
        }

        private fun use(timeline: List<Observation>): Section? {
            val last = CrossKeys.value(timeline, Sources.TIMELINE_LAST_USED) ?: return null
            return Section(
                "Use",
                listOfNotNull(
                    Row("last opened", if (last == Sources.TIMELINE_NEVER) "never" else last),
                    CrossKeys.value(timeline, Sources.TIMELINE_FIRST_INSTALL)?.let { Row("installed on", it) },
                ),
            )
        }
    }
}
