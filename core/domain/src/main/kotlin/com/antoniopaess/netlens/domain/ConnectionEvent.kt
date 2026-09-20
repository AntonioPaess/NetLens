package com.antoniopaess.netlens.domain

/**
 * A session transition record. The sequence id is assigned locally by the
 * engine, while the timestamp comes from its injected clock.
 */
data class ConnectionEvent(
    val id: Long,
    val timestamp: Long,
    val oldState: ConnectionState,
    val newState: ConnectionState,
    val reason: ConnectionEventReason? = null,
)

/**
 * Structured context for a transition. Presentation layers can translate the
 * values into localized labels without depending on exception text.
 */
sealed interface ConnectionEventReason {
    /** A user asked to start a disconnected session. */
    data object UserStarted : ConnectionEventReason

    /** A user asked to retry after a failed session. */
    data object UserRetried : ConnectionEventReason

    /** A user explicitly stopped the current session. */
    data object UserDisconnected : ConnectionEventReason

    /** The owner scope cancelled an active session before it completed. */
    data object SessionCancelled : ConnectionEventReason

    /** The initial or reconnect attempt returned a typed failure. */
    data class AttemptFailed(
        val error: ConnectionError,
    ) : ConnectionEventReason

    /** The connected driver reported that the connection dropped. */
    data class ConnectionDropped(
        val error: ConnectionError,
    ) : ConnectionEventReason

    /** A reconnect attempt succeeded and restored the connection. */
    data object ReconnectSucceeded : ConnectionEventReason
}
