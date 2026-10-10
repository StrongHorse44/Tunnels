package io.github.stronghorse44.tunnels.watchrules

import io.github.stronghorse44.tunnels.model.Finding
import io.github.stronghorse44.tunnels.model.Severity
import java.time.Instant
import java.util.Locale

/** One row of the inbox: a finding on its own, or several of one kind from one tunnel folded into one row. */
sealed interface InboxRow {
    /** Stable across refreshes, for the list's keys and for remembering which groups are open. */
    val key: String
    val severity: Severity
    val lastSeen: Instant

    data class Single(val finding: Finding) : InboxRow {
        override val key: String get() = finding.id
        override val severity: Severity get() = finding.severity
        override val lastSeen: Instant get() = finding.lastSeen
    }

    /** [findings] share [tunnelId] and [kind], most severe and most recent first; [newCount] of them are new. */
    data class Group(val tunnelId: String, val kind: String, val findings: List<Finding>, val newCount: Int) : InboxRow {
        override val key: String get() = "group|$tunnelId|$kind"
        override val severity: Severity get() = findings.maxOf { it.severity }
        override val lastSeen: Instant get() = findings.maxOf { it.lastSeen }
        val title: String get() = InboxRows.title(kind, findings.size)
    }
}

/**
 * Folds the inbox: [MIN_GROUP] or more open findings of one kind from one tunnel become one row ("14 Wi-Fi access points
 * keep turning up"), so a scan that raises a dozen notices of one kind reads as one line. Rows keep the inbox's order
 * (most severe, then most recent); a group sits where its most severe finding would. Pure, unit-tested.
 */
object InboxRows {
    const val MIN_GROUP = 3

    fun rows(findings: List<Finding>, lastVisit: Instant?): List<InboxRow> {
        val byKind = findings.groupBy { it.tunnelId to it.kind }
        val rows = byKind.flatMap { (tk, list) ->
            if (list.size < MIN_GROUP) list.map { InboxRow.Single(it) }
            else listOf(InboxRow.Group(tk.first, tk.second, Inbox.sort(list), list.count { Inbox.isNew(it, lastVisit) }))
        }
        return rows.sortedWith(
            compareByDescending<InboxRow> { it.severity }
                .thenByDescending { it.lastSeen }
                .thenBy { it.key },
        )
    }

    /**
     * What a group of [count] findings of [kind] is called: the plain words for kinds that come in numbers, otherwise
     * the kind itself in words ("PERMISSION_LOST" becomes "Permission lost").
     */
    fun title(kind: String, count: Int): String {
        val phrase = PHRASES[kind] ?: return "$count × ${words(kind)}"
        return "$count $phrase"
    }

    fun words(kind: String): String = kind.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Plural phrases, read after the count. Only kinds whose meaning the phrase says exactly; the rest use [words]. */
    private val PHRASES: Map<String, String> = mapOf(
        "WIFI_AP_REPEATED" to "Wi-Fi access points keep turning up",
        "WIFI_AP_FOLLOWING" to "Wi-Fi access points travelled with you",
        "WIFI_MANY_NAMES" to "devices broadcast many Wi-Fi names",
        "EVIL_TWIN_SUSPECT" to "possible impostor networks",
        "TRACKER_FOLLOWING" to "trackers seen following you",
        "TRACKER_STAYS" to "trackers keep turning up at one place",
        "UNFAMILIAR_TOWER" to "unfamiliar cell towers",
        "UNKNOWN_DEVICE" to "unknown devices on your network",
        "RISKY_SERVICE" to "risky services open on your network",
        "CAMERA_OPEN_WEB" to "cameras with an open web page",
        "TRACKER_SDK" to "apps with tracker SDKs",
        "PERMISSION_GAINED" to "apps gained permissions",
        "NEW_APP" to "new apps",
        "UNUSED_APP" to "unused apps",
        "WEAK_HARDENING" to "apps with weak hardening",
        "HARDENING_REGRESSED" to "apps whose hardening got weaker",
        "LOW_TARGET_SDK" to "apps built for an old Android version",
        "UNKNOWN_SYSTEM_PACKAGE" to "unknown system packages",
        "USER_CA_INSTALLED" to "certificate authorities you installed",
        "CA_ADDED" to "certificate authorities added",
    )
}
