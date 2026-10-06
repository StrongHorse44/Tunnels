package io.github.stronghorse44.tunnels.breaches

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreachHolderTest {
    private var now = 1_000_000L
    private var n = 0
    private val holder = BreachHolder(clock = { now }, mintToken = { "%032x".format(++n) })
    private val data = "hello".toByteArray()

    @Test
    fun servesTheHeldBytesThreeTimesThenNever() {
        val t = holder.put(data.copyOf(), "breaches-20261006.txt")
        repeat(BreachHolder.MAX_SERVES) { assertArrayEquals(data, holder.take(t)) }
        assertNull(holder.take(t))
        assertNull(holder.info())
    }

    @Test
    fun unknownTokenRefusedAndCountsNothing() {
        val t = holder.put(data.copyOf(), "x.txt")
        assertNull(holder.take("0".repeat(32)))
        assertNull(holder.take(""))
        assertNull(holder.take(t + "0"))
        assertNull(holder.describe("0".repeat(32)))
        assertEquals(BreachHolder.MAX_SERVES, holder.info()!!.servesLeft)
        assertNotNull(holder.take(t))
    }

    @Test
    fun expiresAfterTenMinutes() {
        val t = holder.put(data.copyOf(), "x.txt")
        now += BreachHolder.LIFETIME_MS - 1
        assertNotNull(holder.describe(t))
        now += 1
        assertNull(holder.take(t))
        assertNull(holder.info())
    }

    @Test
    fun aNewFetchReplacesTheOldAndItsTokenStopsWorking() {
        val a = holder.put("one".toByteArray(), "a.txt")
        val b = holder.put("two".toByteArray(), "b.txt")
        assertNull(holder.take(a))
        assertArrayEquals("two".toByteArray(), holder.take(b))
    }

    @Test
    fun describeDoesNotCountAServe() {
        val t = holder.put(data.copyOf(), "breaches-20261006.txt")
        repeat(10) { assertEquals(5, holder.describe(t)!!.size) }
        assertEquals("breaches-20261006.txt", holder.describe(t)!!.displayName)
        assertEquals(BreachHolder.MAX_SERVES, holder.info()!!.servesLeft)
    }

    @Test
    fun dropZeroesTheHeldBytes() {
        val mine = data.copyOf()
        val t = holder.put(mine, "x.txt")
        val copy = holder.take(t)!!
        assertArrayEquals(data, copy)
        holder.clear()
        assertTrue(mine.all { it == 0.toByte() })
        assertArrayEquals(data, copy) // what was served is the caller's own copy
    }

    @Test
    fun tokensAre128RandomBitsInHex() {
        val a = BreachHolder.randomToken()
        val b = BreachHolder.randomToken()
        assertTrue(BreachHolder.isToken(a))
        assertEquals(32, a.length)
        assertFalse(a == b)
        assertFalse(BreachHolder.isToken("ABCDEF0123456789ABCDEF0123456789"))
        assertFalse(BreachHolder.isToken("0".repeat(31)))
    }
}
