package com.antoniopaess.netlens.data.local

import androidx.room.Entity

/**
 * Explicit Room representation of one domain transition.
 *
 * State and reason fields are constrained strings plus scalar values. No
 * exception, response body, or serialized object graph is stored.
 */
@Entity(tableName = "connection_events")
data class ConnectionEventEntity(
    val eventId: Long,
    val timestamp: Long,
    val oldStateType: String,
    val oldRegionName: String?,
    val oldRegionCode: String?,
    val oldRegionLatencyMillis: Long?,
    val oldConnectedAtMillis: Long?,
    val oldAttempt: Int?,
    val oldErrorType: String?,
    val oldDropReasonType: String?,
    val newStateType: String,
    val newRegionName: String?,
    val newRegionCode: String?,
    val newRegionLatencyMillis: Long?,
    val newConnectedAtMillis: Long?,
    val newAttempt: Int?,
    val newErrorType: String?,
    val newDropReasonType: String?,
    val reasonType: String?,
    val reasonErrorType: String?,
    @androidx.room.PrimaryKey(autoGenerate = true)
    val storageId: Long = 0L,
)
