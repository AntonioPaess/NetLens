package com.antoniopaess.netlens.feature.connection.domain

/**
 * Timing policy used by the connection state machine. Named constants keep
 * the retry contract visible and make virtual-time tests readable.
 */
object ConnectionTiming {
    /** Maximum duration of the initial and each automatic attempt, in milliseconds. */
    const val CONNECTION_TIMEOUT_MILLIS: Long = 5_000L

    /** Number of automatic attempts allowed after one connection drop. */
    const val MAX_RECONNECT_ATTEMPTS: Int = 3

    /** Backoff before reconnect attempts one, two, and three, in milliseconds. */
    val RECONNECT_BACKOFF_MILLIS: List<Long> = listOf(1_000L, 2_000L, 4_000L)
}
