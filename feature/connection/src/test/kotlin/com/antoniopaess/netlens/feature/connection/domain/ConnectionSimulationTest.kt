package com.antoniopaess.netlens.feature.connection.domain

import com.antoniopaess.netlens.domain.AttemptResult
import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.Region
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSimulationTest {
    private val region = Region(name = "Test Region", code = "TR", simulatedLatency = 0L)

    @Test
    fun `zero attempt failure probability always succeeds`() =
        runTest {
            val region = region.copy(simulatedLatency = 250L)
            val driver =
                SimulatedConnectionDriver(
                    randomSource = RandomSource { 0.0 },
                    config =
                        ConnectionSimulationConfig(
                            attemptFailureProbability = 0.0,
                            dropProbability = 0.0,
                            dropCheckIntervalMillis = 1_000L,
                        ),
                )

            val result = async { driver.attempt(region) }
            runCurrent()
            assertEquals(false, result.isCompleted)
            advanceTimeBy(249L)
            runCurrent()
            assertEquals(false, result.isCompleted)
            advanceTimeBy(1L)
            assertEquals(AttemptResult.Success, result.await())
        }

    @Test
    fun `one attempt failure probability returns configured typed error`() =
        runTest {
            val driver =
                SimulatedConnectionDriver(
                    randomSource = RandomSource { 0.99 },
                    config =
                        ConnectionSimulationConfig(
                            attemptFailureProbability = 1.0,
                            dropProbability = 0.0,
                            dropCheckIntervalMillis = 1_000L,
                            attemptFailure = ConnectionError.NetworkError,
                        ),
                )

            assertEquals(
                AttemptResult.Failure(ConnectionError.NetworkError),
                driver.attempt(region),
            )
        }

    @Test
    fun `one drop probability emits configured reason after check interval`() =
        runTest {
            val driver =
                SimulatedConnectionDriver(
                    randomSource = RandomSource { 0.99 },
                    config =
                        ConnectionSimulationConfig(
                            attemptFailureProbability = 0.0,
                            dropProbability = 1.0,
                            dropCheckIntervalMillis = 1_500L,
                            dropReason = ConnectionError.ServerUnavailable,
                        ),
                )
            val result = async { driver.awaitDrop(region) }

            runCurrent()
            assertEquals(false, result.isCompleted)
            advanceTimeBy(1_499L)
            runCurrent()
            assertEquals(false, result.isCompleted)
            advanceTimeBy(1L)
            assertEquals(ConnectionError.ServerUnavailable, result.await())
        }

    @Test
    fun `zero drop probability keeps waiting across several checks`() =
        runTest {
            val driver =
                SimulatedConnectionDriver(
                    randomSource = RandomSource { 0.0 },
                    config =
                        ConnectionSimulationConfig(
                            attemptFailureProbability = 0.0,
                            dropProbability = 0.0,
                            dropCheckIntervalMillis = 100L,
                        ),
                )
            val result = async { driver.awaitDrop(region) }

            repeat(3) {
                advanceTimeBy(100L)
                runCurrent()
            }
            assertEquals(false, result.isCompleted)
            result.cancelAndJoin()
        }

    @Test(expected = IllegalArgumentException::class)
    fun `negative attempt probability is rejected`() {
        ConnectionSimulationConfig(attemptFailureProbability = -0.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `probability above one is rejected`() {
        ConnectionSimulationConfig(dropProbability = 1.01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non finite probability is rejected`() {
        ConnectionSimulationConfig(dropProbability = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero drop interval is rejected`() {
        ConnectionSimulationConfig(dropCheckIntervalMillis = 0L)
    }

    @Test
    fun `random source adapter delegates to supplied generator`() {
        val expected = kotlin.random.Random(42)
        val source = DefaultRandomSource(kotlin.random.Random(42))

        val first = source.nextDouble()
        val second = source.nextDouble()

        assertEquals(expected.nextDouble(), first, 0.0)
        assertEquals(expected.nextDouble(), second, 0.0)
    }
}
