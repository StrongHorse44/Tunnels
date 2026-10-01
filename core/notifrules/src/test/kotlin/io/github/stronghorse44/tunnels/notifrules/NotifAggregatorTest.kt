package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.notifrules.NotifAggregator.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifAggregatorTest {
    private val now = 1_800_000_000_000L

    private fun ev(pkg: String, daysAgo: Double, record: NotifRecord = NotifRecord(importance = NotifRecord.Importance.DEFAULT)) =
        NotifEvent(pkg, now - (daysAgo * DAY_MS).toLong(), record)

    private val high = NotifRecord(importance = NotifRecord.Importance.HIGH, category = "promo")
    private val publicMsg = NotifRecord(importance = NotifRecord.Importance.DEFAULT, visibility = NotifRecord.Visibility.PUBLIC, category = "msg")
    private val night = NotifRecord(importance = NotifRecord.Importance.DEFAULT, hour = 2, night = true, category = "social")
    private val silentOngoing = NotifRecord(importance = NotifRecord.Importance.LOW, silent = true, ongoing = true, category = "progress")

    @Test
    fun windowsAndCounters() {
        val events = listOf(
            ev("com.chat", 0.5, publicMsg),
            ev("com.chat", 1.0, publicMsg),
            ev("com.chat", 6.9, night),
            ev("com.chat", 7.1),                 // inside 30 days, outside 7
            ev("com.chat", 29.9),
            ev("com.chat", 30.5),                // too old
            ev("com.chat", -1.0),                // future: ignored
            ev("com.shop", 2.0, high),
            ev("com.shop", 3.0, high),
            ev("com.shop", 3.5, silentOngoing),
            ev("", 1.0),                         // blank package: ignored
        )
        val agg = NotifAggregator.aggregate(events, now)
        assertEquals(listOf("com.chat", "com.shop"), agg.perPackage.map { it.packageName })
        val chat = agg.perPackage[0]
        assertEquals(3, chat.count7)
        assertEquals(5, chat.count30)
        assertEquals(2, chat.lockPublic7)
        assertEquals(0, chat.urgent7)
        assertEquals(1, chat.night7)
        assertEquals(setOf("msg", "social", ""), chat.categories)
        assertEquals(3 / 7.0, chat.perDay7, 1e-9)
        val shop = agg.perPackage[1]
        assertEquals(3, shop.count7)
        assertEquals(2, shop.urgent7)
        assertEquals(1, shop.silent7)
        assertEquals(1, shop.ongoing7)
        assertEquals(setOf("promo", "progress"), shop.categories)
        assertEquals(6, agg.total7)
        assertEquals(8, agg.total30)
        assertEquals(2, agg.appsActive7)
        assertEquals(NotifAggregate.EMPTY, NotifAggregator.aggregate(emptyList(), now))
    }

    @Test
    fun orderingNoisiestFirstThenByName() {
        val events = List(5) { ev("com.b", 1.0) } + List(5) { ev("com.a", 1.0) } + List(9) { ev("com.c", 1.0) } + ev("com.old", 10.0)
        val agg = NotifAggregator.aggregate(events, now)
        assertEquals(listOf("com.c", "com.a", "com.b", "com.old"), agg.perPackage.map { it.packageName })
        assertEquals(3, agg.appsActive7)
    }

    @Test
    fun observationsSchema() {
        val events = List(3) { ev("com.chat", 1.0, publicMsg) } + List(2) { ev("com.shop", 2.0, high) } + ev("com.quiet", 12.0)
        val agg = NotifAggregator.aggregate(events, now)
        val obs = NotifAggregator.observations(agg, listenerConnected = true, accessGranted = true) { if (it == "com.chat") "Chat" else null }
        assertTrue(obs.all { it.tunnelId == NotifKeys.TUNNEL_ID })
        assertTrue(obs.none { it.value.isEmpty() })
        val chat = obs.filter { it.subject == "com.chat" }
        assertEquals(10, chat.size)
        assertEquals("Chat", NotifKeys.value(chat, NotifKeys.LABEL))
        assertEquals("3", NotifKeys.value(chat, NotifKeys.COUNT_7))
        assertEquals("3", NotifKeys.value(chat, NotifKeys.COUNT_30))
        assertEquals("0.4", NotifKeys.value(chat, NotifKeys.PER_DAY_7))
        assertEquals("3", NotifKeys.value(chat, NotifKeys.LOCK_PUBLIC_7))
        assertEquals("msg", NotifKeys.value(chat, NotifKeys.CATEGORIES))
        val quiet = obs.filter { it.subject == "com.quiet" }
        assertEquals("com.quiet", NotifKeys.value(quiet, NotifKeys.LABEL))
        assertEquals("0", NotifKeys.value(quiet, NotifKeys.COUNT_7))
        assertEquals("1", NotifKeys.value(quiet, NotifKeys.COUNT_30))
        assertEquals(NotifKeys.NO_CATEGORIES, NotifKeys.value(quiet, NotifKeys.CATEGORIES))
        val summary = obs.filter { it.subject == NotifKeys.SUMMARY }
        assertEquals("true", NotifKeys.value(summary, NotifKeys.LISTENER_CONNECTED))
        assertEquals("true", NotifKeys.value(summary, NotifKeys.ACCESS_GRANTED))
        assertEquals("5", NotifKeys.value(summary, NotifKeys.TOTAL_7))
        assertEquals("6", NotifKeys.value(summary, NotifKeys.TOTAL_30))
        assertEquals("2", NotifKeys.value(summary, NotifKeys.APPS_ACTIVE_7))
        assertEquals("0", NotifKeys.value(summary, NotifKeys.APPS_NOISY))
        assertNull(NotifKeys.value(summary, NotifKeys.APPS_OVERFLOW))
        // Keys are unique per subject.
        obs.groupBy { it.subject }.forEach { (s, list) -> assertEquals(s, list.size, list.map { it.key }.toSet().size) }
    }

    @Test
    fun capsAppsAndCountsNoisyOnes() {
        val events = (0 until NotifAggregator.MAX_APPS + 7).flatMap { i -> List(if (i < 2) 250 else 1) { ev("com.app$i", 1.0) } }
        val agg = NotifAggregator.aggregate(events, now)
        val obs = NotifAggregator.observations(agg, listenerConnected = false, accessGranted = false)
        val subjects = obs.map { it.subject }.toSet() - NotifKeys.SUMMARY
        assertEquals(NotifAggregator.MAX_APPS, subjects.size)
        assertTrue("noisiest apps survive the cap", "com.app0" in subjects && "com.app1" in subjects)
        val summary = obs.filter { it.subject == NotifKeys.SUMMARY }
        assertEquals("7", NotifKeys.value(summary, NotifKeys.APPS_OVERFLOW))
        assertEquals("2", NotifKeys.value(summary, NotifKeys.APPS_NOISY))
        assertEquals("false", NotifKeys.value(summary, NotifKeys.LISTENER_CONNECTED))
    }

    @Test
    fun emptyAggregateStillYieldsSummary() {
        val obs = NotifAggregator.observations(NotifAggregate.EMPTY, listenerConnected = false, accessGranted = true)
        assertEquals(setOf(NotifKeys.SUMMARY), obs.map { it.subject }.toSet())
        assertEquals("0", NotifKeys.value(obs, NotifKeys.TOTAL_7))
        assertEquals("true", NotifKeys.value(obs, NotifKeys.ACCESS_GRANTED))
    }

    @Test
    fun summaryForThePanel() {
        val events = List(70) { ev("com.b", (it % 7).toDouble() + 0.1) } + List(3) { ev("com.a", 1.0, high) } +
            List(300) { ev("com.c", 0.5, night) } + ev("com.old", 20.0)
        val obs = NotifAggregator.observations(NotifAggregator.aggregate(events, now), listenerConnected = true, accessGranted = true) {
            it.removePrefix("com.").uppercase()
        }
        val s = NotifSummary.from(obs, topN = 2)
        assertTrue(s.hasData)
        assertEquals(373, s.total7)
        assertEquals(374, s.total30)
        assertEquals(3, s.appsActive7)
        assertEquals(1, s.appsNoisy)
        assertTrue(s.listenerConnected && s.accessGranted)
        assertEquals(listOf("C", "B"), s.top.map { it.label })
        assertEquals(300, s.top[0].count7)
        assertEquals(300, s.top[0].night7)
        assertEquals(300 / 7.0, s.top[0].perDay7, 0.05)
        assertEquals(NotifSummary.EMPTY, NotifSummary.from(emptyList()))
        assertEquals("42.9", NotifAggregator.formatRate(300 / 7.0))
    }
}
