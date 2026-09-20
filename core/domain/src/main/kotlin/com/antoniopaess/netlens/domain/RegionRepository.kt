package com.antoniopaess.netlens.domain

import kotlinx.coroutines.flow.Flow

/**
 * Identifies which source produced one region snapshot.
 *
 * Keeping this metadata in the domain lets a future presentation layer explain
 * that it is showing a usable local snapshot while a network refresh is being
 * attempted.
 */
enum class RegionDataSource {
    /** The snapshot came from the Room-backed local source. */
    CACHE,

    /** The snapshot came from the public REST service and was persisted locally. */
    REMOTE,
}

/**
 * Typed failures that can occur while refreshing the region list.
 *
 * The repository deliberately exposes categories and the HTTP status only;
 * exception instances, response bodies, and request metadata stay inside the
 * data layer.
 */
sealed interface RegionDataError {
    /** The device has no usable network at the time of the refresh. */
    data object NetworkUnavailable : RegionDataError

    /** DNS could not resolve the public endpoint. */
    data object DnsResolution : RegionDataError

    /** TLS negotiation with the public endpoint failed. */
    data object Tls : RegionDataError

    /** The endpoint returned an HTTP failure response. */
    data class Http(
        val statusCode: Int,
    ) : RegionDataError

    /** The refresh exceeded its configured time budget. */
    data object Timeout : RegionDataError

    /** The failure did not match a more precise category. */
    data object Unknown : RegionDataError
}

/**
 * One emission from the offline-first region stream.
 *
 * A cached snapshot remains [Available] when its refresh fails. The optional
 * [refreshError] gives callers a retry affordance without making the already
 * usable list disappear.
 */
sealed interface RegionLoadState {
    /** The local read or remote refresh has not produced a snapshot yet. */
    data object Loading : RegionLoadState

    /** A region snapshot is available from either local or remote storage. */
    data class Available(
        val regions: List<Region>,
        val source: RegionDataSource,
        val refreshError: RegionDataError? = null,
    ) : RegionLoadState

    /** No local data exists and the remote refresh failed. Collect again to retry. */
    data class Unavailable(
        val error: RegionDataError,
    ) : RegionLoadState
}

/**
 * Supplies selectable regions through an offline-first stream.
 *
 * Each collection reads Room before contacting the remote source. A new
 * collection retries the refresh, which keeps retry orchestration out of the
 * domain contract and lets callers cancel an old attempt through structured
 * concurrency.
 */
interface RegionRepository {
    /** Observe cache-first data and the result of the subsequent refresh. */
    fun observeRegions(): Flow<RegionLoadState>

    /** Start a fresh cache-first refresh; collecting the returned flow retries the operation. */
    fun retry(): Flow<RegionLoadState> = observeRegions()
}
