package com.antoniopaess.netlens.data.local

import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.ConnectionEvent
import com.antoniopaess.netlens.domain.ConnectionEventReason
import com.antoniopaess.netlens.domain.ConnectionState
import com.antoniopaess.netlens.domain.Region

/** Stable scalar names used by the version-one event schema. */
private object EventType {
    const val DISCONNECTED = "DISCONNECTED"
    const val CONNECTING = "CONNECTING"
    const val CONNECTED = "CONNECTED"
    const val RECONNECTING = "RECONNECTING"
    const val FAILED = "FAILED"

    const val USER_STARTED = "USER_STARTED"
    const val USER_RETRIED = "USER_RETRIED"
    const val USER_DISCONNECTED = "USER_DISCONNECTED"
    const val SESSION_CANCELLED = "SESSION_CANCELLED"
    const val ATTEMPT_FAILED = "ATTEMPT_FAILED"
    const val CONNECTION_DROPPED = "CONNECTION_DROPPED"
    const val RECONNECT_SUCCEEDED = "RECONNECT_SUCCEEDED"
}

/** Convert a domain event into Room's scalar-only representation. */
fun ConnectionEvent.toEntity(): ConnectionEventEntity {
    val old = oldState.toColumns()
    val new = newState.toColumns()
    val reason = reason.toColumns()
    return ConnectionEventEntity(
        eventId = id,
        timestamp = timestamp,
        oldStateType = old.type,
        oldRegionName = old.regionName,
        oldRegionCode = old.regionCode,
        oldRegionLatencyMillis = old.regionLatencyMillis,
        oldConnectedAtMillis = old.connectedAtMillis,
        oldAttempt = old.attempt,
        oldErrorType = old.errorType,
        oldDropReasonType = old.dropReasonType,
        newStateType = new.type,
        newRegionName = new.regionName,
        newRegionCode = new.regionCode,
        newRegionLatencyMillis = new.regionLatencyMillis,
        newConnectedAtMillis = new.connectedAtMillis,
        newAttempt = new.attempt,
        newErrorType = new.errorType,
        newDropReasonType = new.dropReasonType,
        reasonType = reason.type,
        reasonErrorType = reason.errorType,
    )
}

/** Convert a persisted event back to domain types, using [Unknown] for old unknown error tags. */
fun ConnectionEventEntity.toDomain(): ConnectionEvent =
    ConnectionEvent(
        id = eventId,
        timestamp = timestamp,
        oldState = oldState(),
        newState = newState(),
        reason = reason(),
    )

private data class StateColumns(
    val type: String,
    val regionName: String? = null,
    val regionCode: String? = null,
    val regionLatencyMillis: Long? = null,
    val connectedAtMillis: Long? = null,
    val attempt: Int? = null,
    val errorType: String? = null,
    val dropReasonType: String? = null,
)

private data class ReasonColumns(
    val type: String?,
    val errorType: String?,
)

private fun ConnectionState.toColumns(): StateColumns =
    when (this) {
        ConnectionState.Disconnected -> StateColumns(EventType.DISCONNECTED)
        is ConnectionState.Connecting ->
            StateColumns(
                type = EventType.CONNECTING,
                regionName = region.name,
                regionCode = region.code,
                regionLatencyMillis = region.simulatedLatency,
            )
        is ConnectionState.Connected ->
            StateColumns(
                type = EventType.CONNECTED,
                regionName = region.name,
                regionCode = region.code,
                regionLatencyMillis = region.simulatedLatency,
                connectedAtMillis = connectedAt,
            )
        is ConnectionState.Reconnecting ->
            StateColumns(
                type = EventType.RECONNECTING,
                regionName = region.name,
                regionCode = region.code,
                regionLatencyMillis = region.simulatedLatency,
                attempt = attempt,
                dropReasonType = dropReason.toType(),
            )
        is ConnectionState.Failed ->
            StateColumns(
                type = EventType.FAILED,
                errorType = error.toType(),
            )
    }

private fun ConnectionEventReason?.toColumns(): ReasonColumns =
    when (this) {
        null -> ReasonColumns(type = null, errorType = null)
        ConnectionEventReason.UserStarted -> ReasonColumns(EventType.USER_STARTED, null)
        ConnectionEventReason.UserRetried -> ReasonColumns(EventType.USER_RETRIED, null)
        ConnectionEventReason.UserDisconnected -> ReasonColumns(EventType.USER_DISCONNECTED, null)
        ConnectionEventReason.SessionCancelled -> ReasonColumns(EventType.SESSION_CANCELLED, null)
        is ConnectionEventReason.AttemptFailed ->
            ReasonColumns(EventType.ATTEMPT_FAILED, error.toType())
        is ConnectionEventReason.ConnectionDropped ->
            ReasonColumns(EventType.CONNECTION_DROPPED, error.toType())
        ConnectionEventReason.ReconnectSucceeded -> ReasonColumns(EventType.RECONNECT_SUCCEEDED, null)
    }

private fun ConnectionError.toType(): String =
    when (this) {
        ConnectionError.NetworkError -> "NETWORK_ERROR"
        ConnectionError.Timeout -> "TIMEOUT"
        ConnectionError.ServerUnavailable -> "SERVER_UNAVAILABLE"
        ConnectionError.Unknown -> "UNKNOWN"
    }

private fun ConnectionEventEntity.oldState(): ConnectionState =
    stateFromColumns(
        type = oldStateType,
        regionName = oldRegionName,
        regionCode = oldRegionCode,
        latency = oldRegionLatencyMillis,
        connectedAt = oldConnectedAtMillis,
        attempt = oldAttempt,
        errorType = oldErrorType,
        dropReasonType = oldDropReasonType,
    )

private fun ConnectionEventEntity.newState(): ConnectionState =
    stateFromColumns(
        type = newStateType,
        regionName = newRegionName,
        regionCode = newRegionCode,
        latency = newRegionLatencyMillis,
        connectedAt = newConnectedAtMillis,
        attempt = newAttempt,
        errorType = newErrorType,
        dropReasonType = newDropReasonType,
    )

private fun stateFromColumns(
    type: String,
    regionName: String?,
    regionCode: String?,
    latency: Long?,
    connectedAt: Long?,
    attempt: Int?,
    errorType: String?,
    dropReasonType: String?,
): ConnectionState =
    when (type) {
        EventType.DISCONNECTED -> ConnectionState.Disconnected
        EventType.CONNECTING -> ConnectionState.Connecting(regionFromColumns(regionName, regionCode, latency))
        EventType.CONNECTED ->
            ConnectionState.Connected(
                region = regionFromColumns(regionName, regionCode, latency),
                connectedAt = checkNotNull(connectedAt),
            )
        EventType.RECONNECTING ->
            ConnectionState.Reconnecting(
                region = regionFromColumns(regionName, regionCode, latency),
                attempt = checkNotNull(attempt),
                dropReason = errorFromType(dropReasonType),
            )
        EventType.FAILED -> ConnectionState.Failed(errorFromType(errorType))
        else -> error("Unknown persisted connection state type: $type")
    }

private fun regionFromColumns(
    name: String?,
    code: String?,
    latency: Long?,
): Region =
    Region(
        name = checkNotNull(name),
        code = checkNotNull(code),
        simulatedLatency = checkNotNull(latency),
    )

private fun ConnectionEventEntity.reason(): ConnectionEventReason? =
    when (reasonType) {
        null -> null
        EventType.USER_STARTED -> ConnectionEventReason.UserStarted
        EventType.USER_RETRIED -> ConnectionEventReason.UserRetried
        EventType.USER_DISCONNECTED -> ConnectionEventReason.UserDisconnected
        EventType.SESSION_CANCELLED -> ConnectionEventReason.SessionCancelled
        EventType.ATTEMPT_FAILED -> ConnectionEventReason.AttemptFailed(errorFromType(reasonErrorType))
        EventType.CONNECTION_DROPPED -> ConnectionEventReason.ConnectionDropped(errorFromType(reasonErrorType))
        EventType.RECONNECT_SUCCEEDED -> ConnectionEventReason.ReconnectSucceeded
        else -> error("Unknown persisted connection event reason: $reasonType")
    }

private fun errorFromType(type: String?): ConnectionError =
    when (type) {
        "NETWORK_ERROR" -> ConnectionError.NetworkError
        "TIMEOUT" -> ConnectionError.Timeout
        "SERVER_UNAVAILABLE" -> ConnectionError.ServerUnavailable
        "UNKNOWN", null -> ConnectionError.Unknown
        else -> ConnectionError.Unknown
    }
