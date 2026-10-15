@file:Suppress("unused")

package egressfixtures

import io.github.stronghorse44.tunnels.deepmode.ShizukuShell

// Classes the egress tests compile and then read back as class files. Nothing here runs.

class OpensSocket {
    fun open(): Any = java.net.Socket("example.org", 80)
}

class ParsesUri {
    fun host(text: String): String = java.net.URI(text).host ?: ""
    fun encode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")
    fun address(bytes: ByteArray): String = java.net.InetAddress.getByAddress(bytes).hostAddress
}

class ResolvesName {
    fun resolve(name: String): Any = java.net.InetAddress.getByName(name)
}

class ReflectsSocket {
    fun load(): Class<*> = Class.forName("java.net.Socket")
}

class HoldsSocket {
    var socket: java.net.Socket? = null
}

class ListsSockets {
    val sockets: List<java.net.Socket> = emptyList()
}

class OpensUrl {
    fun open(uri: java.net.URI): Any = uri.toURL()
}

class SwitchesThenConnects {
    fun dense(i: Int): String = when (i) { 1 -> "a"; 2 -> "b"; 3 -> "c"; 4 -> "d"; 5 -> "e"; else -> "z" }
    fun sparse(i: Int): String = when (i) { 1 -> "a"; 1000 -> "b"; 100000 -> "c"; else -> "z" }
    fun connect(i: Int): Any = dense(i) + sparse(i) + java.net.Socket().toString()
}

class CallsShell {
    fun go(): String = ShizukuShell().run("svc wifi disable")
}

class StartsProcess {
    fun go(): Any = ProcessBuilder("sh").start()
}

class ExecsRuntime {
    fun go(): Any = Runtime.getRuntime().exec(arrayOf("sh"))
    fun memory(): Long = Runtime.getRuntime().maxMemory()
}

class CatchesSocketTimeout {
    fun go(block: () -> Unit) = try { block() } catch (e: java.net.SocketTimeoutException) { }
}

class Plain {
    fun add(a: Int, b: Int): Int = a + b
}
