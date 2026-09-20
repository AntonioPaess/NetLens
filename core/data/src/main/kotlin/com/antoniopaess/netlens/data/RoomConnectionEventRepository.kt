package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.local.ConnectionEventDao
import com.antoniopaess.netlens.data.local.toDomain
import com.antoniopaess.netlens.data.local.toEntity
import com.antoniopaess.netlens.domain.ConnectionEvent
import com.antoniopaess.netlens.domain.ConnectionEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed persistence adapter for connection transition history. */
class RoomConnectionEventRepository(
    private val dao: ConnectionEventDao,
) : ConnectionEventRepository {
    override fun observeEvents(): Flow<List<ConnectionEvent>> = dao.observeAll().map { events -> events.map { it.toDomain() } }

    override suspend fun append(event: ConnectionEvent) {
        dao.insert(event.toEntity())
    }
}
