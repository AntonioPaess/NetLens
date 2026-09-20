package com.antoniopaess.netlens.domain

/**
 * Executes one connection attempt and waits for a later connection drop.
 *
 * The state machine owns timing, retries, and cancellation; this driver only
 * supplies deterministic boundary results or a real simulation implementation.
 */
interface ConnectionAttemptDriver {
    /** Try to establish a connection to [region]. */
    suspend fun attempt(region: Region): AttemptResult

    /** Suspend until the active connection drops and return its typed reason. */
    suspend fun awaitDrop(region: Region): ConnectionError
}

/** Result returned by one driver attempt. */
sealed interface AttemptResult {
    /** The attempt established the connection. */
    data object Success : AttemptResult

    /** The attempt completed with a typed failure. */
    data class Failure(
        val error: ConnectionError,
    ) : AttemptResult
}
