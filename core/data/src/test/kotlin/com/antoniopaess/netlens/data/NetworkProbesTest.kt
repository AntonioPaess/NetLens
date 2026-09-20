package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.diagnostics.DnsNetworkProbe
import com.antoniopaess.netlens.data.diagnostics.DnsResolver
import com.antoniopaess.netlens.data.diagnostics.HttpsNetworkProbe
import com.antoniopaess.netlens.data.diagnostics.HttpsRequester
import com.antoniopaess.netlens.data.diagnostics.NetworkProbeTimeouts
import com.antoniopaess.netlens.data.diagnostics.ProbeClock
import com.antoniopaess.netlens.data.diagnostics.TlsConnector
import com.antoniopaess.netlens.data.diagnostics.TlsNetworkProbe
import com.antoniopaess.netlens.domain.ConnectivitySnapshot
import com.antoniopaess.netlens.domain.ConnectivitySnapshotProvider
import com.antoniopaess.netlens.domain.NetworkProbeFailure
import com.antoniopaess.netlens.domain.NetworkProbeKind
import com.antoniopaess.netlens.domain.NetworkProbeResult
import com.antoniopaess.netlens.domain.NetworkProbeTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkProbesTest {
    private val target = NetworkProbeTarget("example.test")
    private val online =
        ConnectivitySnapshotProvider {
            ConnectivitySnapshot(available = true, validated = true, transports = emptySet())
        }
    private val offline =
        ConnectivitySnapshotProvider {
            ConnectivitySnapshot(available = false, validated = false, transports = emptySet())
        }
    private val clock = ProbeClock { 0L }

    @Test
    fun dnsClassifiesResolutionFailureWithoutLiveNetwork() =
        runTest {
            val probe =
                DnsNetworkProbe(
                    connectivity = online,
                    resolver = DnsResolver { throw UnknownHostException() },
                    clock = clock,
                )

            val result = probe.execute(target)
            val failure = result as NetworkProbeResult.Failure

            assertEquals(
                NetworkProbeFailure.DnsResolution,
                failure.reason,
            )
            assertEquals(NetworkProbeKind.DNS, failure.kind)
        }

    @Test
    fun tlsClassifiesVirtualTimeout() =
        runTest {
            val probe =
                TlsNetworkProbe(
                    connectivity = online,
                    connector = TlsConnector { _, _, _ -> delay(10_000L) },
                    timeouts = NetworkProbeTimeouts(tlsMillis = 100L),
                    clock = clock,
                )

            val result = probe.execute(target)

            assertEquals(NetworkProbeFailure.Timeout, (result as NetworkProbeResult.Failure).reason)
        }

    @Test
    fun tlsClassifiesHandshakeFailure() =
        runTest {
            val probe =
                TlsNetworkProbe(
                    connectivity = online,
                    connector = TlsConnector { _, _, _ -> throw SSLHandshakeException("certificate") },
                    clock = clock,
                )

            val result = probe.execute(target)

            assertEquals(NetworkProbeFailure.Tls, (result as NetworkProbeResult.Failure).reason)
        }

    @Test
    fun httpsClassifiesStatusWithoutReadingPayload() =
        runTest {
            val probe =
                HttpsNetworkProbe(
                    connectivity = online,
                    requester = HttpsRequester { _, _ -> 503 },
                    clock = clock,
                )

            val result = probe.execute(target)

            assertEquals(NetworkProbeFailure.Http(503), (result as NetworkProbeResult.Failure).reason)
        }

    @Test
    fun httpsClassifiesTransportTimeout() =
        runTest {
            val probe =
                HttpsNetworkProbe(
                    connectivity = online,
                    requester = HttpsRequester { _, _ -> throw SocketTimeoutException() },
                    clock = clock,
                )

            val result = probe.execute(target)

            assertEquals(NetworkProbeFailure.Timeout, (result as NetworkProbeResult.Failure).reason)
        }

    @Test
    fun unavailableSnapshotStopsProbeBeforeLowLevelOperation() =
        runTest {
            var called = false
            val probe =
                DnsNetworkProbe(
                    connectivity = offline,
                    resolver =
                        DnsResolver {
                            called = true
                            listOf(InetAddress.getLoopbackAddress())
                        },
                    clock = clock,
                )

            val result = probe.execute(target)

            assertEquals(NetworkProbeFailure.NetworkUnavailable, (result as NetworkProbeResult.Failure).reason)
            assertTrue(!called)
        }
}
