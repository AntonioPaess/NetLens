package com.antoniopaess.netlens.feature.connection.domain

import com.antoniopaess.netlens.domain.AttemptResult
import com.antoniopaess.netlens.domain.Clock
import com.antoniopaess.netlens.domain.ConnectionAttemptDriver
import com.antoniopaess.netlens.domain.ConnectionCommand
import com.antoniopaess.netlens.domain.ConnectionEngine
import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.ConnectionEvent
import com.antoniopaess.netlens.domain.ConnectionEventReason
import com.antoniopaess.netlens.domain.ConnectionState
import com.antoniopaess.netlens.domain.Region
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes connection commands and drives the domain state transitions.
 *
 * The scope, dispatcher, clock, and driver are supplied by composition so the
 * same machine can run with a real lifecycle or a virtual test scheduler. A
 * monotonically increasing session id protects state publication when a
 * driver does not stop immediately after cancellation.
 */
class ConnectionStateMachine(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val driver: ConnectionAttemptDriver,
    private val clock: Clock,
) : ConnectionEngine {
    private val commandMutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableEvents = MutableStateFlow<List<ConnectionEvent>>(emptyList())

    private var nextEventId = 1L
    private var sessionId = 0L
    private var sessionJob: Job? = null

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val events: StateFlow<List<ConnectionEvent>> = mutableEvents.asStateFlow()

    override suspend fun dispatch(command: ConnectionCommand) {
        commandMutex.withLock {
            when (command) {
                is ConnectionCommand.Start -> {
                    if (mutableState.value is ConnectionState.Disconnected) {
                        beginSessionLocked(command.region, ConnectionEventReason.UserStarted)
                    }
                    // Starting while a session is active is intentionally a no-op. The
                    // selected region cannot change underneath an active session.
                }

                ConnectionCommand.Retry -> {
                    if (mutableState.value is ConnectionState.Failed) {
                        beginSessionLocked(
                            region = regionForRetry(),
                            reason = ConnectionEventReason.UserRetried,
                        )
                    }
                }

                ConnectionCommand.Disconnect -> disconnectLocked()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun beginSessionLocked(
        region: Region,
        reason: ConnectionEventReason,
    ) {
        if (!scope.isActive) return

        lastRegion = region
        sessionId += 1
        val currentSessionId = sessionId
        transitionLocked(
            newState = ConnectionState.Connecting(region),
            reason = reason,
        )
        // ATOMIC makes the worker enter its finally block even when its owner
        // is cancelled after this command but before the dispatcher runs it.
        sessionJob =
            scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
                runSession(currentSessionId, region)
            }
        if (sessionJob?.isActive != true) {
            sessionId += 1
            sessionJob = null
            transitionLocked(
                newState = ConnectionState.Disconnected,
                reason = ConnectionEventReason.SessionCancelled,
            )
        }
    }

    private fun disconnectLocked() {
        if (mutableState.value is ConnectionState.Disconnected) return

        // Invalidate before cancellation: a non-cooperative driver may finish
        // later, but it can no longer publish a state for this session.
        sessionId += 1
        sessionJob?.cancel()
        sessionJob = null
        transitionLocked(
            newState = ConnectionState.Disconnected,
            reason = ConnectionEventReason.UserDisconnected,
        )
    }

    private suspend fun runSession(
        currentSessionId: Long,
        region: Region,
    ) {
        var cancelled = false
        try {
            currentCoroutineContext().ensureActive()
            when (val result = attemptWithTimeout(region)) {
                AttemptResult.Success -> {
                    val connectedAt = clock.now()
                    if (!transitionIfCurrent(
                            currentSessionId = currentSessionId,
                            expectedState = ConnectionState.Connecting(region),
                            newState = ConnectionState.Connected(region, connectedAt),
                            reason = null,
                            transitionTimestamp = connectedAt,
                        )
                    ) {
                        return
                    }
                    monitorConnection(currentSessionId, region)
                }

                is AttemptResult.Failure -> {
                    transitionIfCurrent(
                        currentSessionId = currentSessionId,
                        expectedState = ConnectionState.Connecting(region),
                        newState = ConnectionState.Failed(result.error),
                        reason = ConnectionEventReason.AttemptFailed(result.error),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is a control signal from disconnect or lifecycle
            // shutdown, so it must never be converted into Failed.
            cancelled = true
            throw cancellation
        } catch (failure: Exception) {
            transitionIfCurrent(
                currentSessionId = currentSessionId,
                expectedState = ConnectionState.Connecting(region),
                newState = ConnectionState.Failed(ConnectionError.Unknown),
                reason = ConnectionEventReason.AttemptFailed(ConnectionError.Unknown),
            )
        } finally {
            withContext(NonCancellable) {
                commandMutex.withLock {
                    if (sessionId == currentSessionId) {
                        sessionJob = null
                        sessionId += 1
                        if (cancelled && mutableState.value !is ConnectionState.Disconnected) {
                            transitionLocked(
                                newState = ConnectionState.Disconnected,
                                reason = ConnectionEventReason.SessionCancelled,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun attemptWithTimeout(region: Region): AttemptResult =
        try {
            withTimeoutOrNull(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS) {
                driver.attempt(region)
            } ?: AttemptResult.Failure(ConnectionError.Timeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AttemptResult.Failure(ConnectionError.Unknown)
        }

    private suspend fun monitorConnection(
        currentSessionId: Long,
        region: Region,
    ) {
        while (isConnected(currentSessionId, region)) {
            val dropReason =
                try {
                    driver.awaitDrop(region)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    ConnectionError.Unknown
                }

            if (!transitionConnectedToReconnectingIfCurrent(
                    currentSessionId = currentSessionId,
                    region = region,
                    dropReason = dropReason,
                    reason = ConnectionEventReason.ConnectionDropped(dropReason),
                )
            ) {
                return
            }

            if (!reconnect(currentSessionId, region, dropReason)) return
        }
    }

    private suspend fun reconnect(
        currentSessionId: Long,
        region: Region,
        dropReason: ConnectionError,
    ): Boolean {
        for (attempt in 1..ConnectionTiming.MAX_RECONNECT_ATTEMPTS) {
            if (!isCurrent(
                    currentSessionId,
                    ConnectionState.Reconnecting(region, attempt, dropReason),
                )
            ) {
                return false
            }

            delay(ConnectionTiming.RECONNECT_BACKOFF_MILLIS[attempt - 1])
            if (!isCurrent(
                    currentSessionId,
                    ConnectionState.Reconnecting(region, attempt, dropReason),
                )
            ) {
                return false
            }

            val result = attemptWithTimeout(region)

            when (result) {
                AttemptResult.Success -> {
                    val connectedAt = clock.now()
                    return transitionIfCurrent(
                        currentSessionId = currentSessionId,
                        expectedState = ConnectionState.Reconnecting(region, attempt, dropReason),
                        newState = ConnectionState.Connected(region, connectedAt),
                        reason = ConnectionEventReason.ReconnectSucceeded,
                        transitionTimestamp = connectedAt,
                    )
                }

                is AttemptResult.Failure -> {
                    if (attempt == ConnectionTiming.MAX_RECONNECT_ATTEMPTS) {
                        transitionIfCurrent(
                            currentSessionId = currentSessionId,
                            expectedState = ConnectionState.Reconnecting(region, attempt, dropReason),
                            newState = ConnectionState.Failed(result.error),
                            reason = ConnectionEventReason.AttemptFailed(result.error),
                        )
                        return false
                    }

                    if (!transitionIfCurrent(
                            currentSessionId = currentSessionId,
                            expectedState = ConnectionState.Reconnecting(region, attempt, dropReason),
                            newState =
                                ConnectionState.Reconnecting(
                                    region = region,
                                    attempt = attempt + 1,
                                    dropReason = dropReason,
                                ),
                            reason = ConnectionEventReason.AttemptFailed(result.error),
                        )
                    ) {
                        return false
                    }
                }
            }
        }
        return false
    }

    private suspend fun transitionIfCurrent(
        currentSessionId: Long,
        expectedState: ConnectionState,
        newState: ConnectionState,
        reason: ConnectionEventReason?,
        transitionTimestamp: Long? = null,
    ): Boolean =
        commandMutex.withLock {
            if (sessionId != currentSessionId || mutableState.value != expectedState) return@withLock false
            transitionLocked(newState, reason, transitionTimestamp)
            true
        }

    private suspend fun isCurrent(
        currentSessionId: Long,
        expectedState: ConnectionState,
    ): Boolean =
        commandMutex.withLock {
            sessionId == currentSessionId && mutableState.value == expectedState
        }

    private suspend fun isConnected(
        currentSessionId: Long,
        region: Region,
    ): Boolean =
        commandMutex.withLock {
            sessionId == currentSessionId &&
                (mutableState.value as? ConnectionState.Connected)?.region == region
        }

    private suspend fun transitionConnectedToReconnectingIfCurrent(
        currentSessionId: Long,
        region: Region,
        dropReason: ConnectionError,
        reason: ConnectionEventReason,
    ): Boolean =
        commandMutex.withLock {
            val currentState = mutableState.value
            if (
                sessionId != currentSessionId ||
                currentState !is ConnectionState.Connected ||
                currentState.region != region
            ) {
                return@withLock false
            }
            transitionLocked(
                newState =
                    ConnectionState.Reconnecting(
                        region = region,
                        attempt = 1,
                        dropReason = dropReason,
                    ),
                reason = reason,
            )
            true
        }

    private fun transitionLocked(
        newState: ConnectionState,
        reason: ConnectionEventReason?,
        timestamp: Long? = null,
    ) {
        val oldState = mutableState.value
        if (oldState == newState) return

        mutableState.value = newState
        mutableEvents.value = mutableEvents.value +
            ConnectionEvent(
                id = nextEventId++,
                timestamp = timestamp ?: clock.now(),
                oldState = oldState,
                newState = newState,
                reason = reason,
            )
    }

    private fun regionForRetry(): Region {
        // Failed intentionally carries only the error. The last selected region
        // is retained by the machine so Retry can restart the same session.
        return checkNotNull(lastRegion)
    }

    private var lastRegion: Region? = null
}
