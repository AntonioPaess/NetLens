package com.antoniopaess.netlens.data.diagnostics

import com.antoniopaess.netlens.domain.ConnectivitySnapshotProvider
import com.antoniopaess.netlens.domain.NetworkProbe
import com.antoniopaess.netlens.domain.NetworkProbeFailure
import com.antoniopaess.netlens.domain.NetworkProbeKind
import com.antoniopaess.netlens.domain.NetworkProbeResult
import com.antoniopaess.netlens.domain.NetworkProbeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Named budgets for each real diagnostic layer. */
data class NetworkProbeTimeouts(
    val dnsMillis: Long = DNS_TIMEOUT_MILLIS,
    val tlsMillis: Long = TLS_TIMEOUT_MILLIS,
    val httpsMillis: Long = HTTPS_TIMEOUT_MILLIS,
) {
    init {
        require(dnsMillis > 0)
        require(tlsMillis > 0)
        require(httpsMillis > 0)
    }

    companion object {
        /** DNS must resolve within two seconds. */
        const val DNS_TIMEOUT_MILLIS = 2_000L

        /** TLS negotiation has a three-second budget. */
        const val TLS_TIMEOUT_MILLIS = 3_000L

        /** HTTPS status check has a five-second budget. */
        const val HTTPS_TIMEOUT_MILLIS = 5_000L
    }
}

/** DNS probe with a bounded operation and an injectable resolver. */
class DnsNetworkProbe(
    private val connectivity: ConnectivitySnapshotProvider,
    private val resolver: DnsResolver = SystemDnsResolver,
    private val timeouts: NetworkProbeTimeouts = NetworkProbeTimeouts(),
    private val clock: ProbeClock = SystemProbeClock,
) : NetworkProbe {
    override suspend fun execute(target: NetworkProbeTarget): NetworkProbeResult {
        if (!connectivity.current().available) {
            return NetworkProbeResult.Failure(
                kind = NetworkProbeKind.DNS,
                reason = NetworkProbeFailure.NetworkUnavailable,
                latencyMillis = 0L,
            )
        }

        return executeBounded(
            kind = NetworkProbeKind.DNS,
            timeoutMillis = timeouts.dnsMillis,
        ) {
            resolver.resolve(target.host)
        }
    }

    private suspend fun executeBounded(
        kind: NetworkProbeKind,
        timeoutMillis: Long,
        operation: suspend () -> List<java.net.InetAddress>,
    ): NetworkProbeResult {
        val started = clock.nowNanos()
        return try {
            val addresses =
                withTimeoutOrNull(timeoutMillis) { operation() }
                    ?: return NetworkProbeResult.Failure(
                        kind,
                        NetworkProbeFailure.Timeout,
                        elapsedMillis(started),
                    )
            if (addresses.isEmpty()) {
                NetworkProbeResult.Failure(kind, NetworkProbeFailure.DnsResolution, elapsedMillis(started))
            } else {
                NetworkProbeResult.Success(kind, elapsedMillis(started))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NetworkProbeResult.Failure(kind, failure.toProbeFailure(), elapsedMillis(started))
        }
    }

    private fun elapsedMillis(started: Long): Long =
        ((clock.nowNanos() - started) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(0L)
}

/** TLS probe with a bounded handshake and an injectable socket adapter. */
class TlsNetworkProbe(
    private val connectivity: ConnectivitySnapshotProvider,
    private val connector: TlsConnector = SystemTlsConnector,
    private val timeouts: NetworkProbeTimeouts = NetworkProbeTimeouts(),
    private val clock: ProbeClock = SystemProbeClock,
) : NetworkProbe {
    override suspend fun execute(target: NetworkProbeTarget): NetworkProbeResult {
        if (!connectivity.current().available) {
            return NetworkProbeResult.Failure(
                kind = NetworkProbeKind.TLS,
                reason = NetworkProbeFailure.NetworkUnavailable,
                latencyMillis = 0L,
            )
        }

        val started = clock.nowNanos()
        return try {
            val completed =
                withTimeoutOrNull(timeouts.tlsMillis) {
                    connector.connect(target.host, target.tlsPort, timeouts.tlsMillis)
                    true
                } ?: false
            if (!completed) {
                NetworkProbeResult.Failure(
                    NetworkProbeKind.TLS,
                    NetworkProbeFailure.Timeout,
                    elapsedMillis(started),
                )
            } else {
                NetworkProbeResult.Success(NetworkProbeKind.TLS, elapsedMillis(started))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NetworkProbeResult.Failure(
                NetworkProbeKind.TLS,
                failure.toProbeFailure(),
                elapsedMillis(started),
            )
        }
    }

    private fun elapsedMillis(started: Long): Long =
        ((clock.nowNanos() - started) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(0L)
}

/** HTTPS probe with a bounded status request and an injectable HTTP adapter. */
class HttpsNetworkProbe(
    private val connectivity: ConnectivitySnapshotProvider,
    private val requester: HttpsRequester = SystemHttpsRequester,
    private val timeouts: NetworkProbeTimeouts = NetworkProbeTimeouts(),
    private val clock: ProbeClock = SystemProbeClock,
) : NetworkProbe {
    override suspend fun execute(target: NetworkProbeTarget): NetworkProbeResult {
        if (!connectivity.current().available) {
            return NetworkProbeResult.Failure(
                kind = NetworkProbeKind.HTTPS,
                reason = NetworkProbeFailure.NetworkUnavailable,
                latencyMillis = 0L,
            )
        }

        val started = clock.nowNanos()
        return try {
            val statusCode =
                withTimeoutOrNull(timeouts.httpsMillis) {
                    requester.request(target.httpsUrl, timeouts.httpsMillis)
                }
                    ?: return NetworkProbeResult.Failure(
                        NetworkProbeKind.HTTPS,
                        NetworkProbeFailure.Timeout,
                        elapsedMillis(started),
                    )

            if (statusCode in 200..299) {
                NetworkProbeResult.Success(
                    kind = NetworkProbeKind.HTTPS,
                    latencyMillis = elapsedMillis(started),
                    httpStatusCode = statusCode,
                )
            } else {
                NetworkProbeResult.Failure(
                    kind = NetworkProbeKind.HTTPS,
                    reason = NetworkProbeFailure.Http(statusCode),
                    latencyMillis = elapsedMillis(started),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            NetworkProbeResult.Failure(
                NetworkProbeKind.HTTPS,
                failure.toProbeFailure(),
                elapsedMillis(started),
            )
        }
    }

    private fun elapsedMillis(started: Long): Long =
        ((clock.nowNanos() - started) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(0L)
}

/** Short aliases for callers that prefer layer names without the adapter suffix. */
typealias DnsProbe = DnsNetworkProbe
typealias TlsProbe = TlsNetworkProbe
typealias HttpsProbe = HttpsNetworkProbe

private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun Throwable.toProbeFailure(): NetworkProbeFailure =
    when (rootCause()) {
        is UnknownHostException -> NetworkProbeFailure.DnsResolution
        is SSLException -> NetworkProbeFailure.Tls
        is SocketTimeoutException, is TimeoutException -> NetworkProbeFailure.Timeout
        is IOException -> NetworkProbeFailure.Unknown
        else -> NetworkProbeFailure.Unknown
    }

private fun Throwable.rootCause(): Throwable {
    var current = this
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}
