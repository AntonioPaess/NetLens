package com.antoniopaess.netlens.domain

/**
 * A selectable endpoint used by the connection simulation.
 *
 * Latency is expressed in milliseconds so the model stays independent from
 * Android and can be used by both production adapters and virtual-time tests.
 */
data class Region(
    val name: String,
    val code: String,
    val simulatedLatency: Long,
) {
    init {
        require(name.isNotBlank())
        require(code.isNotBlank())
        require(simulatedLatency >= 0)
    }
}
