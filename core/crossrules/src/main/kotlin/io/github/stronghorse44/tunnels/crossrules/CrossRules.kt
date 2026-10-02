package io.github.stronghorse44.tunnels.crossrules

import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import io.github.stronghorse44.tunnels.permrules.PermissionGroup
import io.github.stronghorse44.tunnels.trackers.TrackerCategory
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Findings that need two tunnels to see. Each rule reads the joined facts of one app ([CrossKeys]) and names the
 * tunnels its evidence comes from. All are state findings: they clear when either side of the join changes.
 */
object CrossRules {
    const val SIDELOADED_ACCESSIBILITY = "SIDELOADED_ACCESSIBILITY"
    const val SIDELOADED_MESSAGES = "SIDELOADED_MESSAGES"
    const val TRACKERS_WITH_PERSONAL_DATA = "TRACKERS_WITH_PERSONAL_DATA"
    const val ACCESSIBILITY_WITH_TRACKERS = "ACCESSIBILITY_WITH_TRACKERS"
    const val IDLE_WITH_ACCESS = "IDLE_WITH_ACCESS"
    const val SENSOR_USE_UNOPENED = "SENSOR_USE_UNOPENED"
    const val NEW_SIGNER_NEW_ACCESS = "NEW_SIGNER_NEW_ACCESS"

    /** Kinds whose first action is Android's accessibility settings. */
    val ACCESSIBILITY_KINDS: Set<String> = setOf(SIDELOADED_ACCESSIBILITY, ACCESSIBILITY_WITH_TRACKERS)

    /** SDK categories whose business is collecting data about the user, as opposed to crashes or app performance. */
    private val HARVESTING = setOf(TrackerCategory.ADS, TrackerCategory.ATTRIBUTION, TrackerCategory.LOCATION, TrackerCategory.IDENTIFICATION).map { it.label }.toSet()
    private val PERSONAL = labels(PermissionGroup.LOCATION_BACKGROUND, PermissionGroup.LOCATION_FINE, PermissionGroup.CONTACTS, PermissionGroup.SMS, PermissionGroup.CALL_LOG)
    private val PERSONAL_WARN = labels(PermissionGroup.LOCATION_BACKGROUND, PermissionGroup.CONTACTS, PermissionGroup.SMS, PermissionGroup.CALL_LOG)
    private val MESSAGES = labels(PermissionGroup.SMS, PermissionGroup.CALL_LOG)
    private val IDLE_WARN = labels(PermissionGroup.LOCATION_BACKGROUND, PermissionGroup.MICROPHONE, PermissionGroup.CAMERA, PermissionGroup.SMS, PermissionGroup.CALL_LOG)

    private fun labels(vararg groups: PermissionGroup) = groups.map { it.label }.toSet()

    /** One app's joined facts. */
    private class App(val pkg: String, obs: List<Observation>) {
        private val byKey = obs.associate { it.key to it.value }
        val system = byKey[CrossKeys.SYSTEM] == "true"
        val source = InstallSource.of(byKey[CrossKeys.INSTALL_SOURCE])
        val installer = byKey[CrossKeys.INSTALLED_BY]
        val held = CrossKeys.list(byKey[CrossKeys.HELD])
        val accessibility = byKey[CrossKeys.ACCESSIBILITY] == CrossKeys.ON
        val networkOn = byKey[CrossKeys.NETWORK] == "on"
        val sdkCount = byKey[CrossKeys.SDK_COUNT]?.toIntOrNull() ?: 0
        val sdkNames = CrossKeys.list(byKey[CrossKeys.SDK_NAMES])
        val sdkCategories = CrossKeys.list(byKey[CrossKeys.SDK_CATEGORIES])
        val dnsTrackers = byKey[CrossKeys.DNS_TRACKERS]?.toIntOrNull() ?: 0
        val dnsTop = CrossKeys.list(byKey[CrossKeys.DNS_TRACKER_TOP])
        val idleDays = byKey[CrossKeys.IDLE_DAYS]?.toLongOrNull()
        val lastOpened = byKey[CrossKeys.LAST_OPENED]
        val cameraUsed = byKey[CrossKeys.CAMERA_USED]
        val micUsed = byKey[CrossKeys.MIC_USED]
        val signerChanged = byKey[CrossKeys.SIGNER_CHANGED] == "true"
        val gained = byKey[CrossKeys.GAINED]

        /** "Firebase Analytics, AppLovin MAX and 2 more" from the listed names and the true count. */
        fun sdks(): String {
            val rest = sdkCount - sdkNames.size
            return if (rest > 0) sdkNames.joinToString(", ") + " and $rest more" else join(sdkNames)
        }
    }

    private fun apps(ctx: RuleContext): List<App> =
        ctx.bySubject().filterKeys { it != CrossKeys.SUMMARY }.map { (pkg, obs) -> App(pkg, obs) }

    private fun source(ctx: RuleContext, tunnelId: String): LocalDate? =
        ctx.current.firstOrNull { it.subject == CrossKeys.SUMMARY && it.key == CrossKeys.sourceKey(tunnelId) }?.value?.let(::date)

    private fun draft(ctx: RuleContext, app: App, kind: String, severity: Severity, evidence: String) =
        FindingDraft(ctx.tunnelId, app.pkg, kind, severity, evidence)

    /** Accessibility service on, installed outside a store: the banking-malware pattern. CRITICAL from a file or adb. */
    val sideloadedAccessibility = FindingRule { ctx ->
        apps(ctx).mapNotNull { app ->
            val source = app.source ?: return@mapNotNull null
            if (app.system || !app.accessibility || !source.outsideStore) return@mapNotNull null
            val severity = if (source == InstallSource.OTHER) Severity.WARN else Severity.CRITICAL
            draft(
                ctx, app, SIDELOADED_ACCESSIBILITY, severity,
                "Its accessibility service is on, so it can read the screen and act in any app (Permissions), and it " +
                    "${InstallSources.describe(source, app.installer)} (APK excavation). That pairing is how most Android banking " +
                    "malware works. Keep the service on only if you know exactly why this app needs it.",
            )
        }
    }

    /** SMS or call log granted to an app from a file or adb. */
    val sideloadedMessages = FindingRule { ctx ->
        apps(ctx).mapNotNull { app ->
            val source = app.source ?: return@mapNotNull null
            if (app.system || (source != InstallSource.FILE && source != InstallSource.UNKNOWN)) return@mapNotNull null
            val messages = app.held.filter { it in MESSAGES }
            if (messages.isEmpty()) return@mapNotNull null
            draft(
                ctx, app, SIDELOADED_MESSAGES, Severity.WARN,
                "Holds ${join(messages)} (Permissions) and ${InstallSources.describe(source, app.installer)} (APK excavation). " +
                    "Text messages carry sign-in codes, and no store reviewed this app.",
            )
        }
    }

    /** Data-collecting SDKs inside an app that holds location, contacts or messages, with the network on. */
    val trackersWithPersonalData = FindingRule { ctx ->
        apps(ctx).mapNotNull { app ->
            if (app.system || !app.networkOn || app.sdkCategories.none { it in HARVESTING }) return@mapNotNull null
            val personal = app.held.filter { it in PERSONAL }
            if (personal.isEmpty()) return@mapNotNull null
            val confirmed = app.dnsTrackers > 0
            val severity = if (confirmed || personal.any { it in PERSONAL_WARN }) Severity.WARN else Severity.NOTICE
            val categories = app.sdkCategories.filter { it in HARVESTING }
            val traffic = if (confirmed) {
                " During Traffic sessions it looked up ${plural(app.dnsTrackers, "tracking domain")}" +
                    (if (app.dnsTop.isNotEmpty()) ": ${app.dnsTop.joinToString(", ")}." else ".")
            } else {
                ""
            }
            draft(
                ctx, app, TRACKERS_WITH_PERSONAL_DATA, severity,
                "Holds ${join(personal)} (Permissions) and embeds ${app.sdks()} for ${join(categories)} (APK excavation). " +
                    "Embedded SDKs run with the app's permissions, so they can read what it holds, and its Network toggle is on.$traffic",
            )
        }
    }

    /** Accessibility service on in an app with tracker SDKs (outside-store apps are left to [sideloadedAccessibility]). */
    val accessibilityWithTrackers = FindingRule { ctx ->
        apps(ctx).mapNotNull { app ->
            if (app.system || !app.accessibility || app.sdkCount == 0) return@mapNotNull null
            if (app.source?.outsideStore == true) return@mapNotNull null
            draft(
                ctx, app, ACCESSIBILITY_WITH_TRACKERS, Severity.WARN,
                "Its accessibility service is on, so it receives what is on your screen (Permissions), and it embeds " +
                    "${app.sdks()} (APK excavation). That SDK code runs in the same app as the service.",
            )
        }
    }

    /** An app unused for weeks that still holds sensitive access. */
    val idleWithAccess = FindingRule { ctx ->
        val asOf = source(ctx, CrossKeys.Sources.TIMELINE)
        apps(ctx).mapNotNull { app ->
            val idle = app.idleDays ?: return@mapNotNull null
            if (app.system || (app.held.isEmpty() && !app.accessibility)) return@mapNotNull null
            val holds = (if (app.accessibility) listOf("an accessibility service") else emptyList()) + app.held
            val severity = if (app.accessibility || app.held.any { it in IDLE_WARN }) Severity.WARN else Severity.NOTICE
            val unused = if (app.lastOpened == CrossKeys.NEVER) "Never opened in the $idle days since it was installed"
            else "Not opened for $idle days" + (app.lastOpened?.let { " (last on $it)" } ?: "")
            val until = asOf?.let { ", up to the Timeline scan on $it," } ?: ""
            draft(
                ctx, app, IDLE_WITH_ACCESS, severity,
                "$unused$until yet it still holds ${join(holds)} (Permissions). Revoke what it no longer needs, or uninstall it: " +
                    "Android's own reset of unused apps waits about 90 days and can be switched off per app.",
            )
        }
    }

    /** Camera or microphone used on a day after the app was last opened, as far as Timeline can tell. */
    val sensorUseUnopened = FindingRule { ctx ->
        val asOf = source(ctx, CrossKeys.Sources.TIMELINE) ?: return@FindingRule emptyList()
        apps(ctx).mapNotNull { app ->
            if (app.system) return@mapNotNull null
            val opened = app.lastOpened ?: return@mapNotNull null
            val openedDay = if (opened == CrossKeys.NEVER) null else date(opened) ?: return@mapNotNull null
            val uses = listOfNotNull(
                app.cameraUsed?.let(::date)?.let { "camera" to it },
                app.micUsed?.let(::date)?.let { "microphone" to it },
            ).filter { (_, day) ->
                // Timeline must cover the day of the use, and the use must come over a day after the last open.
                !day.isAfter(asOf) && (openedDay == null || day.isAfter(openedDay.plusDays(1)))
            }
            if (uses.isEmpty()) return@mapNotNull null
            val used = uses.joinToString(" and ") { (what, day) -> "the $what on $day" }
            val last = if (openedDay == null) "it has never been opened" else "it was last opened on $openedDay"
            draft(
                ctx, app, SENSOR_USE_UNOPENED, Severity.WARN,
                "Used $used (Deep mode), but $last (Timeline, up to $asOf). Apps rarely need the camera or microphone while " +
                    "you are not using them: check its permissions and whether it runs in the background.",
            )
        }
    }

    /** A new signing key and new permissions in the same app: the shape of a takeover. */
    val newSignerNewAccess = FindingRule { ctx ->
        apps(ctx).mapNotNull { app ->
            if (!app.signerChanged || app.gained.isNullOrBlank()) return@mapNotNull null
            draft(
                ctx, app, NEW_SIGNER_NEW_ACCESS, Severity.CRITICAL,
                "An update was signed with a different key (APK excavation) and the app also gained ${app.gained} (Permissions). " +
                    "A new signer plus new access is what an app takeover looks like. If you did not expect its developer to change keys, uninstall it.",
            )
        }
    }

    val all: List<FindingRule> = listOf(
        newSignerNewAccess, sideloadedAccessibility, sideloadedMessages, sensorUseUnopened,
        trackersWithPersonalData, accessibilityWithTrackers, idleWithAccess,
    )

    private fun date(iso: String): LocalDate? = try {
        LocalDate.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

    /** "a", "a and b", "a, b and c". */
    fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
