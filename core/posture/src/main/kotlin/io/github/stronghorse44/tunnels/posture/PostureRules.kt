package io.github.stronghorse44.tunnels.posture

import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Severity

/**
 * Findings of the posture subject. All are state findings (not sticky): they clear when the setting is fixed. Only a
 * WEAK reading of a confirmed item with a kind raises one; unknown never does. Evidence ends with the Settings path.
 */
object PostureRules {
    const val POSTURE_UNREAD = "POSTURE_UNREAD"

    /** Auto reboot warns when off and notices beyond this. */
    const val AUTO_REBOOT_MAX_HOURS = PostureReader.AUTO_REBOOT_MAX_HOURS

    /** A lock delay of this long or more is noted. */
    const val LOCK_DELAY_MAX_MS = PostureReader.LOCK_DELAY_MAX_MS

    private val exemptCount = Regex("""(\d+) exempt$""")

    /** Every kind these rules can raise. */
    val kinds: Set<String> = PostureKeys.ITEMS.mapNotNull { it.kind }.toSet() + POSTURE_UNREAD

    val all: List<FindingRule> = rulesFor(PostureKeys.ITEMS)

    /** The rules for [items]; [all] is this over the shipped table. */
    fun rulesFor(items: List<PostureItem>): List<FindingRule> =
        items.mapNotNull { it.kind }.distinct().map { kind -> stateRule(kind, items) } + unreadRule(items)

    /** The Settings screen for a posture finding; empty for [POSTURE_UNREAD], whose action the Android side supplies. */
    fun actionsFor(draft: FindingDraft, items: List<PostureItem> = PostureKeys.ITEMS): List<FindingAction> {
        val item = items.firstOrNull { it.kind == draft.kind } ?: return emptyList()
        val action = item.action ?: return emptyList()
        return listOf(FindingAction.OpenSettings(action, item.actionLabel ?: "Settings"))
    }

    private fun stateRule(kind: String, items: List<PostureItem>) = FindingRule { ctx ->
        val obs = ctx.current.filter { it.subject == PostureKeys.SUBJECT }
        if (obs.isEmpty()) return@FindingRule emptyList()
        val snapshot = PostureObservations.from(obs, items)
        val item = items.first { it.kind == kind }
        val reading = snapshot.reading(item.id)
        // The item's own flag counts too: observations stored before a flag was flipped back never raise a finding.
        if (reading == null || !item.confirmed || reading.state != PostureState.WEAK) return@FindingRule emptyList()
        val (severity, text) = judgement(item, reading, snapshot) ?: return@FindingRule emptyList()
        listOf(FindingDraft(ctx.tunnelId, PostureKeys.SUBJECT, kind, severity, withPath(text, item.path)))
    }

    private fun judgement(item: PostureItem, r: Reading, snapshot: PostureSnapshot): Pair<Severity, String>? {
        val value = r.value ?: return null
        return when (item.id) {
            PostureKeys.AUTO_REBOOT ->
                if (value == PostureReader.OFF) {
                    Severity.WARN to "Auto reboot is off. A locked phone stays in the after-first-unlock state, with more of its data " +
                        "decryptable, until someone restarts it."
                } else {
                    Severity.NOTICE to "Auto reboot waits $value, longer than GrapheneOS's $AUTO_REBOOT_MAX_HOURS h default."
                }
            PostureKeys.USB_PORT -> when (value) {
                PostureReader.USB_VALUES[PostureReader.USB_ON_MODE] ->
                    Severity.WARN to "The USB-C port accepts data while the phone is locked."
                PostureReader.USB_VALUES[PostureReader.USB_BEFORE_FIRST_UNLOCK_MODE] ->
                    Severity.NOTICE to "The USB-C port accepts new data connections before the first unlock after a restart."
                else -> null
            }
            PostureKeys.VPN_LOCKDOWN -> {
                val app = snapshot.reading(PostureKeys.VPN_ALWAYS_ON)?.value ?: "unknown app"
                val exempt = exemptCount.find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (value == "lockdown off") {
                    Severity.NOTICE to "Always-on VPN ($app) runs without Block connections without VPN, so apps can reach the network " +
                        "outside it while it starts or reconnects."
                } else if (exempt > 0) {
                    Severity.NOTICE to "$exempt ${if (exempt == 1) "app" else "apps"} may connect outside the always-on VPN lockdown."
                } else {
                    null
                }
            }
            PostureKeys.PRIVATE_DNS ->
                Severity.NOTICE to "Private DNS is off: every lookup goes unencrypted to the network's resolver. Automatic keeps Traffic sessions working."
            PostureKeys.PIN_SCRAMBLE ->
                Severity.NOTICE to "The lock-screen PIN pad is not scrambled, so smudges and onlookers can give the PIN away."
            PostureKeys.CLIPBOARD_DEFAULT ->
                Severity.NOTICE to "Apps may read the clipboard by default. GrapheneOS can make it opt-in."
            PostureKeys.CLIPBOARD_NOTICES ->
                Severity.NOTICE to "Clipboard access notices are off, so an app reading the clipboard goes unseen."
            PostureKeys.WIFI_AUTO_OFF ->
                Severity.INFO to "Wi-Fi stays on when no network is connected; GrapheneOS can turn it off after a set time."
            PostureKeys.BT_AUTO_OFF ->
                Severity.INFO to "Bluetooth stays on when nothing is connected; GrapheneOS can turn it off after a set time."
            PostureKeys.NFC_AUTO_OFF ->
                Severity.INFO to "NFC stays on when unused; GrapheneOS can turn it off after a set time."
            PostureKeys.SENSORS_DEFAULT ->
                Severity.INFO to "New apps get the Sensors permission without asking."
            PostureKeys.LOCK_DELAY ->
                Severity.INFO to "The phone locks $value after the screen turns off; whoever picks it up in that time gets in without the PIN."
            else -> null
        }
    }

    private fun withPath(text: String, path: String) = if (path.isBlank()) text else "$text $path."

    /** A command failed: the items it covers are unknown and their findings are hidden until the next full read. */
    private fun unreadRule(items: List<PostureItem>) = FindingRule { ctx ->
        val obs = ctx.current.filter { it.subject == PostureKeys.SUBJECT }
        if (obs.isEmpty()) return@FindingRule emptyList()
        val snapshot = PostureObservations.from(obs, items)
        val failed = PostureTable.entries.mapNotNull { t -> snapshot.reads[t]?.takeIf { it != PostureParser.OK }?.let { t to it } }
        if (failed.isEmpty()) return@FindingRule emptyList()
        val unknown = snapshot.readings.count { it.why == PostureWhy.UNREADABLE }
        val tables = failed.joinToString(", ") { (t, _) -> t.label }
        val reasons = failed.map { it.second }.distinct().joinToString(", ")
        listOf(
            FindingDraft(
                ctx.tunnelId, PostureKeys.SUBJECT, POSTURE_UNREAD, Severity.NOTICE,
                "Posture could not read $tables this scan ($reasons); $unknown ${if (unknown == 1) "item is" else "items are"} unknown " +
                    "and their findings are hidden until the next full read.",
            ),
        )
    }
}
