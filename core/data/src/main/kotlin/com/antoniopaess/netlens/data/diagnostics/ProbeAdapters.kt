package com.antoniopaess.netlens.data.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Injectable DNS operation; JVM tests can provide a deterministic lambda. */
fun interface DnsResolver {
    /** Resolve [host] without exposing any payload or request metadata. */
    suspend fun resolve(host: String): List<InetAddress>
}

/** Injectable TLS handshake operation; JVM tests never need a real socket. */
fun interface TlsConnector {
    /** Complete a TLS handshake to [host] and [port]. */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMillis: Long,
    )
}

/** Injectable HTTPS status operation; the response body is never read. */
fun interface HttpsRequester {
    /** Return only the HTTPS response status code for [url]. */
    suspend fun request(
        url: String,
        timeoutMillis: Long,
    ): Int
}

/** Injectable monotonic clock used to calculate local probe latency. */
fun interface ProbeClock {
    /** Return monotonic nanoseconds. */
    fun nowNanos(): Long
}

/** Default system clock for diagnostic latency measurements. */
object SystemProbeClock : ProbeClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/** Default DNS adapter. It performs no logging and returns only resolved addresses. */
object SystemDnsResolver : DnsResolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        runInterruptible(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
}

/** Default TLS adapter. It opens one socket, performs a handshake, and closes it. */
object SystemTlsConnector : TlsConnector {
    override suspend fun connect(
        host: String,
        port: Int,
        timeoutMillis: Long,
    ) {
        withContext(Dispatchers.IO) {
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMillis.toInt())
                socket.soTimeout = timeoutMillis.toInt()
                socket.startHandshake()
            } finally {
                socket.close()
            }
        }
    }
}

/** Default HTTPS adapter. It uses a HEAD request and never reads the response body. */
object SystemHttpsRequester : HttpsRequester {
    override suspend fun request(
        url: String,
        timeoutMillis: Long,
    ): Int =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = timeoutMillis.toInt()
                connection.readTimeout = timeoutMillis.toInt()
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "HEAD"
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
}
