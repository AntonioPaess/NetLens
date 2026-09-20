package com.antoniopaess.netlens.feature.connection.domain

import com.antoniopaess.netlens.domain.AttemptResult
import com.antoniopaess.netlens.domain.ConnectionAttemptDriver
import com.antoniopaess.netlens.domain.ConnectionError
import com.antoniopaess.netlens.domain.Region
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Source of reproducible random values for the simulation boundary.
 *
 * The state machine never draws randomness itself; tests can provide a fixed
 * sequence while the default adapter uses Kotlin's platform generator.
 */
fun interface RandomSource {
    /** Return a value in the inclusive-exclusive range [0, 1). */
    fun nextDouble(): Double
}

/** Random source backed by Kotlin's default generator for production wiring. */
class DefaultRandomSource(
    private val random: Random = Random.Default,
) : RandomSource {
    override fun nextDouble(): Double = random.nextDouble()
}

/**
 * Named inputs for the connection simulation. Probabilities are validated at
 * construction so a bad configuration cannot silently alter state semantics.
 */
data class ConnectionSimulationConfig(
    val attemptFailureProbability: Double = ConnectionSimulationDefaults.ATTEMPT_FAILURE_PROBABILITY,
    val dropProbability: Double = ConnectionSimulationDefaults.DROP_PROBABILITY,
    val dropCheckIntervalMillis: Long = ConnectionSimulationDefaults.DROP_CHECK_INTERVAL_MILLIS,
    val attemptFailure: ConnectionError = ConnectionError.ServerUnavailable,
    val dropReason: ConnectionError = ConnectionError.NetworkError,
) {
    init {
        require(attemptFailureProbability.isFinite())
        require(attemptFailureProbability in 0.0..1.0)
        require(dropProbability.isFinite())
        require(dropProbability in 0.0..1.0)
        require(dropCheckIntervalMillis > 0)
    }
}

/**
 * Demonstration defaults for local simulation. They intentionally describe
 * failure behavior rather than claiming to model measured network reliability.
 */
object ConnectionSimulationDefaults {
    /** Initial-attempt and automatic-retry failure probability used by the demonstration. */
    const val ATTEMPT_FAILURE_PROBABILITY: Double = 0.2

    /** Per-check connection-drop probability used by the demonstration. */
    const val DROP_PROBABILITY: Double = 0.1

    /** Delay between drop checks, in milliseconds. */
    const val DROP_CHECK_INTERVAL_MILLIS: Long = 8_000L
}

/**
 * Driver used by the app's local simulation. Delays represent endpoint
 * latency and connection lifetime; cancellation interrupts both operations.
 */
class SimulatedConnectionDriver(
    private val randomSource: RandomSource,
    private val config: ConnectionSimulationConfig = ConnectionSimulationConfig(),
) : ConnectionAttemptDriver {
    override suspend fun attempt(region: Region): AttemptResult {
        delay(region.simulatedLatency)
        return if (randomSource.nextDouble() < config.attemptFailureProbability) {
            AttemptResult.Failure(config.attemptFailure)
        } else {
            AttemptResult.Success
        }
    }

    override suspend fun awaitDrop(region: Region): ConnectionError {
        while (true) {
            delay(config.dropCheckIntervalMillis)
            if (randomSource.nextDouble() < config.dropProbability) return config.dropReason
        }
    }
}
