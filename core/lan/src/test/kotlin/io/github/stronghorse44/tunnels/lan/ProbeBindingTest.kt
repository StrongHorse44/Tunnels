package io.github.stronghorse44.tunnels.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class ProbeBindingTest {
    private class FakeBinder(var failAfter: Int = Int.MAX_VALUE) : ProbeBinder {
        var calls = 0
        private fun next() {
            calls++
            if (calls > failAfter) throw IOException("network gone")
        }
        override fun bind(socket: Socket) = next()
        override fun bind(socket: DatagramSocket) = next()
    }

    @Test
    fun bindsWhileTheNetworkIsThere() {
        val binder = FakeBinder()
        val guard = BindGuard(binder)
        Socket().use { assertTrue(guard.bindTcp(it)) }
        DatagramSocket().use { assertTrue(guard.bindUdp(it)) }
        assertFalse(guard.lost)
        assertEquals(0, guard.skipped)
        assertEquals(2, binder.calls)
    }

    @Test
    fun aFailedBindSkipsTheProbeAndAbortsTheRest() {
        val binder = FakeBinder(failAfter = 1)
        val guard = BindGuard(binder)
        Socket().use { assertTrue(guard.bindTcp(it)) }
        Socket().use { assertFalse("the bind that fails", guard.bindTcp(it)) }
        assertTrue(guard.lost)
        assertEquals(1, guard.skipped)
        // Even if the network comes back, the scan does not resume: later probes are refused without a bind attempt.
        binder.failAfter = Int.MAX_VALUE
        val callsBefore = binder.calls
        Socket().use { assertFalse(guard.bindTcp(it)) }
        DatagramSocket().use { assertFalse(guard.bindUdp(it)) }
        assertEquals(callsBefore, binder.calls)
        assertEquals(3, guard.skipped)
        assertTrue(guard.lost)
    }

    @Test
    fun noNetworkMeansNoProbes() {
        val guard = BindGuard(null)
        assertTrue(guard.lost)
        Socket().use { assertFalse(guard.bindTcp(it)) }
        DatagramSocket().use { assertFalse(guard.bindUdp(it)) }
        assertEquals(2, guard.skipped)
    }

    @Test
    fun anyExceptionFromTheBinderCountsAsLost() {
        val guard = BindGuard(object : ProbeBinder {
            override fun bind(socket: Socket) = throw SecurityException("denied")
            override fun bind(socket: DatagramSocket) = throw IllegalStateException("closed")
        })
        DatagramSocket().use { assertFalse(guard.bindUdp(it)) }
        assertTrue(guard.lost)
    }
}

class DropCounterTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun countsDistinctAddressesNotEvents() {
        val c = DropCounter()
        c.add(ip("10.0.0.5"))
        c.add(ip("10.0.0.5"))
        c.add("10.0.0.5")
        c.add(" 10.0.0.5 ")
        assertEquals(1, c.count)
        c.add("8.8.8.8")
        c.add(ip("fd12:3456::1"))
        c.add("fd12:3456:0:0:0:0:0:1")
        assertEquals(3, c.count)
    }

    @Test
    fun nonLiteralsCountOncePerText() {
        val c = DropCounter()
        c.add("router.local")
        c.add("router.local")
        c.add("other.local")
        assertEquals(2, c.count)
    }

    @Test
    fun staysBounded() {
        val c = DropCounter()
        for (i in 0 until 5000) c.add("10.${i / 256}.${i % 256}.1")
        assertEquals(DropCounter.MAX_TRACKED, c.count)
    }
}
