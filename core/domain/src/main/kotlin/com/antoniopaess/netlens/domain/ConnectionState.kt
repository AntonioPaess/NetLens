package com.antoniopaess.netlens.domain

/**
 * State of one connection session. Each state carries the data relevant to
 * the work represented by that state.
 */
sealed interface ConnectionState {
    /** No connection work is active. */
    data object Disconnected : ConnectionState

    /** The initial connection attempt is in progress. */
    data class Connecting(
        val region: Region,
    ) : ConnectionState

    /** A connection is active and the timestamp marks its latest success. */
    data class Connected(
        val region: Region,
        val connectedAt: Long,
    ) : ConnectionState

    /** An automatic retry is waiting to run after the preceding drop/failure. */
    data class Reconnecting(
        val region: Region,
        val attempt: Int,
        val dropReason: ConnectionError,
    ) : ConnectionState

    /** The current session stopped because a typed failure was encountered. */
    data class Failed(
        val error: ConnectionError,
    ) : ConnectionState
}
