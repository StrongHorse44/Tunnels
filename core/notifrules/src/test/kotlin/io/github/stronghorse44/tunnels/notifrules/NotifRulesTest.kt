package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.engine.FindingsEngine
import io.github.stronghorse44.tunnels.model.FindingAction
import io.github.stronghorse44.tunnels.model.FindingDraft
import io.github.stronghorse44.tunnels.model.Observation
import io.github.stronghorse44.tunnels.model.RuleContext
import io.github.stronghorse44.tunnels.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotifRulesTest {
    private val t = NotifKeys.TUNNEL_ID

    private fun app(
        pkg: String,
        count7: Int,
        label: String = pkg.substringAfterLast('.'),
        lockPublic7: Int = 0,
        urgent7: Int = 0,
        night7: Int = 0,
        categories: String = NotifKeys.NO_CATEGORIES,
    ): List<Observation> = listOf(
        Observation(t, pkg, NotifKeys.LABEL, label),
        Observation(t, pkg, NotifKeys.COUNT_7, count7.toString()),
        Observation(t, pkg, NotifKeys.COUNT_30, (count7 * 2).toString()),
        Observation(t, pkg, NotifKeys.PER_DAY_7, NotifAggregator.formatRate(count7 / 7.0)),
        Observation(t, pkg, NotifKeys.LOCK_PUBLIC_7, lockPublic7.toString()),
        Observation(t, pkg, NotifKeys.URGENT_7, urgent7.toString()),
        Observation(t, pkg, NotifKeys.SILENT_7, "0"),
        Observation(t, pkg, NotifKeys.ONGOING_7, "0"),
        Observation(t, pkg, NotifKeys.NIGHT_7, night7.toString()),
        Observation(t, pkg, NotifKeys.CATEGORIES, categories),
    )

    private fun summary(connected: Boolean, granted: Boolean, total7: Int = 0): List<Observation> = listOf(
        Observation(t, NotifKeys.SUMMARY, NotifKeys.LISTENER_CONNECTED, connected.toString()),
        Observation(t, NotifKeys.SUMMARY, NotifKeys.ACCESS_GRANTED, granted.toString()),
        Observation(t, NotifKeys.SUMMARY, NotifKeys.TOTAL_7, total7.toString()),
        Observation(t, NotifKeys.SUMMARY, NotifKeys.PER_DAY_7, "999.0"), // a stray per-day key on the summary must not count as noisy
    )

    private fun evaluate(current: List<Observation>, firstScan: Boolean = true): List<FindingDraft> =
        NotifRules.all.flatMap { it.evaluate(RuleContext(t, current, emptyList(), isFirstScan = firstScan)) }

    private fun List<FindingDraft>.of(kind: String) = filter { it.kind == kind }

    @Test
    fun noisyAppThresholds() {
        val quiet = app("com.quiet", 210)            // 30.0 a day: not above
        val notice = app("com.notice", 211)          // 30.1
        val warn = app("com.warn", 701)              // 100.1
        val drafts = evaluate(quiet + notice + warn + summary(true, true)).of(NotifRules.NOISY_APP)
        assertEquals(setOf("com.notice", "com.warn"), drafts.map { it.subject }.toSet())
        val n = drafts.single { it.subject == "com.notice" }
        assertEquals(Severity.NOTICE, n.severity)
        assertEquals("notice posted about 30 notifications a day this week (211 in 7 days). You can silence or turn off its noisier channels.", n.evidence)
        assertEquals(Severity.WARN, drafts.single { it.subject == "com.warn" }.severity)
        assertTrue(drafts.none { it.sticky })
    }

    @Test
    fun nightNoiseSkipsAlarmsAndCalls() {
        val social = app("com.social", 20, night7 = 5, categories = "social")
        val few = app("com.few", 20, night7 = 4, categories = "social")
        val alarm = app("com.alarm", 20, night7 = 12, categories = "alarm,reminder")
        val call = app("com.call", 20, night7 = 9, categories = "call,msg")
        val none = app("com.none", 20, night7 = 7)
        val drafts = evaluate(social + few + alarm + call + none).of(NotifRules.NIGHT_NOISE)
        assertEquals(setOf("com.social", "com.none"), drafts.map { it.subject }.toSet())
        assertEquals(Severity.NOTICE, drafts.first().severity)
        assertTrue(drafts.single { it.subject == "com.social" }.evidence.startsWith("social notified 5 times between 23:00 and 06:00 this week"))
    }

    @Test
    fun spoofedUrgency() {
        val promo = app("com.promo", 10, urgent7 = 6, categories = "promo,recommendation")
        val half = app("com.half", 10, urgent7 = 5, categories = "promo")               // exactly half: not more than half
        val uncategorised = app("com.blank", 4, urgent7 = 3)                            // "none" counts as empty
        val tooFew = app("com.few", 3, urgent7 = 3, categories = "promo")
        val messenger = app("com.chat", 50, urgent7 = 49, categories = "msg")
        val mixed = app("com.mixed", 10, urgent7 = 9, categories = "promo,msg")
        val drafts = evaluate(promo + half + uncategorised + tooFew + messenger + mixed).of(NotifRules.SPOOFED_URGENCY)
        assertEquals(setOf("com.promo", "com.blank"), drafts.map { it.subject }.toSet())
        assertTrue(drafts.all { it.severity == Severity.WARN })
        val p = drafts.single { it.subject == "com.promo" }
        assertTrue(p.evidence, p.evidence.contains("6 of its 10 notifications") && p.evidence.contains("promo, recommendation"))
        assertTrue(drafts.single { it.subject == "com.blank" }.evidence.contains("uncategorised"))
    }

    @Test
    fun lockScreenExposureOnlyForPersonalApps() {
        val chat = app("com.chat", 10, lockPublic7 = 3, categories = "msg,service")
        val mail = app("com.mail", 10, lockPublic7 = 1, categories = "email")
        val privateChat = app("com.private", 10, lockPublic7 = 0, categories = "msg")
        val weather = app("com.weather", 10, lockPublic7 = 10, categories = "status")
        val drafts = evaluate(chat + mail + privateChat + weather).of(NotifRules.LOCK_SCREEN_EXPOSURE)
        assertEquals(setOf("com.chat", "com.mail"), drafts.map { it.subject }.toSet())
        assertTrue(drafts.all { it.severity == Severity.INFO })
        assertTrue(drafts.single { it.subject == "com.chat" }.evidence.startsWith("chat posted 3 notifications this week marked as safe to show in full on the lock screen"))
    }

    @Test
    fun listenerDisconnectedNeedsGrantedAccess() {
        assertEquals(1, evaluate(summary(connected = false, granted = true)).of(NotifRules.LISTENER_DISCONNECTED).size)
        assertTrue(evaluate(summary(connected = true, granted = true)).of(NotifRules.LISTENER_DISCONNECTED).isEmpty())
        assertTrue(evaluate(summary(connected = false, granted = false)).of(NotifRules.LISTENER_DISCONNECTED).isEmpty())
        val d = evaluate(summary(false, true)).single()
        assertEquals(NotifKeys.SUMMARY, d.subject)
        assertEquals(Severity.INFO, d.severity)
    }

    @Test
    fun summarySubjectNeverTripsAppRules() {
        val drafts = evaluate(summary(connected = true, granted = true, total7 = 5000))
        assertTrue(drafts.toString(), drafts.isEmpty())
    }

    @Test
    fun rulesAreStateRulesThatClearThroughTheEngine() {
        val loud = app("com.loud", 800) + summary(true, true)
        val now = Instant.ofEpochSecond(1_800_000_000)
        val actions = { d: FindingDraft -> listOf<FindingAction>(FindingAction.OpenAppDetails(d.subject)) }
        val first = FindingsEngine.derive(RuleContext(t, loud, emptyList(), true), NotifRules.all, actions, emptyList(), now)
        assertEquals(listOf(NotifRules.NOISY_APP), first.upserts.map { it.kind })
        assertTrue(first.upserts.none { it.sticky })
        val quiet = app("com.loud", 3) + summary(true, true)
        val second = FindingsEngine.derive(RuleContext(t, quiet, emptyList(), false), NotifRules.all, actions, first.upserts, now.plusSeconds(60))
        assertTrue(second.upserts.isEmpty())
        assertEquals(first.upserts.map { it.id }, second.removals)
    }
}
