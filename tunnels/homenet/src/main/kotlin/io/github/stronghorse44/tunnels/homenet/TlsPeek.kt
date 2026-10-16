package io.github.stronghorse44.tunnels.homenet

import io.github.stronghorse44.tunnels.lan.BindGuard
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Reads a TLS server's leaf certificate without ever trusting it: a handshake whose trust check records the certificate
 * and then refuses it, so the handshake fails and nothing is ever sent over the connection. Used by Look closer (a
 * device's certificate names) and by the router identity probe (the admin page's key). The socket is bound to the
 * confirmed network ([BindGuard]) before it connects. [onSocket] gets the plain socket as soon as it exists, so a
 * caller with a deadline can close it.
 */
internal object TlsPeek {
    fun leaf(
        guard: BindGuard,
        address: InetAddress,
        port: Int,
        connectMs: Int,
        readMs: Int,
        onSocket: (Socket) -> Unit = {},
    ): X509Certificate? {
        val seen = AtomicReference<X509Certificate?>()
        val recorder = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("not a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                seen.set(chain?.firstOrNull())
                throw CertificateException("Tunnels only reads the certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val plain = Socket()
        try {
            onSocket(plain)
            if (!guard.bindTcp(plain)) return null
            plain.connect(InetSocketAddress(address, port), connectMs)
            plain.soTimeout = readMs
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(recorder), null) }
            (tls.socketFactory.createSocket(plain, address.hostAddress, port, true) as SSLSocket).use { socket ->
                runCatching { socket.startHandshake() }
            }
        } catch (_: Exception) {
            // Refused, timed out, closed by the caller or not TLS: no certificate.
        } finally {
            runCatching { plain.close() }
        }
        return seen.get()
    }
}
