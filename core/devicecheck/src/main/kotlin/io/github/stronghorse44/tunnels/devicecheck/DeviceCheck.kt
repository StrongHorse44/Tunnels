package io.github.stronghorse44.tunnels.devicecheck

/**
 * How a reading came out. Device checks verify Tunnels' own assumptions about this phone; they are not findings
 * (rule #7) and are never stored.
 */
enum class CheckStatus(val label: String) {
    /** The reading works as Tunnels assumes. */
    PASS("ok"),
    /** Worth knowing; nothing to fix. */
    NOTE("note"),
    /** Something to do before this reading can be confirmed, or a check only a person can make. */
    TODO("to do"),
    /** Works, with a caveat that changes what Tunnels can see. */
    WARN("warn"),
    /** The assumption does not hold here; findings that rely on it are unreliable. */
    FAIL("fail"),
}

/** Where a check's button leads. Resolved to an intent by the screen. */
sealed interface CheckAction {
    val label: String

    /** A Settings screen by action; [forThisApp] adds Tunnels' package (App info, notification settings). */
    data class OpenSettings(val action: String, override val label: String, val forThisApp: Boolean = false) : CheckAction

    data class OpenTunnel(val tunnelId: String, override val label: String) : CheckAction

    /** A screen of this app reached by its same-package action (the findings inbox, snapshots). */
    data class OpenScreen(val action: String, override val label: String) : CheckAction

    /** The special access [accessId] of [tunnelId], opened the way the tunnel's own gate opens it. */
    data class GrantAccess(val tunnelId: String, val accessId: String, override val label: String) : CheckAction
}

data class CheckResult(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val action: CheckAction? = null,
)

data class CheckGroup(val title: String, val results: List<CheckResult>)
