package com.antoniopaess.netlens.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persists session transitions without exposing Room or entity details to the
 * connection feature.
 *
 * Implementations may batch or transactionally write events, but callers only
 * depend on the ordered domain records needed by the future history screen.
 */
interface ConnectionEventRepository {
    /** Observe persisted events in chronological sequence order. */
    fun observeEvents(): Flow<List<ConnectionEvent>>

    /** Append one already-validated transition record. */
    suspend fun append(event: ConnectionEvent)
}
