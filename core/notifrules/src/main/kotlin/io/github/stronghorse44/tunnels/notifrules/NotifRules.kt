package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.engine.Rules
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.FindingRule
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.Severity

/** Finding rules of the notifications tunnel. All are state rules: they describe this week and clear when it quiets down. */
object NotifRules {
    const val NOISY_APP = "NOISY_APP"
    const val NIGHT_NOISE = "NIGHT_NOISE"
    const val SPOOFED_URGENCY = "SPOOFED_URGENCY"
    const val LOCK_SCREEN_EXPOSURE = "LOCK_SCREEN_EXPOSURE"
    const val LISTENER_DISCONNECTED = "LISTENER_DISCONNECTED"

    /** More than this many notifications a day over the week is NOTICE; more than [VERY_NOISY_PER_DAY] is WARN. */
    const val NOISY_PER_DAY = 30.0
    const val VERY_NOISY_PER_DAY = 100.0
    /** At least this many night-time posts in a week. */
    const val NIGHT_MIN = 5
    /** SPOOFED_URGENCY needs at least this many posts in the week so one loud promo does not count. */
    const val URGENCY_MIN_POSTS = 4

    /** Categories that legitimately wake people at night. */
    val nightExempt = setOf("alarm", "call", "missed_call", "reminder")
    /** Categories that have no business peeking at high importance. "" is "no category set". */
    val lowStakes = setOf("promo", "recommendation", "social", "")
    /** Categories whose contents are personal: messages, mail, social. */
    val personal = setOf("msg", "email", "social")

    val noisyApp: FindingRule = FindingRule { ctx ->
        ctx.bySubject().mapNotNull { (subject, obs) ->
            if (subject == NotifKeys.SUMMARY) return@mapNotNull null
            val perDay = NotifKeys.double(obs, NotifKeys.PER_DAY_7)
            val severity = when {
                perDay > VERY_NOISY_PER_DAY -> Severity.WARN
                perDay > NOISY_PER_DAY -> Severity.NOTICE
                else -> return@mapNotNull null
            }
            val count = NotifKeys.int(obs, NotifKeys.COUNT_7)
            FindingDraft(
                ctx.tunnelId, subject, NOISY_APP, severity,
                "${name(obs)} posted about ${Math.round(perDay)} notifications a day this week ($count in 7 days). " +
                    "You can silence or turn off its noisier channels.",
            )
        }
    }

    val nightNoise: FindingRule = Rules.perSubject(NIGHT_NOISE, Severity.NOTICE) { subject, obs ->
        if (subject == NotifKeys.SUMMARY) return@perSubject null
        val night = NotifKeys.int(obs, NotifKeys.NIGHT_7)
        if (night < NIGHT_MIN) return@perSubject null
        if (NotifKeys.categories(obs).any { it in nightExempt }) return@perSubject null
        "${name(obs)} notified $night times between 23:00 and 06:00 this week, and it is not an alarm or call app. " +
            "Consider muting it or letting Do Not Disturb filter it at night."
    }

    val spoofedUrgency: FindingRule = Rules.perSubject(SPOOFED_URGENCY, Severity.WARN) { subject, obs ->
        if (subject == NotifKeys.SUMMARY) return@perSubject null
        val count = NotifKeys.int(obs, NotifKeys.COUNT_7)
        val urgent = NotifKeys.int(obs, NotifKeys.URGENT_7)
        if (count < URGENCY_MIN_POSTS || urgent * 2 <= count) return@perSubject null
        val cats = categoriesIncludingEmpty(obs)
        if (cats.isEmpty() || cats.any { it !in lowStakes }) return@perSubject null
        val kinds = cats.filter { it.isNotEmpty() }.sorted().joinToString(", ").ifEmpty { "uncategorised" }
        "${name(obs)} marked $urgent of its $count notifications this week as high priority, so they pop over other apps " +
            "and make sound, yet they are only $kinds notifications. Lowering the channel importance stops the interruptions."
    }

    val lockScreenExposure: FindingRule = Rules.perSubject(LOCK_SCREEN_EXPOSURE, Severity.INFO) { subject, obs ->
        if (subject == NotifKeys.SUMMARY) return@perSubject null
        val public = NotifKeys.int(obs, NotifKeys.LOCK_PUBLIC_7)
        if (public == 0) return@perSubject null
        val cats = NotifKeys.categories(obs)
        if (cats.none { it in personal }) return@perSubject null
        "${name(obs)} posted $public notifications this week marked as safe to show in full on the lock screen, " +
            "so anyone holding the phone can read them. Set its channels to hide sensitive content on the lock screen."
    }

    val listenerDisconnected: FindingRule = Rules.perSubject(LISTENER_DISCONNECTED, Severity.INFO) { subject, obs ->
        if (subject != NotifKeys.SUMMARY) return@perSubject null
        val granted = NotifKeys.value(obs, NotifKeys.ACCESS_GRANTED) == "true"
        val connected = NotifKeys.value(obs, NotifKeys.LISTENER_CONNECTED) == "true"
        if (!granted || connected) return@perSubject null
        "Notification access is granted but the system has not connected the listener, so new notifications are not being counted. " +
            "Toggling Notification access off and on usually reconnects it."
    }

    /** Every rule of the tunnel, in display order. Declared last so the rule values above exist first. */
    val all: List<FindingRule> = listOf(noisyApp, nightNoise, spoofedUrgency, lockScreenExposure, listenerDisconnected)

    private fun name(obs: List<Observation>): String =
        NotifKeys.value(obs, NotifKeys.LABEL)?.takeIf { it.isNotBlank() } ?: obs.firstOrNull()?.subject ?: "This app"

    /** Categories seen, with "" standing for posts that had no category (the stored value "none"). */
    private fun categoriesIncludingEmpty(obs: List<Observation>): Set<String> {
        val raw = NotifKeys.value(obs, NotifKeys.CATEGORIES) ?: return emptySet()
        return raw.split(',').map { it.trim() }.map { if (it == NotifKeys.NO_CATEGORIES) "" else it }.toSet()
    }
}
