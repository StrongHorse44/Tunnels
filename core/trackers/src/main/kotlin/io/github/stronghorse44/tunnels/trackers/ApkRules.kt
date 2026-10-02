package io.github.stronghorse44.tunnels.trackers

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the apk_excavation tunnel. Pure functions over observations, unit-tested here. */
object ApkRules {
    const val TRACKER_SDK = "TRACKER_SDK"
    const val CERT_CHANGED = "CERT_CHANGED"
    const val INSTALLER_CHANGED = "INSTALLER_CHANGED"
    const val LOW_TARGET_SDK = "LOW_TARGET_SDK"
    const val ABI_32_ONLY = "ABI_32_ONLY"
    const val NEW_TRACKER = "NEW_TRACKER"
    const val DEBUGGABLE = "DEBUGGABLE"

    /** Apps targeting below this API level predate scoped storage and the modern permission model. */
    const val MIN_TARGET_SDK = 29
    /** This many trackers, or an ads and a location SDK together, raise TRACKER_SDK to WARN. */
    const val MANY_TRACKERS = 3
    private const val LIST_MAX = 6

    /**
     * State rule: the app embeds known SDKs. NOTICE, or WARN when there are many or ads meet location. Google's own Play
     * apps ([GooglePlay]) are the service those SDKs talk to, not apps embedding them, so they are left out.
     */
    val trackerSdk: FindingRule = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            val trackers = trackerEntries(obs)
            if (trackers.isEmpty() || GooglePlay.isGooglePlay(subject, obs)) return@mapNotNull null
            val categories = trackers.flatMapTo(HashSet()) { it.second }
            val severity = if (
                trackers.size >= MANY_TRACKERS ||
                (TrackerCategory.ADS in categories && TrackerCategory.LOCATION in categories)
            ) Severity.WARN else Severity.NOTICE
            FindingDraft(ctx.tunnelId, subject, TRACKER_SDK, severity, "Embeds " + describe(trackers))
        }
    }

    /** Sticky CRITICAL: the first signer's certificate is not the one seen before. */
    val certChanged: FindingRule = Rules.onChange(CERT_CHANGED, Severity.CRITICAL) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        if (c.key.key != ApkKeys.CERT_SHA256) return@onChange null
        "Signing certificate changed from ${ApkKeys.shortFingerprint(c.before.value)} to ${ApkKeys.shortFingerprint(c.after.value)}. " +
            "Only the original developer should be able to sign an update."
    }

    /** Sticky WARN: the app now reports a different installing package. */
    val installerChanged: FindingRule = Rules.onChange(INSTALLER_CHANGED, Severity.WARN) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        if (c.key.key != ApkKeys.INSTALLER) return@onChange null
        if (c.after.value == ApkKeys.UNKNOWN_INSTALLER) {
            "Install source changed from ${c.before.value} to unknown. " +
                "This also happens when the store that installed the app was itself uninstalled."
        } else {
            "Install source changed from ${c.before.value} to ${c.after.value}."
        }
    }

    /** State WARN: a user-installed app targets an old Android API level. */
    val lowTargetSdk: FindingRule = Rules.perSubject(LOW_TARGET_SDK, Severity.WARN) { _, obs ->
        if (ApkKeys.isSystem(obs)) return@perSubject null
        val target = ApkKeys.value(obs, ApkKeys.TARGET_SDK)?.toIntOrNull() ?: return@perSubject null
        // 0 means the level could not be read, not that the app targets API 0.
        if (target <= 0 || target >= MIN_TARGET_SDK) return@perSubject null
        "Targets Android API $target (below $MIN_TARGET_SDK): it runs under older storage and permission rules than current apps."
    }

    /** State WARN: a user-installed app built debuggable, which no store build is. */
    val debuggable: FindingRule = Rules.perSubject(DEBUGGABLE, Severity.WARN) { _, obs ->
        if (ApkKeys.isSystem(obs) || ApkKeys.value(obs, ApkKeys.DEBUGGABLE) != "true") return@perSubject null
        "Built as a debug build: whenever USB or wireless debugging is on, a connected computer can attach a debugger and " +
            "read everything the app holds. Store builds never are. Install the developer's release build, or uninstall it."
    }

    /** State INFO: native code without a 64-bit build. */
    val abi32Only: FindingRule = Rules.perSubject(ABI_32_ONLY, Severity.INFO) { _, obs ->
        val abis = ApkKeys.value(obs, ApkKeys.NATIVE_ABIS) ?: return@perSubject null
        if (!ApkKeys.is32BitOnly(abis)) return@perSubject null
        "Native code is 32-bit only ($abis); no 64-bit build is shipped."
    }

    /**
     * Sticky NOTICE: an update brought a tracker the app did not have before. Fires only for apps whose
     * version changed in the same diff, so a grown tracker catalog (a Tunnels update) cannot blame apps
     * that did not change. Freshly installed apps and apps whose dex was skipped last time are left out
     * too, since all their sdk keys look new.
     */
    val newTracker: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val updated = ctx.diff.filter { it is DiffEntry.Changed && it.key.key == ApkKeys.VERSION }.map { it.key.subject }.toSet()
        if (updated.isEmpty()) return@FindingRule emptyList()
        val newApps = ctx.diff.filter { it is DiffEntry.Added && it.key.key == ApkKeys.LABEL }.map { it.key.subject }.toSet()
        val wasSkipped = ctx.diff.filter { it is DiffEntry.Removed && it.key.key == ApkKeys.SDK_SKIPPED }.map { it.key.subject }.toSet()
        val googlePlay = ctx.bySubject().filter { (subject, obs) -> GooglePlay.isGooglePlay(subject, obs) }.keys
        ctx.diff.asSequence()
            .filterIsInstance<DiffEntry.Added>()
            .filter {
                ApkKeys.isTrackerKey(it.key.key) && it.key.subject in updated &&
                    it.key.subject !in newApps && it.key.subject !in wasSkipped && it.key.subject !in googlePlay
            }
            .groupBy { it.key.subject }
            .map { (subject, added) ->
                val trackers = added.map { trackerEntry(it.observation) }
                FindingDraft(ctx.tunnelId, subject, NEW_TRACKER, Severity.NOTICE, "An update added " + describe(trackers), sticky = true)
            }
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(trackerSdk, certChanged, installerChanged, lowTargetSdk, debuggable, abi32Only, newTracker)

    private fun trackerEntries(obs: List<Observation>): List<Pair<String, Set<TrackerCategory>>> =
        obs.filter { ApkKeys.isTrackerKey(it.key) }.map(::trackerEntry).sortedBy { TrackerCatalog.nameOf(it.first).lowercase() }

    private fun trackerEntry(o: Observation): Pair<String, Set<TrackerCategory>> =
        (ApkKeys.trackerId(o.key) ?: o.key) to ApkKeys.categoriesOf(o.value)

    /** "Firebase Analytics (analytics), AppsFlyer (attribution) and 2 more" */
    fun describe(trackers: List<Pair<String, Set<TrackerCategory>>>): String {
        val shown = trackers.take(LIST_MAX).joinToString(", ") { (id, cats) ->
            val name = TrackerCatalog.nameOf(id)
            if (cats.isEmpty()) name else "$name (${ApkKeys.categoriesValue(cats)})"
        }
        val rest = trackers.size - LIST_MAX
        return if (rest > 0) "$shown and $rest more" else shown
    }
}
