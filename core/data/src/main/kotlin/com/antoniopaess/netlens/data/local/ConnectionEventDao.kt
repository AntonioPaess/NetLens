package com.antoniopaess.netlens.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Persistence operations for the future event history screen. */
@Dao
interface ConnectionEventDao {
    /** Observe all transitions in sequence order. */
    @Query("SELECT * FROM connection_events ORDER BY storageId ASC")
    fun observeAll(): Flow<List<ConnectionEventEntity>>

    /** Append one transition, retaining both storage and engine sequence identifiers. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ConnectionEventEntity): Long
}
