package com.antoniopaess.netlens.feature.connection.domain

import app.cash.turbine.test
import com.antoniopaess.netlens.domain.AttemptResult
import com.antoniopaess.netlens.domain.Clock
import com.antoniopaess.netlens.domain.ConnectionAttemptDriver
import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.ConnectionEventReason
import com.antoniopaess.netlens.domain.ConnectionState
import com.antoniopaess.netlens.domain.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStateMachineTest {
    private val region = Region(name = "Test Region", code = "TR", simulatedLatency = 0L)

    @Test
    fun `normal connection publishes connecting and connected with metadata`() =
        runTest {
            val clock = FixedClock(1_234L)
            val driver = ScriptedDriver(AttemptResult.Success)
            val machine = machine(driver, clock)

            machine.state.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())

                machine.start(region)
                assertEquals(ConnectionState.Connecting(region), awaitItem())

                runCurrent()
                assertEquals(ConnectionState.Connected(region, 1_234L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(2, machine.events.value.size)
            assertEquals(1L, machine.events.value[0].id)
            assertEquals(2L, machine.events.value[1].id)
            assertEquals(1_234L, machine.events.value[1].timestamp)
            assertEquals(ConnectionState.Connecting(region), machine.events.value[0].newState)
            assertEquals(ConnectionState.Connected(region, 1_234L), machine.events.value[1].newState)
            assertEquals(null, machine.events.value[1].reason)
        }

    @Test
    fun `initial typed failure enters failed`() =
        runTest {
            val errors =
                listOf(
                    ConnectionError.NetworkError,
                    ConnectionError.Timeout,
                    ConnectionError.ServerUnavailable,
                    ConnectionError.Unknown,
                )

            errors.forEach { error ->
                val machine = machine(ScriptedDriver(AttemptResult.Failure(error)))

                machine.start(region)
                runCurrent()

                assertEquals(ConnectionState.Failed(error), machine.state.value)
                assertEquals(
                    ConnectionEventReason.AttemptFailed(error),
                    machine.events.value
                        .last()
                        .reason,
                )
            }
        }

    @Test
    fun `timeout occurs at five second deadline`() =
        runTest {
            val driver = BlockingDriver()
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            assertEquals(ConnectionState.Connecting(region), machine.state.value)

            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 1L)
            runCurrent()
            assertEquals(ConnectionState.Connecting(region), machine.state.value)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(ConnectionState.Failed(ConnectionError.Timeout), machine.state.value)
            assertEquals(1, driver.attemptCount)
        }

    @Test
    fun `success just before deadline reaches connected`() =
        runTest {
            val driver = DelayedDriver(delayMillis = ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 1L)
            val machine = machine(driver, Clock { testScheduler.currentTime })

            machine.start(region)
            runCurrent()
            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 2L)
            runCurrent()
            assertEquals(ConnectionState.Connecting(region), machine.state.value)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(ConnectionState.Connected(region, 4_999L), machine.state.value)
        }

    @Test
    fun `attempt completing on deadline is timed out`() =
        runTest {
            val driver = DelayedDriver(delayMillis = ConnectionTiming.CONNECTION_TIMEOUT_MILLIS)
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS)
            runCurrent()

            assertEquals(ConnectionState.Failed(ConnectionError.Timeout), machine.state.value)
        }

    @Test
    fun `driver exception becomes unknown failure`() =
        runTest {
            val machine = machine(ThrowingDriver())

            machine.start(region)
            runCurrent()

            assertEquals(ConnectionState.Failed(ConnectionError.Unknown), machine.state.value)
        }

    @Test
    fun `automatic retries wait one two and four seconds and stop after three`() =
        runTest {
            val driver =
                ScriptedDriver(
                    AttemptResult.Success,
                    AttemptResult.Failure(ConnectionError.NetworkError),
                    AttemptResult.Failure(ConnectionError.ServerUnavailable),
                    AttemptResult.Failure(ConnectionError.Unknown),
                )
            val machine = machine(driver)

            machine.state.test {
                assertEquals(ConnectionState.Disconnected, awaitItem())
                machine.start(region)
                assertEquals(ConnectionState.Connecting(region), awaitItem())
                runCurrent()
                assertEquals(ConnectionState.Connected(region, 0L), awaitItem())

                driver.drop(ConnectionError.NetworkError)
                runCurrent()
                assertEquals(
                    ConnectionState.Reconnecting(region, 1, ConnectionError.NetworkError),
                    awaitItem(),
                )
                assertEquals(1, driver.attempts.size)
                expectNoEvents()

                advanceTimeBy(999L)
                runCurrent()
                assertEquals(1, driver.attempts.size)
                expectNoEvents()
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(
                    ConnectionState.Reconnecting(region, 2, ConnectionError.NetworkError),
                    awaitItem(),
                )
                assertEquals(2, driver.attempts.size)

                advanceTimeBy(1_999L)
                runCurrent()
                assertEquals(2, driver.attempts.size)
                expectNoEvents()
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(
                    ConnectionState.Reconnecting(region, 3, ConnectionError.NetworkError),
                    awaitItem(),
                )
                assertEquals(3, driver.attempts.size)

                advanceTimeBy(3_999L)
                runCurrent()
                assertEquals(3, driver.attempts.size)
                expectNoEvents()
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(ConnectionState.Failed(ConnectionError.Unknown), awaitItem())
                assertEquals(4, driver.attempts.size)
                advanceTimeBy(10_000L)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(4, driver.attempts.size)
        }

    @Test
    fun `reconnect succeeds after first retry`() =
        runTest {
            assertReconnectSuccess(
                retryAttempt = 1,
                results = listOf(AttemptResult.Success, AttemptResult.Success),
            )
        }

    @Test
    fun `reconnect succeeds after second retry`() =
        runTest {
            assertReconnectSuccess(
                retryAttempt = 2,
                results =
                    listOf(
                        AttemptResult.Success,
                        AttemptResult.Failure(ConnectionError.NetworkError),
                        AttemptResult.Success,
                    ),
            )
        }

    @Test
    fun `reconnect succeeds after third retry`() =
        runTest {
            assertReconnectSuccess(
                retryAttempt = 3,
                results =
                    listOf(
                        AttemptResult.Success,
                        AttemptResult.Failure(ConnectionError.NetworkError),
                        AttemptResult.Failure(ConnectionError.ServerUnavailable),
                        AttemptResult.Success,
                    ),
            )
        }

    @Test
    fun `a new drop starts automatic attempts again at one`() =
        runTest {
            val driver =
                ScriptedDriver(
                    AttemptResult.Success,
                    AttemptResult.Success,
                    AttemptResult.Success,
                )
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            driver.drop(ConnectionError.NetworkError)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(ConnectionState.Connected(region, 0L), machine.state.value)

            driver.drop(ConnectionError.ServerUnavailable)
            runCurrent()
            assertEquals(
                ConnectionState.Reconnecting(region, 1, ConnectionError.ServerUnavailable),
                machine.state.value,
            )
            assertTrue(
                machine.events.value.any { event ->
                    event.newState ==
                        ConnectionState.Reconnecting(
                            region,
                            1,
                            ConnectionError.ServerUnavailable,
                        )
                },
            )
        }

    @Test
    fun `manual retry changes failed to connecting and resets the budget`() =
        runTest {
            val driver =
                ScriptedDriver(
                    AttemptResult.Failure(ConnectionError.ServerUnavailable),
                    AttemptResult.Success,
                )
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            assertEquals(ConnectionState.Failed(ConnectionError.ServerUnavailable), machine.state.value)

            machine.retry()
            assertEquals(ConnectionState.Connecting(region), machine.state.value)
            runCurrent()
            assertEquals(ConnectionState.Connected(region, 0L), machine.state.value)
            assertEquals(ConnectionEventReason.UserRetried, machine.events.value[2].reason)
        }

    @Test
    fun `manual retry after timeout gets a fresh five second budget`() =
        runTest {
            val driver = TimeoutThenDelayedSuccessDriver()
            val machine = machine(driver, Clock { testScheduler.currentTime })

            machine.start(region)
            runCurrent()
            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(ConnectionState.Failed(ConnectionError.Timeout), machine.state.value)

            machine.retry()
            runCurrent()
            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 1L)
            runCurrent()

            assertEquals(ConnectionState.Connected(region, 9_999L), machine.state.value)
            assertEquals(2, driver.attemptCount)
        }

    @Test
    fun `disconnect from failed returns to disconnected`() =
        runTest {
            val machine = machine(ScriptedDriver(AttemptResult.Failure(ConnectionError.NetworkError)))

            machine.start(region)
            runCurrent()
            assertEquals(ConnectionState.Failed(ConnectionError.NetworkError), machine.state.value)

            machine.disconnect()

            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertEquals(
                ConnectionEventReason.UserDisconnected,
                machine.events.value
                    .last()
                    .reason,
            )
        }

    @Test
    fun `disconnect during connection prevents a later timeout`() =
        runTest {
            val driver = BlockingDriver()
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            machine.disconnect()
            assertEquals(ConnectionState.Disconnected, machine.state.value)

            advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS + 1_000L)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertFalse(machine.events.value.any { it.newState is ConnectionState.Failed })
        }

    @Test
    fun `disconnect during reconnect backoff prevents another attempt`() =
        runTest {
            val driver =
                ScriptedDriver(
                    AttemptResult.Success,
                    AttemptResult.Failure(ConnectionError.NetworkError),
                    AttemptResult.Success,
                )
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            driver.drop(ConnectionError.NetworkError)
            runCurrent()
            machine.disconnect()

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertEquals(1, driver.attempts.size)
        }

    @Test
    fun `a timed out reconnect attempt uses the same budget and then fails`() =
        runTest {
            val driver = TimeoutAfterInitialSuccessDriver()
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            driver.drop(ConnectionError.NetworkError)
            runCurrent()

            repeat(ConnectionTiming.MAX_RECONNECT_ATTEMPTS) { attemptIndex ->
                advanceTimeBy(ConnectionTiming.RECONNECT_BACKOFF_MILLIS[attemptIndex])
                runCurrent()
                assertEquals(
                    ConnectionState.Reconnecting(region, attemptIndex + 1, ConnectionError.NetworkError),
                    machine.state.value,
                )
                advanceTimeBy(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 1L)
                runCurrent()
                if (attemptIndex < ConnectionTiming.MAX_RECONNECT_ATTEMPTS - 1) {
                    assertEquals(
                        ConnectionState.Reconnecting(
                            region,
                            attemptIndex + 1,
                            ConnectionError.NetworkError,
                        ),
                        machine.state.value,
                    )
                }
                advanceTimeBy(1L)
                runCurrent()
            }

            assertEquals(ConnectionState.Failed(ConnectionError.Timeout), machine.state.value)
            assertEquals(ConnectionTiming.MAX_RECONNECT_ATTEMPTS + 1, driver.attemptCount)
        }

    @Test
    fun `disconnect from connected cancels drop monitoring`() =
        runTest {
            val driver = ScriptedDriver(AttemptResult.Success)
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            assertEquals(ConnectionState.Connected(region, 0L), machine.state.value)

            machine.disconnect()
            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertEquals(
                ConnectionEventReason.UserDisconnected,
                machine.events.value
                    .last()
                    .reason,
            )
        }

    @Test
    fun `repeated start while active is idempotent`() =
        runTest {
            val driver = BlockingDriver()
            val machine = machine(driver)
            val otherRegion = Region(name = "Other Region", code = "OR", simulatedLatency = 0L)

            machine.start(region)
            machine.start(otherRegion)
            runCurrent()

            assertEquals(ConnectionState.Connecting(region), machine.state.value)
            assertEquals(1, machine.events.value.size)
            assertEquals(1, driver.attemptCount)
            machine.disconnect()
        }

    @Test
    fun `non cooperative completion after disconnect cannot resurrect session`() =
        runTest {
            val driver = NonCooperativeDriver()
            val machine = machine(driver)

            machine.start(region)
            runCurrent()
            machine.disconnect()
            driver.complete(AttemptResult.Success)
            runCurrent()

            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertFalse(machine.events.value.any { it.newState is ConnectionState.Connected })
        }

    @Test
    fun `cancelled owner before worker dispatch ends disconnected without attempt`() =
        runTest {
            val owner = Job()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(owner + dispatcher)
            val driver = BlockingDriver()
            val machine = ConnectionStateMachine(scope, dispatcher, driver, FixedClock(0L))

            machine.start(region)
            owner.cancel()
            runCurrent()

            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertEquals(0, driver.attemptCount)
            assertFalse(machine.events.value.any { it.newState is ConnectionState.Failed })
        }

    @Test
    fun `cancelled owner during attempt ends disconnected without failed`() =
        runTest {
            val owner = Job()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(owner + dispatcher)
            val driver = BlockingDriver()
            val machine = ConnectionStateMachine(scope, dispatcher, driver, FixedClock(0L))

            machine.start(region)
            runCurrent()
            owner.cancel()
            runCurrent()

            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertFalse(machine.events.value.any { it.newState is ConnectionState.Failed })
        }

    @Test
    fun `starting with cancelled owner is a no op`() =
        runTest {
            val owner = Job()
            owner.cancel()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(owner + dispatcher)
            val driver = BlockingDriver()
            val machine = ConnectionStateMachine(scope, dispatcher, driver, FixedClock(0L))

            machine.start(region)
            runCurrent()

            assertEquals(ConnectionState.Disconnected, machine.state.value)
            assertTrue(machine.events.value.isEmpty())
            assertEquals(0, driver.attemptCount)
        }

    @Test
    fun `event history is retained without a collector and ids are ordered`() =
        runTest {
            val clock = FixedClock(9_000L)
            val driver = ScriptedDriver(AttemptResult.Success)
            val machine = machine(driver, clock)

            machine.start(region)
            runCurrent()
            machine.disconnect()

            val events = machine.events.value
            assertEquals(3, events.size)
            assertEquals(listOf(1L, 2L, 3L), events.map { it.id })
            assertEquals(listOf(9_000L, 9_000L, 9_000L), events.map { it.timestamp })
            assertEquals(ConnectionState.Disconnected, events.first().oldState)
            assertEquals(ConnectionState.Disconnected, events.last().newState)
            assertEquals(ConnectionEventReason.UserDisconnected, events.last().reason)
        }

    private suspend fun TestScope.assertReconnectSuccess(
        retryAttempt: Int,
        results: List<AttemptResult>,
    ) {
        val driver = ScriptedDriver(*results.toTypedArray())
        val machine = machine(driver, Clock { testScheduler.currentTime })

        machine.start(region)
        runCurrent()
        driver.drop(ConnectionError.NetworkError)
        runCurrent()

        repeat(retryAttempt) { attemptIndex ->
            advanceTimeBy(ConnectionTiming.RECONNECT_BACKOFF_MILLIS[attemptIndex])
            runCurrent()
        }

        val connectedAt =
            ConnectionTiming.RECONNECT_BACKOFF_MILLIS
                .take(retryAttempt)
                .sum()
        assertEquals(ConnectionState.Connected(region, connectedAt), machine.state.value)
        assertEquals(retryAttempt + 1, driver.attempts.size)
        assertEquals(
            ConnectionEventReason.ReconnectSucceeded,
            machine.events.value
                .last()
                .reason,
        )
        assertEquals(
            connectedAt,
            machine.events.value
                .last()
                .timestamp,
        )
    }

    private fun TestScope.machine(
        driver: ConnectionAttemptDriver,
        clock: Clock = FixedClock(0L),
    ): ConnectionStateMachine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ConnectionStateMachine(backgroundScope, dispatcher, driver, clock)
    }

    private class FixedClock(
        private val timestamp: Long,
    ) : Clock {
        override fun now(): Long = timestamp
    }

    private class ScriptedDriver(
        vararg results: AttemptResult,
    ) : ConnectionAttemptDriver {
        private val results = ArrayDeque(results.toList())
        private val drops = Channel<ConnectionError>(Channel.UNLIMITED)
        val attempts = mutableListOf<Region>()

        override suspend fun attempt(region: Region): AttemptResult {
            attempts += region
            return if (results.isEmpty()) AttemptResult.Success else results.removeFirst()
        }

        override suspend fun awaitDrop(region: Region): ConnectionError = drops.receive()

        fun drop(reason: ConnectionError) {
            drops.trySend(reason)
        }
    }

    private class BlockingDriver : ConnectionAttemptDriver {
        var attemptCount = 0

        override suspend fun attempt(region: Region): AttemptResult {
            attemptCount += 1
            awaitCancellation()
        }

        override suspend fun awaitDrop(region: Region): ConnectionError {
            awaitCancellation()
        }
    }

    private class DelayedDriver(
        private val delayMillis: Long,
    ) : ConnectionAttemptDriver {
        override suspend fun attempt(region: Region): AttemptResult {
            kotlinx.coroutines.delay(delayMillis)
            return AttemptResult.Success
        }

        override suspend fun awaitDrop(region: Region): ConnectionError = awaitCancellation()
    }

    private class TimeoutThenDelayedSuccessDriver : ConnectionAttemptDriver {
        var attemptCount = 0

        override suspend fun attempt(region: Region): AttemptResult {
            attemptCount += 1
            if (attemptCount == 1) {
                kotlinx.coroutines.delay(10_000L)
            } else {
                kotlinx.coroutines.delay(ConnectionTiming.CONNECTION_TIMEOUT_MILLIS - 1L)
            }
            return AttemptResult.Success
        }

        override suspend fun awaitDrop(region: Region): ConnectionError = awaitCancellation()
    }

    private class TimeoutAfterInitialSuccessDriver : ConnectionAttemptDriver {
        var attemptCount = 0
        private val drops = Channel<ConnectionError>(Channel.UNLIMITED)

        override suspend fun attempt(region: Region): AttemptResult {
            attemptCount += 1
            return if (attemptCount == 1) {
                AttemptResult.Success
            } else {
                awaitCancellation()
            }
        }

        override suspend fun awaitDrop(region: Region): ConnectionError = drops.receive()

        fun drop(reason: ConnectionError) {
            drops.trySend(reason)
        }
    }

    private class ThrowingDriver : ConnectionAttemptDriver {
        override suspend fun attempt(region: Region): AttemptResult = throw IllegalStateException()

        override suspend fun awaitDrop(region: Region): ConnectionError = awaitCancellation()
    }

    private class NonCooperativeDriver : ConnectionAttemptDriver {
        private var continuation: ((AttemptResult) -> Unit)? = null

        override suspend fun attempt(region: Region): AttemptResult =
            suspendCoroutine { cont ->
                continuation = { result -> cont.resume(result) }
            }

        override suspend fun awaitDrop(region: Region): ConnectionError = awaitCancellation()

        fun complete(result: AttemptResult) {
            continuation?.invoke(result)
        }
    }
}
