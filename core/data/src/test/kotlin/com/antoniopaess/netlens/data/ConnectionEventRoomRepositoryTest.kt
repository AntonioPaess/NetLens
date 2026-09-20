package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.local.ConnectionEventDao
import com.antoniopaess.netlens.data.local.ConnectionEventEntity
import com.antoniopaess.netlens.data.local.toDomain
import com.antoniopaess.netlens.data.local.toEntity
import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.ConnectionEvent
import com.antoniopaess.netlens.domain.ConnectionEventReason
import com.antoniopaess.netlens.domain.ConnectionState
import com.antoniopaess.netlens.domain.Region
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionEventRoomRepositoryTest {
    @Test
    fun eventRoundTripPreservesStatesAndReason() {
        val region = Region("Test region", "TR", 123L)
        val event =
            ConnectionEvent(
                id = 7L,
                timestamp = 42L,
                oldState = ConnectionState.Connected(region, connectedAt = 12L),
                newState =
                    ConnectionState.Reconnecting(
                        region = region,
                        attempt = 1,
                        dropReason = ConnectionError.NetworkError,
                    ),
                reason = ConnectionEventReason.ConnectionDropped(ConnectionError.NetworkError),
            )

        assertEquals(event, event.toEntity().toDomain())
    }

    @Test
    fun duplicateEngineEventIdsRemainDistinctInStorage() {
        val first =
            ConnectionEvent(
                id = 1L,
                timestamp = 10L,
                oldState = ConnectionState.Disconnected,
                newState = ConnectionState.Connecting(Region("First", "AA", 10L)),
            ).toEntity().copy(storageId = 1L)
        val second =
            ConnectionEvent(
                id = 1L,
                timestamp = 20L,
                oldState = ConnectionState.Disconnected,
                newState = ConnectionState.Connecting(Region("Second", "BB", 20L)),
            ).toEntity().copy(storageId = 2L)

        assertEquals(listOf(first, second), listOf(second, first).sortedBy { it.storageId })
        assertEquals(first.eventId, second.eventId)
    }

    @Test
    fun repositoryStoresAndMapsEventsThroughDao() =
        runTest {
            val dao = FakeConnectionEventDao()
            val repository = RoomConnectionEventRepository(dao)
            val event =
                ConnectionEvent(
                    id = 1L,
                    timestamp = 10L,
                    oldState = ConnectionState.Disconnected,
                    newState = ConnectionState.Failed(ConnectionError.Timeout),
                    reason = ConnectionEventReason.AttemptFailed(ConnectionError.Timeout),
                )

            repository.append(event)

            assertEquals(listOf(event), repository.observeEvents().first())
        }

    private class FakeConnectionEventDao : ConnectionEventDao {
        private val events = MutableStateFlow<List<ConnectionEventEntity>>(emptyList())

        override fun observeAll(): Flow<List<ConnectionEventEntity>> = events

        override suspend fun insert(event: ConnectionEventEntity): Long {
            val storageId = events.value.size.toLong() + 1L
            events.value = events.value + event.copy(storageId = storageId)
            return storageId
        }
    }
}
