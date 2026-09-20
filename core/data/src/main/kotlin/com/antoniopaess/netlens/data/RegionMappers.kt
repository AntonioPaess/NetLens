package com.antoniopaess.netlens.data

import com.antoniopaess.netlens.data.local.RegionEntity
import com.antoniopaess.netlens.data.remote.CountryDto
import com.antoniopaess.netlens.domain.Region
import java.util.Locale

/**
 * Computes the latency shown by the local connection simulation.
 *
 * The value is deliberately derived from stable region data rather than a
 * network measurement. That keeps the region list useful offline and makes
 * the simulation reproducible on every device and in JVM tests.
 */
fun interface RegionLatencyCalculator {
    /** Return a deterministic, non-negative simulated latency in milliseconds. */
    fun calculate(
        name: String,
        code: String,
    ): Long
}

/** Default deterministic latency policy used when the API has no measurement. */
object DeterministicRegionLatency : RegionLatencyCalculator {
    private const val MINIMUM_MILLIS = 35L
    private const val VARIATION_MILLIS = 240L

    override fun calculate(
        name: String,
        code: String,
    ): Long {
        val normalized = "${code.trim().uppercase(Locale.ROOT)}:${name.trim()}"
        val hash =
            normalized.fold(17L) { result, character ->
                (result * 31L + character.code) and Long.MAX_VALUE
            }
        return MINIMUM_MILLIS + (hash % VARIATION_MILLIS)
    }
}

/** Convert one persisted row back into the domain representation. */
fun RegionEntity.toDomain(): Region =
    Region(
        name = name,
        code = code,
        simulatedLatency = diagnosticLatencyMillis,
    )

/** Convert a domain region into its Room representation. */
fun Region.toEntity(): RegionEntity =
    RegionEntity(
        code = code,
        name = name,
        diagnosticLatencyMillis = simulatedLatency,
    )

/** Convert the minimal Rest Countries response into a domain region. */
fun CountryDto.toDomain(latencyCalculator: RegionLatencyCalculator = DeterministicRegionLatency): Region? {
    val regionName = name.common.trim()
    val regionCode = cca2?.trim()?.uppercase(Locale.ROOT)
    if (regionName.isBlank() || regionCode.isNullOrBlank()) return null

    return Region(
        name = regionName,
        code = regionCode,
        simulatedLatency = latencyCalculator.calculate(regionName, regionCode),
    )
}

/** Convert and sort a remote snapshot for stable cache and presentation output. */
fun List<CountryDto>.toDomainRegions(latencyCalculator: RegionLatencyCalculator = DeterministicRegionLatency): List<Region> =
    mapNotNull { it.toDomain(latencyCalculator) }
        .distinctBy { it.code }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
