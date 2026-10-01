package io.github.stronghorse44.tunnels.syspkg

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the system_packages tunnel. Pure functions over observations, unit-tested here. */
object SysPkgRules {
    const val UNKNOWN_SYSTEM_PACKAGE = "UNKNOWN_SYSTEM_PACKAGE"
    const val SYSTEM_PACKAGE_ADDED = "SYSTEM_PACKAGE_ADDED"
    const val ENABLED_STATE_CHANGED = "ENABLED_STATE_CHANGED"
    const val OS_UPDATE_DETECTED = "OS_UPDATE_DETECTED"

    /** More than this many version changes in one diff is an OS update, not individual app updates. */
    const val OS_UPDATE_THRESHOLD = 20

    const val UNKNOWN_EVIDENCE = "System package not in the knowledge base; verify what it is."

    /** State INFO: a system package nobody vouches for, in a namespace that is neither AOSP, GrapheneOS nor Google. */
    val unknownSystemPackage: FindingRule = Rules.perSubject(UNKNOWN_SYSTEM_PACKAGE, Severity.INFO) { subject, obs ->
        if (subject == SysPkgKeys.SUMMARY) return@perSubject null
        if (SysPkgKeys.isKnown(obs)) return@perSubject null
        if (SysPkgKeys.value(obs, SysPkgKeys.NAMESPACE) != Namespace.OTHER.label) return@perSubject null
        UNKNOWN_EVIDENCE
    }

    /** Sticky WARN: a system package exists that was not there at the previous scan. */
    val systemPackageAdded: FindingRule = Rules.onAdded(SysPkgKeys.LABEL, SYSTEM_PACKAGE_ADDED, Severity.WARN) { o ->
        "System package \"${o.value}\" appeared since the last scan. System packages normally only change with an OS update."
    }

    /** Sticky NOTICE: a system package was enabled or disabled since the previous scan. */
    val enabledStateChanged: FindingRule = Rules.onChange(ENABLED_STATE_CHANGED, Severity.NOTICE) { e ->
        val c = e as? DiffEntry.Changed ?: return@onChange null
        if (c.key.key != SysPkgKeys.ENABLED) return@onChange null
        "Was ${SysPkgKeys.describeEnabled(c.before.value)}, now ${SysPkgKeys.describeEnabled(c.after.value)}."
    }

    /**
     * Sticky INFO on the summary subject: many packages changed version at once, which is what an OS
     * update looks like. Individual version changes are deliberately not findings.
     */
    val osUpdateDetected: FindingRule = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val changed = ctx.diff.count { it is DiffEntry.Changed && it.key.key == SysPkgKeys.VERSION }
        if (changed <= OS_UPDATE_THRESHOLD) return@FindingRule emptyList()
        listOf(
            FindingDraft(
                ctx.tunnelId,
                SysPkgKeys.SUMMARY,
                OS_UPDATE_DETECTED,
                Severity.INFO,
                "$changed system packages changed version since the last scan: this looks like an OS update. " +
                    "Check that the new version is the one you expected.",
                sticky = true,
            ),
        )
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(unknownSystemPackage, systemPackageAdded, enabledStateChanged, osUpdateDetected)
}
