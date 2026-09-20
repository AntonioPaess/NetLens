package com.antoniopaess.netlens.domain

/**
 * Commands accepted by the connection engine. Keeping commands in the domain
 * lets a repository adapt the engine without making use cases depend on a
 * feature implementation.
 */
sealed interface ConnectionCommand {
    /** Start a new session when the engine is currently disconnected. */
    data class Start(
        val region: Region,
    ) : ConnectionCommand

    /** Retry a failed session with a fresh connection timeout budget. */
    data object Retry : ConnectionCommand

    /** Cancel the current session and return to the disconnected state. */
    data object Disconnect : ConnectionCommand
}
