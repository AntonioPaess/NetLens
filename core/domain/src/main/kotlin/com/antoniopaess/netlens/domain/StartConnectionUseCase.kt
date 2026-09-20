package com.antoniopaess.netlens.domain

/**
 * Starts a selected region through the repository boundary so callers never
 * couple themselves to the feature state machine.
 */
class StartConnectionUseCase(
    private val repository: ConnectionRepository,
) {
    /** Dispatch a start command for [region]. */
    suspend operator fun invoke(region: Region) {
        repository.dispatch(ConnectionCommand.Start(region))
    }
}
