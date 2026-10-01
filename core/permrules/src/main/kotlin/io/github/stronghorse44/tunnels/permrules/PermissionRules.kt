package io.github.stronghorse44.tunnels.permrules

import io.github.stronghorse44.tunnels.model.DiffEntry
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity

/** Findings of the permissions tunnel. State rules read the current scan; change rules read the diff and are sticky. */
object PermissionRules {
    const val DANGEROUS_GRANTED = "DANGEROUS_GRANTED"
    const val RISKY_COMBO = "RISKY_COMBO"
    const val PERMISSION_GAINED = "PERMISSION_GAINED"
    const val PERMISSION_LOST = "PERMISSION_LOST"
    const val NETWORK_ENABLED = "NETWORK_ENABLED"
    const val NEW_APP = "NEW_APP"

    /** System apps only count as dangerous when they hold one of these. */
    private val systemAppAlarmGroups = setOf(PermissionGroup.LOCATION_BACKGROUND, PermissionGroup.SMS, PermissionGroup.CALL_LOG)

    /** "Granted: background location, camera" for every app holding a sensitive group; system apps only for the alarm groups. */
    val dangerousGranted = FindingRule { ctx ->
        AppPermissionState.all(ctx.current).values.mapNotNull { app ->
            val groups = app.grantedGroups
            if (groups.isEmpty()) return@mapNotNull null
            if (app.isSystem && groups.none { it in systemAppAlarmGroups }) return@mapNotNull null
            FindingDraft(ctx.tunnelId, app.packageName, DANGEROUS_GRANTED, Severity.NOTICE, "Granted: ${PermissionCatalog.describe(groups)}")
        }
    }

    /** Pairs of grants that together let an app do far more than either alone. Non-system apps only. */
    val riskyCombo = FindingRule { ctx ->
        AppPermissionState.all(ctx.current).values.mapNotNull { app ->
            if (app.isSystem) return@mapNotNull null
            val combos = combosOf(app)
            if (combos.isEmpty()) null
            else FindingDraft(ctx.tunnelId, app.packageName, RISKY_COMBO, Severity.WARN, combos.joinToString("; "))
        }
    }

    /** Readable names of the risky combinations [app] holds. */
    fun combosOf(app: AppPermissionState): List<String> = buildList {
        if (app.has(PermissionGroup.LOCATION_BACKGROUND) && app.networkOn) add("Background location + network on")
        if (app.has(PermissionGroup.ACCESSIBILITY) && app.networkOn) add("Accessibility service + network on")
        if (app.has(PermissionGroup.OVERLAY) && app.has(PermissionGroup.ACCESSIBILITY)) add("Draw over other apps + accessibility service")
        if ((app.has(PermissionGroup.SMS) || app.has(PermissionGroup.CALL_LOG)) && app.networkOn) add("SMS or call log + network on")
        if (app.has(PermissionGroup.STORAGE_ALL_FILES) && app.networkOn) add("All files + network on")
    }

    /** A permission went denied -> granted, or appeared already granted (not for apps that are new this scan). */
    val permissionGained = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val newApps = newSubjects(ctx)
        ctx.diff.filter { it.key.subject !in newApps }
            .mapNotNull { e ->
                when (e) {
                    is DiffEntry.Changed -> e.after.takeIf { PermissionKeys.isPermKey(it.key) && e.before.value == PermissionKeys.DENIED && it.value == PermissionKeys.GRANTED }
                    is DiffEntry.Added -> e.observation.takeIf { PermissionKeys.isPermKey(it.key) && it.value == PermissionKeys.GRANTED }
                    is DiffEntry.Removed -> null
                }
            }
            .groupBy({ it.subject }, { PermissionKeys.permissionOf(it.key) })
            .map { (subject, perms) ->
                FindingDraft(ctx.tunnelId, subject, PERMISSION_GAINED, Severity.WARN, "Newly granted: ${PermissionCatalog.describePermissions(perms)}", sticky = true)
            }
    }

    /** A permission went granted -> denied, or a granted one is no longer requested (not for apps removed this scan). */
    val permissionLost = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val gone = removedSubjects(ctx)
        ctx.diff.filter { it.key.subject !in gone }
            .mapNotNull { e ->
                when (e) {
                    is DiffEntry.Changed -> e.after.takeIf { PermissionKeys.isPermKey(it.key) && e.before.value == PermissionKeys.GRANTED && it.value == PermissionKeys.DENIED }
                    is DiffEntry.Removed -> e.observation.takeIf { PermissionKeys.isPermKey(it.key) && it.value == PermissionKeys.GRANTED }
                    is DiffEntry.Added -> null
                }
            }
            .groupBy({ it.subject }, { PermissionKeys.permissionOf(it.key) })
            .map { (subject, perms) ->
                FindingDraft(ctx.tunnelId, subject, PERMISSION_LOST, Severity.INFO, "No longer granted: ${PermissionCatalog.describePermissions(perms)}", sticky = true)
            }
    }

    /** The Network toggle went from off (or not requested) to on. */
    val networkEnabled = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        ctx.diff.mapNotNull { e ->
            val c = e as? DiffEntry.Changed ?: return@mapNotNull null
            if (c.after.key != PermissionKeys.TOGGLE_NETWORK || c.after.value != PermissionKeys.ON || c.before.value == PermissionKeys.ON) return@mapNotNull null
            val was = if (c.before.value == PermissionKeys.OFF) "off" else "not requested"
            FindingDraft(ctx.tunnelId, c.after.subject, NETWORK_ENABLED, Severity.WARN, "Network toggle was $was, now on", sticky = true)
        }
    }

    /** An app appeared since the last scan; evidence names what it already holds. */
    val newApp = FindingRule { ctx ->
        if (ctx.isFirstScan) return@FindingRule emptyList()
        val states = AppPermissionState.all(ctx.current)
        newSubjects(ctx).sorted().map { pkg ->
            val app = states[pkg]
            val groups = app?.grantedGroups.orEmpty()
            val held = if (groups.isEmpty()) "no sensitive permissions granted" else "granted: ${PermissionCatalog.describe(groups)}"
            val net = if (app?.networkOn == true) ", network on" else ""
            FindingDraft(ctx.tunnelId, pkg, NEW_APP, Severity.NOTICE, "New app \"${app?.displayName ?: pkg}\" with $held$net", sticky = true)
        }
    }

    val all: List<FindingRule> = listOf(dangerousGranted, riskyCombo, permissionGained, permissionLost, networkEnabled, newApp)

    private fun newSubjects(ctx: RuleContext): Set<String> =
        ctx.diff.mapNotNullTo(HashSet()) { (it as? DiffEntry.Added)?.observation?.takeIf { o -> o.key == PermissionKeys.APP_LABEL }?.subject }

    private fun removedSubjects(ctx: RuleContext): Set<String> =
        ctx.diff.mapNotNullTo(HashSet()) { (it as? DiffEntry.Removed)?.observation?.takeIf { o -> o.key == PermissionKeys.APP_LABEL }?.subject }
}
