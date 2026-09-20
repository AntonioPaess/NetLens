package com.antoniopaess.netlens.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository boundary for connection operations. Implementations may adapt a
 * remote/local source or a connection engine, while callers remain unaware of
 * the concrete feature and storage choices.
 */
interface ConnectionRepository {
    /** The latest connection state exposed to presentation use cases. */
    val state: StateFlow<ConnectionState>

    /** The complete ordered session history retained by the source. */
    val events: StateFlow<List<ConnectionEvent>>

    /** Forward a domain command to the repository implementation. */
    suspend fun dispatch(command: ConnectionCommand)
}
