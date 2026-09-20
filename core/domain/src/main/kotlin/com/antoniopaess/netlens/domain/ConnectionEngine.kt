package com.antoniopaess.netlens.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Asynchronous connection boundary adapted by repositories for use cases.
 *
 * The feature state machine implements this contract; keeping observations and
 * commands here allows `core:data` to wrap a future engine without a dependency
 * back on `feature:connection`.
 */
interface ConnectionEngine {
    /** The latest state, including the initial disconnected value. */
    val state: StateFlow<ConnectionState>

    /** Every transition in sequence order, including events emitted without a collector. */
    val events: StateFlow<List<ConnectionEvent>>

    /** Serialize and apply one command to the engine. */
    suspend fun dispatch(command: ConnectionCommand)

    /** Convenience command for starting a session. */
    suspend fun start(region: Region) = dispatch(ConnectionCommand.Start(region))

    /** Convenience command for manually retrying a failed session. */
    suspend fun retry() = dispatch(ConnectionCommand.Retry)

    /** Convenience command for stopping a session. */
    suspend fun disconnect() = dispatch(ConnectionCommand.Disconnect)
}
