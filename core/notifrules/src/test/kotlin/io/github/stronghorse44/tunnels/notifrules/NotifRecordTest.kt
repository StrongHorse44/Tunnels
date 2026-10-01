package io.github.stronghorse44.tunnels.notifrules

import io.github.stronghorse44.tunnels.notifrules.NotifRecord.Importance
import io.github.stronghorse44.tunnels.notifrules.NotifRecord.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifRecordTest {
    @Test
    fun encodeAndParseRoundTrip() {
        val r = NotifRecord.of(importance = 4, visibility = 1, category = "msg", ongoing = false, silent = false, hour = 23)
        assertEquals("imp=HIGH;vis=PUBLIC;cat=msg;ongoing=0;silent=0;night=1;h=23", r.encode())
        assertEquals(r, NotifRecord.parse(r.encode()))
        val quiet = NotifRecord.of(importance = 2, visibility = 0, category = null, ongoing = true, silent = true, hour = 14)
        assertEquals("imp=LOW;vis=PRIVATE;cat=;ongoing=1;silent=1;night=0;h=14", quiet.encode())
        assertEquals(quiet, NotifRecord.parse(quiet.encode()))
    }

    @Test
    fun parsesTheBriefExampleWithoutHour() {
        val r = NotifRecord.parse("imp=HIGH;vis=PUBLIC;cat=msg;ongoing=0;silent=0;night=1")!!
        assertEquals(Importance.HIGH, r.importance)
        assertEquals(Visibility.PUBLIC, r.visibility)
        assertEquals("msg", r.category)
        assertFalse(r.ongoing)
        assertFalse(r.silent)
        assertTrue("night flag is trusted when no hour is stored", r.night)
        assertEquals(-1, r.hour)
    }

    @Test
    fun hourWinsOverNightFlag() {
        assertTrue(NotifRecord.parse("imp=DEFAULT;night=0;h=2")!!.night)
        assertFalse(NotifRecord.parse("imp=DEFAULT;night=1;h=12")!!.night)
    }

    @Test
    fun nightWindowIs23To06Exclusive() {
        assertEquals(setOf(23, 0, 1, 2, 3, 4, 5), (0..23).filter(NotifRecord::isNightHour).toSet())
    }

    @Test
    fun toleratesGarbage() {
        assertNull(NotifRecord.parse(""))
        assertNull(NotifRecord.parse("hello world"))
        val r = NotifRecord.parse("imp=LOUD;vis=;cat=Promo!;ongoing=yes;h=99;unknown=1;;=")!!
        assertEquals(Importance.UNKNOWN, r.importance)
        assertEquals(Visibility.UNSET, r.visibility)
        assertEquals("other", r.category)
        assertFalse(r.ongoing)
        assertEquals(-1, r.hour)
        assertFalse(r.night)
    }

    @Test
    fun categorySanitiser() {
        assertEquals("", NotifRecord.sanitiseCategory(null))
        assertEquals("", NotifRecord.sanitiseCategory("  "))
        assertEquals("missed_call", NotifRecord.sanitiseCategory("missed_call"))
        assertEquals("promo", NotifRecord.sanitiseCategory("PROMO"))
        assertEquals("other", NotifRecord.sanitiseCategory("Hi Bob, your code is 1234"))
        assertEquals("other", NotifRecord.sanitiseCategory("a".repeat(25)))
    }

    @Test
    fun androidValueMapping() {
        assertEquals(Importance.HIGH, Importance.fromAndroid(5))     // legacy IMPORTANCE_MAX
        assertEquals(Importance.HIGH, Importance.fromAndroid(4))
        assertEquals(Importance.DEFAULT, Importance.fromAndroid(3))
        assertEquals(Importance.LOW, Importance.fromAndroid(2))
        assertEquals(Importance.MIN, Importance.fromAndroid(1))
        assertEquals(Importance.NONE, Importance.fromAndroid(0))
        assertEquals(Importance.UNKNOWN, Importance.fromAndroid(-1000))
        assertTrue(Importance.HIGH.urgent)
        assertFalse(Importance.DEFAULT.urgent)
        assertEquals(Visibility.PUBLIC, Visibility.fromAndroid(1))
        assertEquals(Visibility.PRIVATE, Visibility.fromAndroid(0))
        assertEquals(Visibility.SECRET, Visibility.fromAndroid(-1))
        assertEquals(Visibility.UNSET, Visibility.fromAndroid(42))
    }
}
