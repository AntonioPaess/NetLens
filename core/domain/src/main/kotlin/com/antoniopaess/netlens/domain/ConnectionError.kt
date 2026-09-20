package com.antoniopaess.netlens.domain

/**
 * Typed failures exposed by the connection domain so presentation code can map
 * them to localized text without parsing exception messages.
 */
sealed interface ConnectionError {
    /** A network operation failed without a more specific server diagnosis. */
    data object NetworkError : ConnectionError

    /** The connection attempt exceeded its time budget. */
    data object Timeout : ConnectionError

    /** The simulated endpoint rejected the request as unavailable. */
    data object ServerUnavailable : ConnectionError

    /** A failure that crossed the domain boundary without a known mapping. */
    data object Unknown : ConnectionError
}
