package com.antoniopaess.netlens.domain

import kotlinx.coroutines.flow.Flow

/** Android transport categories normalized for presentation and tests. */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
}

/**
 * A point-in-time view of connectivity reported by the operating system.
 *
 * [available] means that Android currently reports a network; [validated]
 * separately indicates whether the platform validated general internet access.
 * A Wi-Fi transport alone is therefore never treated as proof of internet
 * reachability.
 */
data class ConnectivitySnapshot(
    val available: Boolean,
    val validated: Boolean,
    val transports: Set<NetworkTransport>,
)

/**
 * Observes Android connectivity without binding callers to a callback
 * lifecycle. Implementations expose a cold Flow so collection owns register
 * and unregister operations.
 */
interface ConnectivityObserver {
    /** Emit an initial snapshot followed by availability/transport changes. */
    fun observe(): Flow<ConnectivitySnapshot>
}

/** Supplies the latest connectivity snapshot to a probe before it does I/O. */
fun interface ConnectivitySnapshotProvider {
    /** Return the latest known platform snapshot. */
    fun current(): ConnectivitySnapshot
}

/** Input shared by DNS, TLS, and HTTPS diagnostics. */
data class NetworkProbeTarget(
    val host: String,
    val tlsPort: Int = DEFAULT_TLS_PORT,
    val httpsUrl: String = "https://$host/",
) {
    init {
        require(host.isNotBlank())
        require(tlsPort in MIN_PORT..MAX_PORT)
        require(httpsUrl.startsWith("https://"))
    }

    private companion object {
        const val DEFAULT_TLS_PORT = 443
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
    }
}

/** Identifies the network layer exercised by a probe. */
enum class NetworkProbeKind {
    DNS,
    TLS,
    HTTPS,
}

/**
 * Categories used by all probes. No exception, payload, credential, header, or
 * traffic content crosses this boundary.
 */
sealed interface NetworkProbeFailure {
    /** The connectivity observer reports that no network is available. */
    data object NetworkUnavailable : NetworkProbeFailure

    /** Host name resolution failed. */
    data object DnsResolution : NetworkProbeFailure

    /** TLS negotiation or certificate validation failed. */
    data object Tls : NetworkProbeFailure

    /** The HTTPS endpoint returned a non-success status. */
    data class Http(
        val statusCode: Int,
    ) : NetworkProbeFailure

    /** The operation exceeded its named timeout. */
    data object Timeout : NetworkProbeFailure

    /** The operation failed without a more specific safe classification. */
    data object Unknown : NetworkProbeFailure
}

/** Result of one bounded diagnostic operation. */
sealed interface NetworkProbeResult {
    /** DNS, TLS, or HTTPS completed successfully. */
    data class Success(
        val kind: NetworkProbeKind,
        val latencyMillis: Long,
        val httpStatusCode: Int? = null,
    ) : NetworkProbeResult

    /** The operation failed with a privacy-safe typed category. */
    data class Failure(
        val kind: NetworkProbeKind,
        val reason: NetworkProbeFailure,
        val latencyMillis: Long,
    ) : NetworkProbeResult
}

/**
 * Runs one real network-layer check against a supplied target.
 *
 * Production implementations perform bounded I/O. Tests and demos can inject
 * an implementation with deterministic outcomes; that seam is separate from
 * the Android and socket adapters used by the real probes.
 */
interface NetworkProbe {
    /** Execute the probe and classify its result without returning payload data. */
    suspend fun execute(target: NetworkProbeTarget): NetworkProbeResult
}
